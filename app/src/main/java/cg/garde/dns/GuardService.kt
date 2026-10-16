package cg.garde.dns

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class GuardService : AccessibilityService() {

    companion object {
        @Volatile var running = false
    }

    private var lastDnsApply = 0L
    private var lastScreenScan = 0L
    private var lastWebScan = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Guard.log(this, "Protection (accessibilité) démarrée", 30_000L)
        if (Guard.isLocked(this)) Guard.startWatchdog(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        Guard.log(this, "Protection (accessibilité) arrêtée", 30_000L)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
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
            if (t - lastScreenScan < 250L) return
            lastScreenScan = t
            val root = rootInActiveWindow ?: return
            val hit = Guard.screenHit(root, dnsBad)
            if (hit != null) {
                Guard.log(this, "Écran protégé bloqué : $hit", 30_000L)
                performGlobalAction(GLOBAL_ACTION_HOME)
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
