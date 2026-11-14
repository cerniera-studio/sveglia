package com.cerniera.sveglia

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import java.time.Instant
import kotlin.math.abs
import com.cerniera.kit.Sky as Heavens

/**
 * The real sky over the phone, from the Kit's one sky (14 Nov 2026), so Sveglia's sun agrees with
 * Solco's. The alarm brings the light; the sun appears only when it is really up:
 * - after sunrise, it stands where it really is, and the dawn is brightness and warmth;
 * - between first light (civil dawn, 6° below) and sunrise, it is just below the horizon, its glow
 *   showing above it;
 * - before first light it stays hidden, and only the horizon warms.
 *
 * The place is the phone's (coarse), taken only while Sveglia is open, kept, and Leeds until then.
 */
object Daylight {
    @Volatile var place: Heavens.Place = Heavens.Place.LEEDS
        private set
    /** When [place] came from the phone, epoch ms; 0 for the Leeds fallback. */
    @Volatile var placeAt = 0L
        private set

    /** How high the risen sun climbs on the wake screen: it is at the top of its climb from this altitude up. */
    const val CLIMB = 25.0

    private fun prefs(c: Context) = c.getSharedPreferences("sky", Context.MODE_PRIVATE)

    fun load(c: Context) {
        val p = prefs(c)
        val name = p.getString("name", null) ?: return
        place = Heavens.Place(name, p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble())
        placeAt = p.getLong("at", 0L)
    }

    /** The sun's altitude, degrees, at [ms]. */
    fun altitude(ms: Long): Double = Heavens.sun(place, Instant.ofEpochMilli(ms)).altitude

    /**
     * Where the sun stands between hidden and the top of its climb: below 0 it is under the horizon
     * (0 is its upper edge on the horizon, sunrise), 1 at [CLIMB] degrees and above.
     */
    fun rise(alt: Double): Float = ((alt - Heavens.HORIZON) / (CLIMB - Heavens.HORIZON)).toFloat().coerceIn(-1f, 1f)

    /** Twilight's share of the sun's glow: 0 before first light, rising to 1 at sunrise. */
    fun twilight(alt: Double): Float = ((alt - Heavens.CIVIL) / (Heavens.HORIZON - Heavens.CIVIL)).toFloat().coerceIn(0f, 1f)

    /** For the settings row: "Leeds, until your location is used" or "Harrogate, from your location". */
    fun describe(): String = if (placeAt == 0L) "${place.name}, until your location is used" else "${place.name}, from your location"

    fun hasLocation(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Takes the phone's place while Sveglia is open (never in the background). Coarse only: a few
     * kilometres moves sunrise by seconds. Calls [done] on the main thread with true if it moved.
     */
    fun follow(c: Context, done: (Boolean) -> Unit) {
        if (!hasLocation(c)) { done(false); return }
        val lm = c.getSystemService(LocationManager::class.java) ?: run { done(false); return }
        fun finish(loc: Location?) = Thread {
            val moved = loc?.let { runCatching { keep(c, it) }.getOrDefault(false) } ?: false
            c.mainExecutor.execute { done(moved) }
        }.start()
        val last = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3_600_000L) { finish(last); return }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) { finish(last); return }
        runCatching { lm.getCurrentLocation(provider, null, c.mainExecutor) { loc -> finish(loc ?: last) } }
            .onFailure { finish(last) }
    }

    private fun keep(c: Context, loc: Location): Boolean {
        val name = runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(c).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: "Here"
        val moved = abs(loc.latitude - place.lat) > 0.01 || abs(loc.longitude - place.lon) > 0.01 || placeAt == 0L
        placeAt = System.currentTimeMillis()
        if (moved) place = Heavens.Place(name, loc.latitude, loc.longitude)
        prefs(c).edit().putString("name", place.name).putFloat("lat", place.lat.toFloat())
            .putFloat("lon", place.lon.toFloat()).putLong("at", placeAt).apply()
        return moved
    }
}
