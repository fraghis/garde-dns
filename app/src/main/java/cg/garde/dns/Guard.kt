package cg.garde.dns

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

object Guard {
    const val DEFAULT_DNS = "family.cloudflare-dns.com"
    private const val DAY = 86_400_000L
    private const val WINDOW = DAY // durée pendant laquelle on peut modifier après le délai

    // Si un de ces textes apparaît dans les Réglages pendant le verrou -> retour accueil
    val PROTECTED_TEXTS = listOf("DNS privé", "Private DNS", "DNS privado", "Garde DNS")

    private fun p(ctx: Context) = ctx.getSharedPreferences("garde", Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis()

    // ---- Données enregistrées ----
    fun blocked(ctx: Context): Set<String> =
        p(ctx).getStringSet("blocked", emptySet())!!.toSet()

    fun setBlocked(ctx: Context, s: Set<String>) =
        p(ctx).edit().putStringSet("blocked", HashSet(s)).apply()

    fun dns(ctx: Context): String = p(ctx).getString("dns", DEFAULT_DNS) ?: DEFAULT_DNS
    fun isLocked(ctx: Context) = p(ctx).getBoolean("locked", false)
    fun delayDays(ctx: Context) = p(ctx).getInt("delay", 3)
    private fun requestedAt(ctx: Context) = p(ctx).getLong("req", 0L)

    // ---- Logique du délai ----
    fun readyAt(ctx: Context): Long {
        val r = requestedAt(ctx)
        return if (r == 0L) 0L else r + delayDays(ctx) * DAY
    }
    fun windowEnd(ctx: Context): Long = readyAt(ctx) + WINDOW
    fun requestPending(ctx: Context): Boolean {
        val r = readyAt(ctx)
        return r > 0L && now() < r
    }
    fun windowOpen(ctx: Context): Boolean {
        val r = readyAt(ctx)
        return r > 0L && now() >= r && now() < r + WINDOW
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
        return applyDns(ctx)
    }

    fun requestUnlock(ctx: Context) {
        if (isLocked(ctx) && !requestPending(ctx) && !windowOpen(ctx))
            p(ctx).edit().putLong("req", now()).apply()
    }

    fun cancelRequest(ctx: Context) {
        if (requestPending(ctx)) p(ctx).edit().putLong("req", 0L).apply()
    }

    fun disableLock(ctx: Context) {
        if (windowOpen(ctx)) p(ctx).edit().putBoolean("locked", false).putLong("req", 0L).apply()
    }

    // ---- DNS privé ----
    fun hasSecurePermission(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

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

    fun fmt(ms: Long): String {
        val m = (ms.coerceAtLeast(0L)) / 60_000
        return "${m / 1440} j ${(m % 1440) / 60} h ${m % 60} min"
    }
}
