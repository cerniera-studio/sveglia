package com.cerniera.sveglia

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One alarm. [days] is a set of weekdays (Monday = 1 .. Sunday = 7); empty means once, at the next
 * occurrence of the time. [dawn] minutes before the time, the screen begins to rise like a sunrise;
 * the sound only starts at the time itself.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val label: String = "",
    val dawn: Int = 20,
    val enabled: Boolean = true,
    val sound: String? = null,
) {
    val time: LocalTime get() = LocalTime.of(hour, minute)

    /** The next moment this alarm sounds after [from], or null if it is off. */
    fun next(from: ZonedDateTime): ZonedDateTime? {
        if (!enabled) return null
        val zone = from.zone
        for (d in 0..7) {
            val date = from.toLocalDate().plusDays(d.toLong())
            if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
            val at = ZonedDateTime.of(date, time, zone)
            if (at.isAfter(from)) return at
        }
        return null
    }

    /** When the dawn begins for the next sound: [dawn] minutes earlier, but never in the past. */
    fun nextDawn(from: ZonedDateTime): ZonedDateTime? {
        val ring = next(from) ?: return null
        val start = ring.minusMinutes(dawn.toLong())
        return if (start.isAfter(from)) start else from.plusSeconds(1)
    }

    /** "Weekdays", "Every day", "Sat, Sun", or "Once". */
    fun repeats(): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(6, 7) -> "Weekends"
        else -> days.sorted().joinToString(", ") { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar(Char::titlecase) }
    }

    companion object {
        /** "In 7 h 32 min", for the line under a time. */
        fun until(from: ZonedDateTime, at: ZonedDateTime): String {
            val m = java.time.Duration.between(from, at).toMinutes().coerceAtLeast(0)
            val h = m / 60; val r = m % 60
            return when {
                m < 1 -> "In under a minute"
                h == 0L -> "In $r min"
                h < 24 -> "In $h h${if (r > 0) " $r min" else ""}"
                else -> "In ${h / 24} d ${h % 24} h"
            }
        }

        fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
    }
}

/**
 * The dawn's light at [p], from 0 (night) to 1 (full day). The sky passes through the colours of a
 * real sunrise over a cool night: deep green-black, a bruised red at the horizon, Alfa's rosso
 * warming to amber, then champagne and ivory. Returns the sky's top and horizon colours and the
 * screen brightness to ask for.
 */
object Dawn {
    private val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val top = intArrayOf(0x0E1311, 0x151A2A, 0x3A2C4A, 0x8C6A7A, 0xE9E3D6)
    private val horizon = intArrayOf(0x0E1311, 0x3A1418, 0xC2561E, 0xE2A857, 0xF6F1E6)

    fun topAt(p: Float) = at(top, p)
    fun horizonAt(p: Float) = at(horizon, p)

    /** Screen brightness for the window, from almost nothing to full. */
    fun brightness(p: Float) = (0.01f + 0.99f * p * p).coerceIn(0.01f, 1f)

    private fun at(c: IntArray, p: Float): Int {
        val q = p.coerceIn(0f, 1f)
        var i = 0
        while (i < stops.size - 2 && q > stops[i + 1]) i++
        val t = (q - stops[i]) / (stops[i + 1] - stops[i])
        fun ch(v: Int, s: Int) = (v shr s) and 255
        fun mix(s: Int) = (ch(c[i], s) + (ch(c[i + 1], s) - ch(c[i], s)) * t).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
