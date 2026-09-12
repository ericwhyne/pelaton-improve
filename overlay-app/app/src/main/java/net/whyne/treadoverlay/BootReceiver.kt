package net.whyne.treadoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Auto-starts the overlay after the tablet boots, so a power loss or reboot
 * never requires touching the app icon. Only fires if the overlay permission
 * is already granted (first-run setup still goes through MainActivity).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(OverlayService.TAG, "boot: overlay permission missing, not starting")
            return
        }
        Log.i(OverlayService.TAG, "boot: starting overlay service")
        context.startForegroundService(Intent(context, OverlayService::class.java))
    }
}
