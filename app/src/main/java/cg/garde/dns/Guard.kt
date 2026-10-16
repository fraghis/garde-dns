package cg.garde.dns

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

object Guard {
    const val DEFAULT_DNS = "e758ce.dns.nextdns.io"
    private const val DAY = 86_400_000L
    private const val WINDOW = DAY // durée pendant laquelle on peut modifier après le délai

    // Fuseau fixe : changer le fuseau horaire du téléphone ne change donc rien aux horaires.
    private val TZ: TimeZone = TimeZone.getTimeZone("Africa/Brazzaville")

    // ---- Applications ----
    /** Jamais bloquées, jamais proposées dans la liste. */
    private val ESSENTIAL = setOf(
        "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer",
        "com.samsung.android.incallui", "com.android.incallui",
        "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms",
        "com.samsung.android.contacts", "com.android.contacts",
        "com.sec.android.app.camera", "com.sec.android.app.clockpackage", "com.google.android.deskclock",
        "com.android.emergency", "com.google.android.apps.maps",
        "com.android.systemui", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher"
    )
    val ALWAYS_BLOCKED = setOf("com.samsung.knox.securefolder")
    val BROWSERS = setOf(
        "com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "com.brave.browser", "com.opera.browser", "com.opera.mini.native", "com.microsoft.emmx",
        "com.duckduckgo.mobile.android", "com.google.android.googlequicksearchbox"
    )
    private val KEYWORD_APPS = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
        "com.twitter.android", "com.reddit.frontpage", "com.instagram.android"
    )

    fun isEssential(pkg: String) = pkg in ESSENTIAL
    fun isKeywordApp(pkg: String) = pkg in KEYWORD_APPS || pkg in BROWSERS
    fun isRisky(pkg: String, blocked: Set<String>) = pkg in blocked || pkg in BROWSERS || pkg in KEYWORD_APPS
    fun isSettingsLike(pkg: String) =
        pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("permissioncontroller") || pkg.contains("vending") || pkg.contains("lool")

    // ---- Écrans protégés (textes cherchés dans les Réglages) ----
    private val SELF_TEXTS = listOf("Garde DNS")
    // Écran Accessibilité : on cherche des textes présents seulement dans cette page (pas dans la liste principale des Réglages)
    private val ACCESS_TEXTS = listOf(
        "TalkBack", "Applications installées", "Installed apps", "Services installés", "Installed services",
        "Interaction et dextérité", "Interaction and dexterity", "Améliorations de la visibilité", "Visibility enhancements"
    )
    private val DNS_TEXTS = listOf("DNS privé", "Private DNS", "DNS privado")
    private val TIME_TEXTS = listOf(
        "Date et heure automatiques", "Automatic date and time", "Définir la date", "Set date",
        "Fuseau horaire", "Time zone"
    )
    private val TETHER_TEXTS = listOf(
        "Partage via USB", "USB tethering", "Partage via Bluetooth", "Bluetooth tethering",
        "Partage via Ethernet", "Ethernet tethering"
    )
    private val VPN_TEXTS = listOf(
        "Ajouter un VPN", "Add VPN", "Ajouter un profil VPN", "VPN permanent", "Always-on VPN"
    )
    private val USER_TEXTS = listOf(
        "Ajouter un utilisateur", "Add user", "Ajouter un invité", "Add guest", "Nouvel utilisateur"
    )

    /** Renvoie le nom de l'écran protégé affiché, ou null. */
    fun screenHit(root: AccessibilityNodeInfo, dnsBad: Boolean): String? {
        val groups = ArrayList<Pair<String, List<String>>>()
        groups.add("Garde DNS" to SELF_TEXTS)
        groups.add("Accessibilité" to ACCESS_TEXTS)
        if (!dnsBad) groups.add("DNS privé" to DNS_TEXTS)
        groups.add("Date et heure" to TIME_TEXTS)
        groups.add("Partage de connexion" to TETHER_TEXTS)
        groups.add("VPN" to VPN_TEXTS)
        groups.add("Utilisateurs" to USER_TEXTS)
        for ((name, texts) in groups) {
            for (t in texts) {
                if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return name
            }
        }
        return null
    }

    fun nextDnsPageShown(root: AccessibilityNodeInfo) =
        root.findAccessibilityNodeInfosByText("nextdns").isNotEmpty()

    // ---- Mots-clés (détection du contenu à l'écran) ----
    private val STRONG = Pattern.compile(
        "\\b(porn|porno|pornhub|xvideos|xnxx|xhamster|redtube|youporn|hentai)\\b",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val WEAK = Pattern.compile(
        "\\b(xxx|nsfw|nude|nudes|sexe|sex|sexy|onlyfans|erotique|érotique|erotic)\\b",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )
    private val WEAK_AGE = Pattern.compile("(?<![0-9])(\\+18|18\\+)")

    /** Un mot très explicite, ou au moins deux mots différents moins explicites. */
    fun keywordHit(text: String): String? {
        val s = STRONG.matcher(text)
        if (s.find()) return "fort"
        val found = HashSet<String>()
        val w = WEAK.matcher(text)
        while (w.find()) found.add(w.group().lowercase())
        if (WEAK_AGE.matcher(text).find()) found.add("18+")
        return if (found.size >= 2) "faible" else null
    }

    fun collectText(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, count: IntArray) {
        if (n == null || depth > 30 || count[0] > 500) return
        count[0]++
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until n.childCount) collectText(n.getChild(i), sb, depth + 1, count)
    }

    // ---- Données enregistrées ----
    private fun p(ctx: Context) = ctx.getSharedPreferences("garde", Context.MODE_PRIVATE)

    fun blocked(ctx: Context): Set<String> =
        p(ctx).getStringSet("blocked", emptySet())!!.toSet()

    fun setBlocked(ctx: Context, s: Set<String>) =
        p(ctx).edit().putStringSet("blocked", HashSet(s)).apply()

    fun dns(ctx: Context): String = p(ctx).getString("dns", DEFAULT_DNS) ?: DEFAULT_DNS
    fun isLocked(ctx: Context) = p(ctx).getBoolean("locked", false)
    fun delayDays(ctx: Context) = p(ctx).getInt("delay", 1)
    private fun requestedAt(ctx: Context) = p(ctx).getLong("req", 0L)

    fun initDone(ctx: Context) = p(ctx).getBoolean("init", false)
    fun markInit(ctx: Context) = p(ctx).edit().putBoolean("init", true).apply()

    /** 0 = désactivé, 1 = observation (journal seulement), 2 = actif (renvoie à l'accueil). */
    fun kwMode(ctx: Context) = p(ctx).getInt("kw", 1)
    fun setKwMode(ctx: Context, m: Int) = p(ctx).edit().putInt("kw", m).apply()
    fun kwName(m: Int) = when (m) { 0 -> "désactivée"; 1 -> "observation"; else -> "active" }

    fun note(ctx: Context): String = p(ctx).getString("note", "") ?: ""
    fun setNote(ctx: Context, s: String) = p(ctx).edit().putString("note", s).apply()

    // ---- Heure fiable ----
    private fun bootCount(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Heure réelle vérifiée sur internet puis avancée par le compteur interne. Null si inconnue. */
    fun trustedNow(ctx: Context): Long? {
        val sp = p(ctx)
        val aw = sp.getLong("a_wall", 0L)
        if (aw == 0L) return null
        if (sp.getInt("a_boot", -2) != bootCount(ctx)) return null
        val el = SystemClock.elapsedRealtime() - sp.getLong("a_elapsed", 0L)
        if (el < 0L) return null
        return aw + el
    }

    fun now(ctx: Context): Long = trustedNow(ctx) ?: System.currentTimeMillis()

    private fun setAnchor(ctx: Context, wall: Long) {
        p(ctx).edit()
            .putLong("a_wall", wall)
            .putLong("a_elapsed", SystemClock.elapsedRealtime())
            .putInt("a_boot", bootCount(ctx))
            .apply()
    }

    @Volatile private var syncing = false

    fun syncAsync(ctx: Context) {
        if (syncing) return
        syncing = true
        val app = ctx.applicationContext
        Thread {
            try { syncTime(app) } catch (e: Exception) { } finally { syncing = false }
        }.start()
    }

    private fun syncTime(ctx: Context): Boolean {
        val urls = listOf("http://connectivitycheck.gstatic.com/generate_204", "http://www.google.com")
        for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.instanceFollowRedirects = false
                c.requestMethod = "HEAD"
                val d = c.getHeaderFieldDate("Date", 0L)
                c.disconnect()
                if (d > 1_700_000_000_000L) {
                    val drift = Math.abs(System.currentTimeMillis() - d)
                    val sp = p(ctx)
                    if (drift > 180_000L) {
                        if (!sp.getBoolean("drift", false)) {
                            log(ctx, "Heure du système modifiée (écart de ${drift / 60_000} min) : ignorée")
                            sp.edit().putBoolean("drift", true).apply()
                        }
                    } else if (sp.getBoolean("drift", false)) {
                        sp.edit().putBoolean("drift", false).apply()
                    }
                    setAnchor(ctx, d)
                    return true
                }
            } catch (e: Exception) {
            }
        }
        return false
    }

    fun hourNow(ctx: Context): Int {
        val c = Calendar.getInstance(TZ)
        c.timeInMillis = now(ctx)
        return c.get(Calendar.HOUR_OF_DAY)
    }

    fun isNight(ctx: Context): Boolean {
        val h = hourNow(ctx)
        return h >= 21 || h < 6
    }

    // ---- Logique du délai ----
    fun readyAt(ctx: Context): Long {
        val r = requestedAt(ctx)
        return if (r == 0L) 0L else r + delayDays(ctx) * DAY
    }
    fun windowEnd(ctx: Context): Long = readyAt(ctx) + WINDOW
    fun requestPending(ctx: Context): Boolean {
        val r = readyAt(ctx)
        return r > 0L && now(ctx) < r
    }
    /** La fenêtre ne s'ouvre que si l'heure a été vérifiée sur internet. */
    fun windowOpen(ctx: Context): Boolean {
        val t = trustedNow(ctx) ?: return false
        val r = readyAt(ctx)
        return r > 0L && t >= r && t < r + WINDOW
    }
    /** Vrai quand les blocages sont actifs. */
    fun isProtected(ctx: Context) = isLocked(ctx) && !windowOpen(ctx)

    // ---- Actions ----
    fun lock(ctx: Context, host: String, days: Int): Boolean {
        p(ctx).edit()
            .putString("dns", host)
            .putInt("delay", days)
            .putBoolean("locked", true)
            .putLong("req", 0L)
            .apply()
        log(ctx, "Verrou activé (délai $days j)")
        return applyDns(ctx)
    }

    fun requestUnlock(ctx: Context): Boolean {
        val t = trustedNow(ctx) ?: return false
        if (isLocked(ctx) && !requestPending(ctx) && !windowOpen(ctx)) {
            p(ctx).edit().putLong("req", t).apply()
            log(ctx, "Demande de déverrouillage")
        }
        return true
    }

    fun cancelRequest(ctx: Context) {
        if (requestPending(ctx)) p(ctx).edit().putLong("req", 0L).apply()
    }

    fun disableLock(ctx: Context) {
        if (windowOpen(ctx)) {
            p(ctx).edit().putBoolean("locked", false).putLong("req", 0L).apply()
            log(ctx, "Verrou désactivé")
        }
    }

    // ---- DNS privé ----
    fun hasSecurePermission(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Vrai si le DNS privé du système est bien celui demandé (ou si on ne peut pas le lire). */
    fun dnsOk(ctx: Context, host: String = dns(ctx)): Boolean {
        val cr = ctx.contentResolver
        val mode = Settings.Global.getString(cr, "private_dns_mode") ?: return true
        val spec = Settings.Global.getString(cr, "private_dns_specifier") ?: ""
        return mode == "hostname" && spec.equals(host, ignoreCase = true)
    }

    /** Écrit/maintient le DNS privé (seulement si la permission a été donnée par adb). */
    fun applyDns(ctx: Context): Boolean {
        if (!hasSecurePermission(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val host = dns(ctx)
            if (Settings.Global.getString(cr, "private_dns_mode") != "hostname" ||
                Settings.Global.getString(cr, "private_dns_specifier") != host
            ) {
                Settings.Global.putString(cr, "private_dns_specifier", host)
                Settings.Global.putString(cr, "private_dns_mode", "hostname")
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    // ---- Etats système ----
    fun accessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains(ctx.packageName)
    }

    private fun adminComponent(ctx: Context) = ComponentName(ctx, GardeAdmin::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun adminActive(ctx: Context) = dpm(ctx).isAdminActive(adminComponent(ctx))

    fun removeAdmin(ctx: Context) {
        if (windowOpen(ctx) || !isLocked(ctx)) dpm(ctx).removeActiveAdmin(adminComponent(ctx))
    }

    /** Détecte un point d'accès Wi-Fi / partage USB ou Bluetooth actif (au mieux selon la version d'Android). */
    fun hotspotOn(): Boolean {
        return try {
            val e = NetworkInterface.getNetworkInterfaces() ?: return false
            for (ni in Collections.list(e)) {
                val n = ni.name.lowercase()
                val tether = n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("rndis") || n.startsWith("bt-pan")
                if (tether && ni.isUp && ni.inetAddresses.hasMoreElements()) return true
            }
            false
        } catch (ex: Exception) {
            false
        }
    }

    fun startWatchdog(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, WatchdogService::class.java))
        } catch (e: Exception) {
        }
    }

    // ---- Journal personnel ----
    private val lastLog = HashMap<String, Long>()

    fun log(ctx: Context, msg: String, minGapMs: Long = 0L) {
        synchronized(this) {
            val el = SystemClock.elapsedRealtime()
            val prev = lastLog[msg]
            if (prev != null && el - prev < minGapMs) return
            lastLog[msg] = el
            val f = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            f.timeZone = TZ
            val line = f.format(Date(now(ctx))) + "  " + msg
            val old = p(ctx).getString("journal", "") ?: ""
            val lines = (listOf(line) + old.split("\n").filter { it.isNotBlank() }).take(200)
            p(ctx).edit().putString("journal", lines.joinToString("\n")).apply()
        }
    }

    fun journal(ctx: Context): String = p(ctx).getString("journal", "") ?: ""

    fun beat(ctx: Context) = p(ctx).edit().putLong("beat", System.currentTimeMillis()).apply()
    fun lastBeat(ctx: Context) = p(ctx).getLong("beat", 0L)
    fun clearBeat(ctx: Context) = p(ctx).edit().putLong("beat", 0L).apply()

    fun fmt(ms: Long): String {
        val m = (ms.coerceAtLeast(0L)) / 60_000
        return "${m / 1440} j ${(m % 1440) / 60} h ${m % 60} min"
    }
}
