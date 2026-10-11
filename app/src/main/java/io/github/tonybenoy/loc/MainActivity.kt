package io.github.tonybenoy.loc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Phase 0 debug screen: permissions, start/stop, health numbers, CSV export. */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val fmt = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault())

    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button("1. Grant permissions") { requestPermissions() }
        button("2. Exempt from battery optimisation") { requestBatteryExemption() }
        button("Start tracking") { TrackingService.start(this); refreshStatus() }
        button("Stop tracking") { TrackingService.stop(this); refreshStatus() }
        button("Export last 7 days (CSV)") { exportCsv() }
        status = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Android requires foreground location first, then background in a separate request. */
    private fun requestPermissions() {
        val first = buildList {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (first.isNotEmpty()) {
            requestPermissions(first.toTypedArray(), REQ_FOREGROUND)
        } else if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_FOREGROUND) requestPermissions() // continue to the background step
        refreshStatus()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun exportCsv() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "loc-points.csv")
            }, REQ_EXPORT
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        val uri = data?.data
        if (code != REQ_EXPORT || result != RESULT_OK || uri == null) return
        io.execute {
            val pts = AppDb.get(this).points().since(System.currentTimeMillis() - 7 * DAY)
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                w.write("timestamp,lat,lon,accuracy,speed,altitude,source,motion\n")
                pts.forEach {
                    w.write("${it.timestamp},${it.lat},${it.lon},${it.accuracy ?: ""},${it.speed ?: ""},${it.altitude ?: ""},${it.source},${it.motion}\n")
                }
            }
        }
    }

    private fun refreshStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val perms = "fine=${has(Manifest.permission.ACCESS_FINE_LOCATION)} " +
            "background=${Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)} " +
            "batteryExempt=${pm.isIgnoringBatteryOptimizations(packageName)}"
        val header = "wants tracking: ${Prefs.wantsTracking(this)}   service starts: ${Prefs.serviceStarts(this)}\n$perms\n"
        io.execute {
            val dao = AppDb.get(this).points()
            val now = System.currentTimeMillis()
            val last = dao.latest()
            val day = dao.since(now - DAY)
            val gaps = Gaps.find(day)
            val movingGaps = gaps.filter { it.whileMoving }
            val body = buildString {
                append("total points: ${dao.count()}\n")
                append("points last 24h: ${day.size}\n")
                append("last fix: ${last?.let { "${fmt.format(Date(it.timestamp))} (${(now - it.timestamp) / 60_000} min ago, ${it.source}, ${it.motion})" } ?: "none"}\n")
                append("gaps >10 min (24h): ${gaps.size}, while moving: ${movingGaps.size}\n")
                gaps.takeLast(10).forEach {
                    append("  ${fmt.format(Date(it.from))} -> ${fmt.format(Date(it.to))}  ${it.minutes} min${if (it.whileMoving) "  MOVING" else ""}\n")
                }
                append("\nPhase 0 exit: no moving gap >10 min; battery <5%/day (see README).")
            }
            ui.post { status.text = header + body }
        }
    }

    companion object {
        private const val REQ_FOREGROUND = 1
        private const val REQ_BACKGROUND = 2
        private const val REQ_EXPORT = 3
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
