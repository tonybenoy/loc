package io.github.tonybenoy.loc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.wantsTracking(context)) {
            try {
                TrackingService.start(context)
            } catch (_: Exception) {
                // Background-start restrictions: the user reopens the app and the health screen shows the gap.
            }
        }
    }
}
