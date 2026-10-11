package io.github.tonybenoy.loc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Phase 0 collector. Uses only AOSP APIs (LocationManager + significant-motion sensor) so it works
 * without Google services. Only samples and writes; no interpretation.
 */
class TrackingService : Service() {

    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var motion = "UNKNOWN"
    private var significantMotion: Sensor? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = save(l)
        @Deprecated("Deprecated in API 29") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            setMotion("MOVING")
            main.removeCallbacks(backToStill)
            main.postDelayed(backToStill, MOVING_HOLD_MS)
        }
    }

    private val backToStill = Runnable { setMotion("STILL") }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        significantMotion = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setWantsTracking(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.setWantsTracking(this, true)
        Prefs.noteServiceStart(this)
        startForegroundCompat()
        // Without a significant-motion sensor we cannot gate, so sample at the moving rate.
        setMotion(if (significantMotion == null) "UNKNOWN" else "STILL")
        return START_STICKY
    }

    private fun setMotion(state: String) {
        motion = state
        armSensor()
        requestUpdates()
    }

    private fun armSensor() {
        significantMotion?.let { sm.requestTriggerSensor(motionListener, it) }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        lm.removeUpdates(listener)
        val interval = if (motion == "STILL") STILL_INTERVAL_MS else MOVING_INTERVAL_MS
        val minDistance = if (motion == "STILL") 0f else 5f
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            // Network provider only at the still rate; GPS does the moving work.
            val i = if (p == LocationManager.NETWORK_PROVIDER) maxOf(interval, STILL_INTERVAL_MS / 2) else interval
            try {
                lm.requestLocationUpdates(p, i, minDistance, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                stopSelf()
            }
        }
    }

    private fun save(l: Location) {
        val p = RawPoint(
            timestamp = l.time,
            lat = l.latitude,
            lon = l.longitude,
            accuracy = if (l.hasAccuracy()) l.accuracy else null,
            speed = if (l.hasSpeed()) l.speed else null,
            altitude = if (l.hasAltitude()) l.altitude else null,
            source = l.provider ?: "unknown",
            motion = motion,
        )
        val db = AppDb.get(this)
        writer.execute { db.points().insert(p) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Location tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Recording location")
            .setContentText("Stored on this device only")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        lm.removeUpdates(listener)
        significantMotion?.let { sm.cancelTriggerSensor(motionListener, it) }
        writer.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "io.github.tonybenoy.loc.STOP"
        private const val CHANNEL = "tracking"
        const val STILL_INTERVAL_MS = 5 * 60_000L
        const val MOVING_INTERVAL_MS = 30_000L
        private const val MOVING_HOLD_MS = 10 * 60_000L

        fun start(c: Context) =
            ContextCompat.startForegroundService(c, Intent(c, TrackingService::class.java))

        fun stop(c: Context) =
            c.startService(Intent(c, TrackingService::class.java).setAction(ACTION_STOP))
    }
}
