package com.cerniera.sveglia

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The morning line (14 Nov 2026): at the ring, one line under the time with the day's first thing,
 * "Arsenal Women at 16:30 · Rain from 10:00, 9°". The first event comes from the phone's calendar
 * (only with permission); the rain and temperature from Open-Meteo for the place the sky is seen
 * from (no key, no account). Each half can be switched off, and the line is simply left out when
 * there is nothing to say or no network.
 *
 * And the evening before: if tomorrow starts early and no alarm covers it, the app suggests one.
 */
object Morning {
    class Event(val title: String, val start: ZonedDateTime)

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    private fun prefs(c: Context) = c.getSharedPreferences("morning", Context.MODE_PRIVATE)

    fun calendarAllowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun showEvent(c: Context) = prefs(c).getBoolean("event", true)
    fun showWeather(c: Context) = prefs(c).getBoolean("weather", true)
    fun setEvent(c: Context, on: Boolean) = prefs(c).edit().putBoolean("event", on).apply()
    fun setWeather(c: Context, on: Boolean) = prefs(c).edit().putBoolean("weather", on).apply()

    /** The first timed event starting in [from, until), from visible calendars, not cancelled or declined. */
    fun firstEvent(c: Context, from: ZonedDateTime, until: ZonedDateTime): Event? {
        if (!calendarAllowed(c)) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toInstant().toEpochMilli()); ContentUris.appendId(it, until.toInstant().toEpochMilli())
        }.build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS, CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.VISIBLE)
        return runCatching {
            c.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val begin = cur.getLong(1)
                    if (cur.getInt(2) == 1) continue
                    if (cur.getInt(3) == CalendarContract.Instances.STATUS_CANCELED) continue
                    if (cur.getInt(4) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    if (cur.getInt(5) == 0) continue
                    if (begin < from.toInstant().toEpochMilli()) continue
                    val title = cur.getString(0)?.trim().takeUnless { it.isNullOrEmpty() } ?: "An event"
                    return@use Event(title, Instant.ofEpochMilli(begin).atZone(from.zone))
                }
                null
            }
        }.getOrNull()
    }

    /** Today's first event still to come, for the ring. */
    fun todayFirst(c: Context, now: ZonedDateTime = Alarm.now()) =
        firstEvent(c, now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))

    // ---- Weather -----------------------------------------------------------------------------

    /** Today's hours: time, chance of rain (%), rain (mm), temperature (°C). Kept for six hours. */
    private class Hours(val at: Long, val times: List<ZonedDateTime>, val chance: List<Int>, val rain: List<Double>, val temp: List<Double>)

    /** Fetches today's forecast for the sky's place, on the calling (background) thread. */
    fun fetchWeather(c: Context) {
        val p = Daylight.place
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.ROOT, p.lat, p.lon) +
            "&hourly=temperature_2m,precipitation_probability,precipitation&forecast_days=2&timezone=auto"
        val body = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 6000; readTimeout = 6000
                try { inputStream.bufferedReader().readText() } finally { disconnect() }
            }
        }.getOrNull() ?: return
        prefs(c).edit().putString("forecast", body).putLong("forecast_at", System.currentTimeMillis()).apply()
    }

    private fun hours(c: Context): Hours? {
        val at = prefs(c).getLong("forecast_at", 0L)
        if (System.currentTimeMillis() - at > 6 * 3_600_000L) return null
        return runCatching {
            val j = JSONObject(prefs(c).getString("forecast", null) ?: return null)
            val zone = runCatching { ZoneId.of(j.getString("timezone")) }.getOrDefault(ZoneId.systemDefault())
            val h = j.getJSONObject("hourly")
            val t = h.getJSONArray("time"); val pc = h.getJSONArray("precipitation_probability")
            val pr = h.getJSONArray("precipitation"); val tt = h.getJSONArray("temperature_2m")
            val n = t.length()
            Hours(at, List(n) { java.time.LocalDateTime.parse(t.getString(it)).atZone(zone) },
                List(n) { pc.optInt(it, 0) }, List(n) { pr.optDouble(it, 0.0) }, List(n) { tt.optDouble(it, Double.NaN) })
        }.getOrNull()
    }

    /** "Rain from 10:00, 9°", "Dry today, 9°", or null without a fresh forecast. */
    fun weatherLine(c: Context, now: ZonedDateTime = Alarm.now()): String? {
        val h = hours(c) ?: return null
        val end = now.toLocalDate().atTime(21, 0).atZone(now.zone)
        val idx = h.times.indices.filter { !h.times[it].isBefore(now.minusMinutes(59)) && h.times[it].isBefore(end) }
        if (idx.isEmpty()) return null
        val temp = h.temp[idx.first()].takeIf { !it.isNaN() }?.let { "${it.roundToInt()}°" }
        val wet = idx.firstOrNull { h.chance[it] >= 50 && h.rain[it] >= 0.1 }
        val sky = when {
            wet == null -> "Dry today"
            !h.times[wet].isAfter(now) -> "Raining now"
            else -> "Rain from " + h.times[wet].format(HHMM)
        }
        return listOfNotNull(sky, temp).joinToString(", ")
    }

    /** The whole line for the ring, or "" when there is nothing to say. Call off the main thread. */
    fun line(c: Context): String {
        val now = Alarm.now()
        val event = if (showEvent(c)) todayFirst(c, now)?.let { "${shorten(it.title)} at ${it.start.format(HHMM)}" } else null
        val weather = if (showWeather(c)) weatherLine(c, now) else null
        return listOfNotNull(event, weather).joinToString(" · ")
    }

    private fun shorten(s: String) = if (s.length <= 28) s else s.take(27).trimEnd() + "…"

    // ---- The evening before ------------------------------------------------------------------

    class Suggestion(val event: Event, val wake: LocalTime)

    /**
     * If tomorrow's first event starts before noon and no alarm will ring tomorrow at least half an
     * hour before it, a one-off alarm an hour and a half before it, rounded down to five minutes.
     */
    fun suggestion(c: Context, now: ZonedDateTime = Alarm.now()): Suggestion? {
        if (!showEvent(c) || !calendarAllowed(c)) return null
        val tomorrow: LocalDate = now.toLocalDate().plusDays(1)
        val dayStart = tomorrow.atTime(4, 0).atZone(now.zone)
        val e = firstEvent(c, dayStart, tomorrow.atTime(12, 0).atZone(now.zone)) ?: return null
        val wakeAt = e.start.minusMinutes(90).let { it.withMinute(it.minute - it.minute % 5).withSecond(0).withNano(0) }
        if (wakeAt.isBefore(dayStart) || !wakeAt.isAfter(now)) return null
        val covered = Alarms.all(c).any { a ->
            val r = a.next(tomorrow.atStartOfDay(now.zone).minusSeconds(1))
            r != null && r.toLocalDate() == tomorrow && !r.isAfter(e.start.minusMinutes(30))
        }
        return if (covered) null else Suggestion(e, wakeAt.toLocalTime())
    }
}
