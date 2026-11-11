package cg.garde.dns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Guard.log(ctx, "Redémarrage de l'appareil")
        }
        if (Guard.isLocked(ctx)) Guard.startWatchdog(ctx)
        Guard.syncAsync(ctx)
    }
}
