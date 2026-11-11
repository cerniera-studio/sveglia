package com.cerniera.sveglia

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The alarms, kept in the app's preferences, and the two system alarms that serve them: the ring
 * itself (set as an alarm clock, so the status bar and lock screen show the real wake time and the
 * system wakes the phone for it), and the dawn before it.
 */
object Alarms {
    private fun prefs(c: Context) = c.getSharedPreferences("sveglia", Context.MODE_PRIVATE)

    fun all(c: Context): List<Alarm> = runCatching {
        val a = JSONArray(prefs(c).getString("alarms", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val days = o.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) }.toSet() } ?: emptySet()
            Alarm(o.getInt("id"), o.getInt("h"), o.getInt("m"), days, o.optString("label"), o.optInt("dawn", 20),
                o.optBoolean("on", true), if (o.has("sound")) o.getString("sound") else null)
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Alarm>) {
        val a = JSONArray()
        list.forEach { x ->
            a.put(JSONObject().put("id", x.id).put("h", x.hour).put("m", x.minute).put("days", JSONArray(x.days.sorted()))
                .put("label", x.label).put("dawn", x.dawn).put("on", x.enabled).apply { x.sound?.let { put("sound", it) } })
        }
        prefs(c).edit().putString("alarms", a.toString()).apply()
        schedule(c)
    }

    fun put(c: Context, a: Alarm) = save(c, all(c).filter { it.id != a.id } + a)
    fun remove(c: Context, id: Int) = save(c, all(c).filter { it.id != id })
    fun get(c: Context, id: Int) = all(c).firstOrNull { it.id == id }
    fun newId(c: Context) = (all(c).maxOfOrNull { it.id } ?: 0) + 1

    // ---- Snooze ------------------------------------------------------------------------------

    fun snooze(c: Context, id: Int, minutes: Int = 9) {
        prefs(c).edit().putLong("snooze_at", System.currentTimeMillis() + minutes * 60_000L).putInt("snooze_id", id).apply()
        schedule(c)
    }

    fun clearSnooze(c: Context) { prefs(c).edit().remove("snooze_at").remove("snooze_id").apply() }

    private fun snoozed(c: Context): Pair<Long, Int>? {
        val at = prefs(c).getLong("snooze_at", 0L)
        return if (at > System.currentTimeMillis()) at to prefs(c).getInt("snooze_id", -1) else null
    }

    /**
     * After an alarm has been stopped, rung or skipped during its dawn: a one-off switches itself
     * off, and a repeating one is marked done up to [ringAt] so a skipped ring doesn't still sound.
     */
    fun rang(c: Context, id: Int, ringAt: Long) {
        clearSnooze(c)
        prefs(c).edit().putLong("done_$id", ringAt).apply()
        val a = get(c, id)
        if (a != null && a.days.isEmpty()) put(c, a.copy(enabled = false)) else schedule(c)
    }

    /** The next sound of [a] after [from] that hasn't already been dealt with. */
    private fun upcoming(c: Context, a: Alarm, from: ZonedDateTime): ZonedDateTime? {
        val done = prefs(c).getLong("done_${a.id}", 0L)
        var t = a.next(from) ?: return null
        if (t.toInstant().toEpochMilli() <= done) t = a.next(t.plusSeconds(1)) ?: return null
        return t
    }

    // ---- What comes next ---------------------------------------------------------------------

    data class Next(val id: Int, val ring: ZonedDateTime, val dawnStart: ZonedDateTime?, val snooze: Boolean)

    fun next(c: Context, from: ZonedDateTime = Alarm.now()): Next? {
        val candidates = all(c).mapNotNull { a ->
            upcoming(c, a, from)?.let { ring ->
                val d = ring.minusMinutes(a.dawn.toLong())
                Next(a.id, ring, if (a.dawn > 0) (if (d.isAfter(from)) d else null) else null, false)
            }
        }.toMutableList()
        snoozed(c)?.let { (at, id) -> candidates += Next(id, ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()), null, true) }
        return candidates.minByOrNull { it.ring.toInstant() }
    }

    fun canExact(c: Context) = c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val ring = pending(c, AlarmReceiver.RING, 0)
        val dawn = pending(c, AlarmReceiver.DAWN, 0)
        am.cancel(ring); am.cancel(dawn)
        val n = next(c) ?: return
        if (!canExact(c)) return
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(n.ring.toInstant().toEpochMilli(), show), pending(c, AlarmReceiver.RING, n.id)) }
        n.dawnStart?.let { d ->
            if (d.isBefore(n.ring)) runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d.toInstant().toEpochMilli(), pending(c, AlarmReceiver.DAWN, n.id)) }
        }
    }

    private fun pending(c: Context, action: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(c, if (action == AlarmReceiver.RING) 1 else 2,
            Intent(c, AlarmReceiver::class.java).setAction(action).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** The system's alarms arrive here and hand over to the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        when (i.action) {
            DAWN, RING -> c.startForegroundService(Intent(c, RingService::class.java).setAction(i.action).putExtra("id", id))
        }
    }

    companion object {
        const val DAWN = "com.cerniera.sveglia.DAWN"
        const val RING = "com.cerniera.sveglia.RING"
    }
}

/** Alarms don't survive a restart or a clock change on their own: set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Alarms.schedule(c)
}
