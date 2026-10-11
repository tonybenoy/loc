package io.github.tonybenoy.loc

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("loc", Context.MODE_PRIVATE)

    fun wantsTracking(c: Context) = sp(c).getBoolean("tracking", false)
    fun setWantsTracking(c: Context, v: Boolean) = sp(c).edit().putBoolean("tracking", v).apply()

    fun serviceStarts(c: Context) = sp(c).getInt("starts", 0)
    fun noteServiceStart(c: Context) = sp(c).edit().putInt("starts", serviceStarts(c) + 1).apply()
}
