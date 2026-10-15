package cg.garde.dns

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.util.Calendar

class GuardService : AccessibilityService() {

    private var lastDnsCheck = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return

        // 1) Applications cochées : bloquées de 21h à 6h
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if ((hour >= 21 || hour < 6) && pkg in Guard.blocked(this)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        if (!Guard.isProtected(this)) return

        // 2) Remet le DNS si quelqu'un l'a changé
        val now = System.currentTimeMillis()
        if (now - lastDnsCheck > 5000) {
            lastDnsCheck = now
            Guard.applyDns(this)
        }

        // 3) Interdit les écrans DNS privé / infos de l'app / désinstallation / admin
        if (pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("permissioncontroller") || pkg.contains("vending")
        ) {
            val root = rootInActiveWindow ?: return
            for (t in Guard.PROTECTED_TEXTS) {
                if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
            }
        }
    }

    override fun onInterrupt() {}
}
