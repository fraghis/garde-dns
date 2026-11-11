package cg.garde.dns

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class GuardService : AccessibilityService() {

    companion object {
        @Volatile var running = false
    }

    private val h = Handler(Looper.getMainLooper())

    /** Vrai tant que le dernier écran vu appartient aux Réglages (ou assimilé) : la relecture régulière est alors active. */
    private var inSettings = false

    private var lastDnsApply = 0L
    private var lastScreenScan = 0L
    private var lastWebScan = 0L

    /** Relit l'écran toutes les 500 ms tant qu'on est dans les Réglages : une page ouverte vite ou lentement n'échappe plus. */
    private val poll = object : Runnable {
        override fun run() {
            try {
                if (inSettings) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isInteractive) scanSettingsWindow()
                }
            } catch (e: Exception) {
            }
            h.postDelayed(this, 500L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Guard.log(this, "Protection (accessibilité) démarrée", 30_000L)
        if (Guard.isLocked(this)) Guard.startWatchdog(this)
        h.removeCallbacksAndMessages(null)
        h.postDelayed(poll, 500L)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        h.removeCallbacksAndMessages(null)
        Guard.log(this, "Protection (accessibilité) arrêtée", 30_000L)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handle(event)
        } catch (e: Exception) {
        }
    }

    /** Retour à l'accueil, répété une fois peu après (certaines pages Samsung se rouvrent pendant la transition). */
    private fun block(what: String) {
        Guard.log(this, "Écran protégé bloqué : $what", 30_000L)
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({
            try {
                if (inSettings && Guard.isProtected(this)) performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
            }
        }, 350L)
    }

    /** Lit la fenêtre active ; si c'est un écran des Réglages protégé, renvoie à l'accueil. */
    private fun scanSettingsWindow() {
        if (!Guard.isProtected(this)) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName || !Guard.isSettingsLike(pkg)) return
        val hit = Guard.screenHit(root, !Guard.dnsOk(this))
        if (hit != null) block(hit)
    }

    private fun handle(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // On quitte les Réglages (accueil, autre appli) : la relecture s'arrête. Le panneau système et le clavier ne comptent pas.
        val neutral = pkg == "com.android.systemui" || pkg.contains("honeyboard") ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")
        if (!Guard.isSettingsLike(pkg) && !neutral) inSettings = false

        if (pkg == packageName) return
        if (Guard.isEssential(pkg)) return

        val prot = Guard.isProtected(this)
        val blocked = Guard.blocked(this)

        // 1) Applications cochées : bloquées de 21h à 6h (heure vérifiée sur internet)
        if (Guard.isNight(this) && pkg in blocked) {
            Guard.log(this, "Blocage de nuit : $pkg", 60_000L)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        if (!prot) return

        // 2) Applications toujours bloquées pendant le verrou (ex. Dossier sécurisé)
        if (pkg in Guard.ALWAYS_BLOCKED) {
            Guard.log(this, "Application interdite pendant le verrou : $pkg", 60_000L)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        val t = SystemClock.elapsedRealtime()

        // 3) Remet le DNS si quelqu'un l'a changé (si la permission adb existe)
        if (t - lastDnsApply > 5000L) {
            lastDnsApply = t
            Guard.applyDns(this)
        }

        // 4) DNS modifié : les applications à risque sont bloquées, les Réglages restent accessibles
        val dnsBad = !Guard.dnsOk(this)
        if (dnsBad && Guard.isRisky(pkg, blocked)) {
            Guard.log(this, "DNS différent du DNS verrouillé : $pkg bloquée", 60_000L)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 5) Écrans sensibles dans les Réglages
        if (Guard.isSettingsLike(pkg)) {
            inSettings = true

            // 5a) Détection par la fenêtre elle-même (nom de l'activité + titre) : ne dépend pas du contenu de la page
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val cls = event.className?.toString() ?: ""
                val titles = event.text.map { it.toString() }
                val hit = Guard.eventHit(cls, titles, dnsBad)
                Guard.diag(this, pkg + " | " + cls + " | " + titles.joinToString(" / ") + (if (hit != null) "  -> BLOQUÉ" else ""))
                if (hit != null) {
                    block(hit)
                    return
                }
            }

            // 5b) Lecture du contenu de la page (en plus de la relecture régulière toutes les 500 ms)
            if (t - lastScreenScan >= 150L) {
                lastScreenScan = t
                scanSettingsWindow()
            }
            return
        }

        // 6) Navigateurs : page NextDNS ; navigateurs et applis à risque : mots-clés
        val isBrowser = pkg in Guard.BROWSERS
        val kwMode = Guard.kwMode(this)
        val wantKw = kwMode != 0 && Guard.isKeywordApp(pkg)
        if (!isBrowser && !wantKw) return
        if (t - lastWebScan < 1500L) return
        lastWebScan = t
        val root = rootInActiveWindow ?: return

        if (isBrowser && Guard.nextDnsPageShown(root)) {
            Guard.log(this, "Page NextDNS bloquée", 30_000L)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        if (wantKw) {
            val sb = StringBuilder()
            Guard.collectText(root, sb, 0, IntArray(1))
            val kind = Guard.keywordHit(sb.toString())
            if (kind != null) {
                if (kwMode == 2) {
                    Guard.log(this, "Mot-clé ($kind) détecté dans $pkg : retour à l'accueil", 30_000L)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    Guard.log(this, "[observation] mot-clé ($kind) détecté dans $pkg", 30_000L)
                }
            }
        }
    }

    override fun onInterrupt() {}
}
