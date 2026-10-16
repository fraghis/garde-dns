package cg.garde.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Surveillance en arrière-plan : vérification de l'heure sur internet, maintien du DNS,
 * journal (protection coupée, partage de connexion, trous de surveillance).
 */
class WatchdogService : Service() {

    private val h = Handler(Looper.getMainLooper())
    private var downSince = 0L
    private var wasDown = false
    private var hotspotLogged = false
    private var lastSync = 0L
    private var lastBeat = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (e: Exception) {
            }
            h.postDelayed(this, 3000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("garde", "Garde DNS", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "garde")
            .setContentTitle("Garde DNS actif")
            .setContentText("Surveillance en cours")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        startForeground(1, n)

        val last = Guard.lastBeat(this)
        if (last > 0L) {
            val gap = System.currentTimeMillis() - last
            if (gap > 15 * 60_000L) {
                Guard.log(this, "Trou de surveillance d'environ ${gap / 60_000} min (arrêt, redémarrage ou mode sans échec)")
            }
        }
        Guard.beat(this)
        h.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun step() {
        if (!Guard.isLocked(this)) {
            Guard.clearBeat(this)
            stopSelf()
            return
        }
        val el = SystemClock.elapsedRealtime()

        // Heure fiable : vérification toutes les 10 min, toutes les 30 s tant qu'elle est inconnue
        if (el - lastSync > 600_000L || (Guard.trustedNow(this) == null && el - lastSync > 30_000L)) {
            lastSync = el
            Guard.syncAsync(this)
        }
        if (el - lastBeat > 60_000L) {
            lastBeat = el
            Guard.beat(this)
        }
        Guard.applyDns(this)

        val prot = Guard.isProtected(this)

        // Protection (accessibilité) coupée ?
        val setOn = Guard.accessibilityOn(this)
        val down = prot && (!setOn || !GuardService.running)
        val grace = if (setOn) 25_000L else 3_000L
        if (down) {
            if (downSince == 0L) downSince = el
            if (el - downSince > grace && !wasDown) {
                wasDown = true
                Guard.log(this, "Protection (accessibilité) coupée pendant le verrou")
            }
        } else {
            downSince = 0L
            if (wasDown) {
                wasDown = false
                Guard.log(this, "Protection rétablie")
            }
        }

        // Partage de connexion actif ?
        val hs = prot && Guard.hotspotOn()
        if (hs && !hotspotLogged) {
            hotspotLogged = true
            Guard.log(this, "Partage de connexion actif")
        } else if (!hs) {
            hotspotLogged = false
        }
    }
}
