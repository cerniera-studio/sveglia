package com.cerniera.sveglia

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent

/**
 * One sky with Solco (14 Nov 2026). When Solco is the wallpaper, the wake screen goes see-through and
 * Solco draws the dawn: Sveglia tells it how far the dawn has come, about once a second, and Solco
 * warms from the hinge behind its grain, with its own disc as the real sun. Sveglia keeps the time,
 * the controls, the sound and the screen's brightness. Any other wallpaper, or the switch off, and
 * Sveglia draws its own sky as before.
 */
object SharedDawn {
    private const val SOLCO = "com.fergusonconsultancy.solco"
    private const val ACTION = "com.cerniera.action.DAWN"

    private fun prefs(c: Context) = c.getSharedPreferences("sveglia", Context.MODE_PRIVATE)
    fun wanted(c: Context) = prefs(c).getBoolean("solco_dawn", true)
    fun setWanted(c: Context, on: Boolean) = prefs(c).edit().putBoolean("solco_dawn", on).apply()

    /** Whether Solco itself (not Sipario) is the wallpaper now. */
    fun solcoIsWallpaper(c: Context): Boolean = runCatching {
        val w = WallpaperManager.getInstance(c).wallpaperInfo ?: return false
        w.packageName == SOLCO && w.serviceName.endsWith(".SolcoWallpaperService")
    }.getOrDefault(false)

    fun active(c: Context) = wanted(c) && solcoIsWallpaper(c)

    /** The dawn's progress for Solco, 0 to 1; -1 ends it. */
    fun send(c: Context, p: Float) {
        runCatching { c.sendBroadcast(Intent(ACTION).setPackage(SOLCO).putExtra("p", p)) }
    }
}
