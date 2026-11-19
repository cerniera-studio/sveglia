package com.cerniera.sveglia

import android.content.Context
import android.net.Uri
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import com.cerniera.kit.Sky as Heavens

/**
 * Mattina (Fluido, 18 Nov 2026): once the alarm is stopped, the wake screen stays a little longer
 * and puts the morning together under the time. Up to three rows (the first event, the sky, the
 * light) and one line written for this morning.
 *
 * Rules only for now: [compose] is pure, so every morning it writes can be tested. The line is the
 * place a model (Gemini Nano on the phone) can take over later, with these rules as the fallback.
 * Rain comes from Cielo when Cielo shares it (the same provider Senso reads), otherwise from
 * Sveglia's own forecast for the morning line.
 */
object Mattina {

    /** One row: a small label, the main words, and a quieter second line. */
    data class Row(val label: String, val main: String, val sub: String? = null)

    data class Page(val rows: List<Row>, val line: String)

    /** The first event still to come today. [where] is the place as written in the calendar. */
    data class First(val title: String, val start: ZonedDateTime, val where: String?)

    /** The next rain: when it starts, how likely (0 to 1), and where the forecast came from. */
    data class Rain(val at: ZonedDateTime, val chance: Float, val from: String)

    /** The day's light. */
    data class Light(val sunrise: ZonedDateTime?, val sunset: ZonedDateTime?, val golden: ZonedDateTime?)

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    private val ONLINE = listOf("http", "teams", "zoom", "meet.google", "google meet", "webex", "skype", "facetime", "online", "call", "virtual")

    /** A place you would have to go to, not a link or a call (the same reading as Senso's). */
    fun physical(where: String?) = where?.trim()?.lowercase()?.let { w -> w.isNotEmpty() && ONLINE.none { it in w } } ?: false

    private fun short(s: String, n: Int = 30) = if (s.length <= n) s else s.take(n - 1).trimEnd() + "…"

    /** "in 40 min", "in 2 h", "in 2 h 15 min". */
    fun inWords(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        return when {
            m < 60 -> "in $m min"
            m % 60 == 0L -> "in ${m / 60} h"
            else -> "in ${m / 60} h ${m % 60} min"
        }
    }

    /** "an hour", "an hour and a half", "40 minutes", "three hours". For the line, in words. */
    fun spoken(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        val words = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
        return when {
            m < 50 -> "$m minutes"
            m < 75 -> "an hour"
            m < 105 -> "an hour and a half"
            else -> ((m + 30) / 60).toInt().let { h -> "${words.getOrElse(h) { "$h" }} hours" }
        }
    }

    /**
     * The page for [now]. [weather] is Sveglia's own forecast line ("Dry today, 9°"), used when
     * Cielo has nothing to share; [rain] is Cielo's next rain, which wins when it is there.
     */
    fun compose(now: ZonedDateTime, first: First?, rain: Rain?, weather: String?, light: Light?): Page {
        val rows = mutableListOf<Row>()
        first?.let { e ->
            val sub = listOfNotNull(inWords(Duration.between(now, e.start)), e.where?.trim()?.takeIf { it.isNotEmpty() }?.let { short(it, 28) })
            rows += Row("FIRST", "${short(e.title)} · ${e.start.format(HHMM)}", sub.joinToString(" · "))
        }
        val rainToday = rain?.takeIf { it.at.toLocalDate() == now.toLocalDate() }
        when {
            rainToday != null -> {
                val pct = "${Math.round(rainToday.chance * 100)}%"
                val main = if (!rainToday.at.isAfter(now)) "Raining now" else "Rain from ${rainToday.at.format(HHMM)}"
                rows += Row("SKY", main, "$pct chance · ${rainToday.from}")
            }
            weather != null -> rows += Row("SKY", weather, if (rain != null) "Next rain tomorrow · Cielo" else null)
        }
        light?.let { l ->
            val rise = l.sunrise; val set = l.sunset
            when {
                rise != null && rise.isAfter(now) && set != null -> rows += Row("LIGHT", "Sunrise ${rise.format(HHMM)}", "Light until ${set.format(HHMM)}")
                set != null && set.isAfter(now) -> rows += Row("LIGHT", "Light until ${set.format(HHMM)}", l.golden?.let { "Golden hour from ${it.format(HHMM)}" })
                else -> {}
            }
        }
        return Page(rows, line(now, first, rainToday, weather, light))
    }

    /** One sentence for this morning, from the rules. The first that applies wins. */
    fun line(now: ZonedDateTime, first: First?, rain: Rain?, weather: String?, light: Light?): String {
        val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        val wetBy = rain?.at
        val dry = rain == null && (weather == null || weather.startsWith("Dry"))
        if (first != null) {
            val until = Duration.between(now, first.start)
            val name = short(first.title, 26)
            // Going out into the rain
            if (wetBy != null && physical(first.where) && wetBy.isBefore(first.start.plusHours(2))) {
                return if (!wetBy.isAfter(now)) "It's raining, so take a brolly to $name."
                else "Rain from ${wetBy.format(HHMM)}, so take a brolly to $name."
            }
            // Soon: how long to get ready
            if (until.toMinutes() <= 105) return "$name at ${first.start.format(HHMM)}: ${spoken(until)} to get ready."
            if (dry) return "A dry $day, and nothing until $name at ${first.start.format(HHMM)}."
            return "${spoken(until).replaceFirstChar { it.uppercase() }} before $name."
        }
        if (wetBy != null) {
            return if (!wetBy.isAfter(now)) "A wet start, and a clear diary."
            else if (wetBy.hour >= 12) "Dry this morning, with rain from ${wetBy.format(HHMM)}."
            else "Rain from ${wetBy.format(HHMM)}, and a clear diary."
        }
        val rise = light?.sunrise
        if (rise != null && rise.isAfter(now)) return "Up before the sun: it rises at ${rise.format(HHMM)}."
        return if (weekend) "$day, and nothing in the diary." else "A clear diary for $day."
    }

    // ---- Gathering, on the phone (off the main thread) ------------------------------------

    /** Cielo's rain, the same row Senso reads. Null when Cielo isn't there or has nothing to say. */
    private val CIELO: Uri get() = Uri.parse("content://com.cerniera.cielo.rain/next")

    fun cieloRain(c: Context, now: ZonedDateTime): Rain? = runCatching {
        c.contentResolver.query(CIELO, null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) null
            else Rain(Instant.ofEpochMilli(cur.getLong(cur.getColumnIndexOrThrow("starts_at"))).atZone(now.zone),
                cur.getFloat(cur.getColumnIndexOrThrow("chance")), "Cielo")
        }
    }.getOrNull()

    /** True when Cielo is installed and sharing, so "no row" means dry rather than unknown. */
    fun cieloSharing(c: Context) = runCatching { c.packageManager.resolveContentProvider(CIELO.authority!!, 0) != null }.getOrDefault(false)

    fun light(now: ZonedDateTime): Light? = runCatching {
        val d = Heavens.day(Daylight.place, now.toLocalDate(), now.zone)
        Light(d.sunrise?.atZone(now.zone), d.sunset?.atZone(now.zone), d.goldenEveningStart?.atZone(now.zone))
    }.getOrNull()

    private fun prefs(c: Context) = c.getSharedPreferences("morning", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("mattina", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("mattina", on).apply()

    /** Everything for the page, as the phone knows it now. Call off the main thread. */
    fun gather(c: Context, now: ZonedDateTime = Alarm.now()): Page {
        val first = if (Morning.showEvent(c)) Morning.todayFirst(c, now)?.let { First(it.title, it.start, it.where) } else null
        val sharing = Morning.showWeather(c) && cieloSharing(c)
        // Cielo's rain when it shares it; otherwise Sveglia's own forecast, so the line and the row agree
        val own = if (Morning.showWeather(c) && !sharing) Morning.nextRain(c, now) else null
        val rain = if (sharing) cieloRain(c, now) else own?.let { Rain(it.first, it.second / 100f, it.third?.let { t -> "$t° now" } ?: "Open-Meteo") }
        val today = rain?.takeIf { it.at.toLocalDate() == now.toLocalDate() }
        val weather = if (Morning.showWeather(c) && today == null) Morning.weatherLine(c, now) else null
        return compose(now, first, rain, weather, light(now))
    }
}
