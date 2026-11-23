package com.cerniera.sveglia

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Cronografo, Sveglia's second face (23 Nov 2026): timers and a stopwatch as a real chronograph,
 * so that with the alarms Sveglia can replace Samsung Clock. Times are kept against the wall clock
 * (System.currentTimeMillis), so a timer survives Sveglia being closed and the phone restarting.
 *
 * A timer's end is an exact alarm (USE_EXACT_ALARM, which Sveglia already declares as an alarm
 * clock), received by [TimerReceiver], which starts [TimerRing]. While anything runs, one quiet
 * notification counts it down (or up), so a timer is visible on every screen.
 */
object Chrono {
    data class Timer(
        val id: Int,
        val label: String,
        /** As set, in milliseconds. */
        val length: Long,
        /** Wall-clock end while running; null while paused. */
        val endsAt: Long?,
        /** What was left when paused. */
        val left: Long,
        val ringing: Boolean = false,
    ) {
        val running get() = endsAt != null && !ringing
        fun remaining(now: Long) = if (endsAt != null) (endsAt - now).coerceAtLeast(0) else left
        fun json() = JSONObject().put("id", id).put("label", label).put("len", length).put("end", endsAt ?: -1L).put("left", left).put("ring", ringing)
        companion object {
            fun from(o: JSONObject) = Timer(o.optInt("id"), o.optString("label"), o.optLong("len"),
                o.optLong("end", -1L).takeIf { it > 0 }, o.optLong("left"), o.optBoolean("ring"))
        }
    }

    data class Watch(val running: Boolean = false, val startedAt: Long = 0, val banked: Long = 0, val laps: List<Long> = emptyList()) {
        fun elapsed(now: Long) = banked + if (running) (now - startedAt).coerceAtLeast(0) else 0
        fun json() = JSONObject().put("run", running).put("start", startedAt).put("bank", banked).put("laps", JSONArray(laps))
        companion object {
            fun from(o: JSONObject?) = if (o == null) Watch() else Watch(o.optBoolean("run"), o.optLong("start"), o.optLong("bank"),
                o.optJSONArray("laps")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList())
        }
    }

    /** The scale printed round the bezel for the stopwatch. */
    enum class Scale { TACHYMETER, PULSOMETER, PLAIN }

    // ---- The watchmaker's arithmetic (pure, tested on the JVM) --------------------------------

    /** Speed over a measured unit (a mile, a kilometre) timed in [ms]: units an hour. Null past a minute, as on a real bezel's last mark. */
    fun tachymeter(ms: Long): Int? = if (ms in 7_200..60_000) (3_600_000.0 / ms).roundToInt() else null

    /** Beats a minute from the time [ms] taken to count [beats] beats (15 on the Cronografo's scale). */
    fun pulsometer(ms: Long, beats: Int = 15): Int? = if (ms in 3_000..60_000) (beats * 60_000.0 / ms).roundToInt() else null

    /** 1:02:03, 12:03, or 0:07 for a countdown; with tenths for the stopwatch. */
    fun clock(ms: Long, tenths: Boolean = false): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000; val m = t / 60_000 % 60; val s = t / 1_000 % 60; val d = t / 100 % 10
        val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        return if (tenths) "$base.$d" else base
    }

    /** "10 min", "1 h 30 min", "45 s": a timer's name when it has no label. */
    fun words(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = s / 60 % 60; val sec = s % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }, sec.takeIf { it > 0 }?.let { "$it s" }).joinToString(" ").ifBlank { "0 s" }
    }

    fun start(t: Timer, now: Long) = t.copy(endsAt = now + t.remaining(now), ringing = false)
    fun pause(t: Timer, now: Long) = t.copy(endsAt = null, left = t.remaining(now))
    fun addMinute(t: Timer, now: Long) = if (t.endsAt != null && !t.ringing) t.copy(endsAt = t.endsAt + 60_000, length = t.length + 60_000)
        else t.copy(endsAt = now + 60_000, left = 60_000, ringing = false)
    fun reset(t: Timer) = t.copy(endsAt = null, left = t.length, ringing = false)

    fun lap(w: Watch, now: Long) = if (w.running) w.copy(laps = w.laps + w.elapsed(now)) else w
    fun toggle(w: Watch, now: Long) = if (w.running) w.copy(running = false, banked = w.elapsed(now)) else w.copy(running = true, startedAt = now)

    /** Each lap's own length, from the split times. */
    fun lapLengths(laps: List<Long>) = laps.mapIndexed { i, v -> v - (if (i == 0) 0 else laps[i - 1]) }

    // ---- Kept ---------------------------------------------------------------------------------

    private fun prefs(c: Context) = c.getSharedPreferences("cronografo", Context.MODE_PRIVATE)

    fun timers(c: Context): List<Timer> = runCatching {
        val a = JSONArray(prefs(c).getString("timers", "[]"))
        (0 until a.length()).map { Timer.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun watch(c: Context): Watch = runCatching { Watch.from(prefs(c).getString("watch", null)?.let(::JSONObject)) }.getOrDefault(Watch())

    fun scale(c: Context) = runCatching { Scale.valueOf(prefs(c).getString("scale", "TACHYMETER")!!) }.getOrDefault(Scale.TACHYMETER)
    fun setScale(c: Context, s: Scale) = prefs(c).edit().putString("scale", s.name).apply()

    /** The last length set on the drums, offered again next time. */
    fun lastLength(c: Context) = prefs(c).getLong("last", 10 * 60_000L)

    fun saveTimers(c: Context, list: List<Timer>) {
        prefs(c).edit().putString("timers", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()
        sync(c)
    }

    fun saveWatch(c: Context, w: Watch) { prefs(c).edit().putString("watch", w.json().toString()).apply(); sync(c) }

    fun put(c: Context, t: Timer) = saveTimers(c, timers(c).filter { it.id != t.id } + t)
    fun remove(c: Context, id: Int) = saveTimers(c, timers(c).filter { it.id != id })
    fun newId(c: Context) = (timers(c).maxOfOrNull { it.id } ?: 0) + 1

    /** Adds and starts a timer of [ms], as Cronografo's Start does and as another app's SET_TIMER asks. */
    fun begin(c: Context, ms: Long, label: String = ""): Timer {
        val now = System.currentTimeMillis()
        val t = Timer(newId(c), label.take(40), ms, now + ms, ms)
        prefs(c).edit().putLong("last", ms).apply()
        put(c, t)
        return t
    }

    /** After a restart, a time change or an update: anything that ended meanwhile rings now, once; the rest are armed again. */
    fun recover(c: Context) {
        val now = System.currentTimeMillis()
        timers(c).filter { it.running && it.endsAt != null && it.endsAt <= now }.forEach { t ->
            put(c, t.copy(ringing = true, left = 0))
            runCatching { c.startForegroundService(Intent(c, TimerRing::class.java).setAction(TimerRing.RING).putExtra("id", t.id)) }
        }
        sync(c)
    }

    // ---- Who is watching --------------------------------------------------------------------------

    private val listeners = mutableSetOf<() -> Unit>()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun listen(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun unlisten(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    /** Something rang or stopped: the open face redraws (on the main thread). */
    fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    // ---- Alarms and the notification ------------------------------------------------------------

    const val CHANNEL = "cronografo"
    const val NOTE_ID = 40

    private fun pending(c: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(c, 4000 + id, Intent(c, TimerReceiver::class.java).setAction(TimerReceiver.END).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Arms every running timer's end, cancels the rest, and redraws the countdown notification. */
    fun sync(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val list = timers(c)
        list.forEach { t ->
            val p = pending(c, t.id)
            if (t.running && t.endsAt != null) runCatching {
                if (Alarms.canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, p)
            } else am.cancel(p)
        }
        // Ends of timers removed since: their ids are below the next id
        val ids = list.map { it.id }.toSet()
        for (id in 1..((list.maxOfOrNull { it.id } ?: 0) + 8)) if (id !in ids) am.cancel(pending(c, id))
        note(c, list, watch(c))
    }

    private fun note(c: Context, list: List<Timer>, w: Watch) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val run = list.filter { it.running }.sortedBy { it.endsAt }
        if (run.isEmpty() && !w.running) { nm.cancel(NOTE_ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers and stopwatch", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What Cronografo is timing, counting on every screen"; setShowBadge(false)
        })
        val open = PendingIntent.getActivity(c, 41, Intent(c, CronografoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH).setContentIntent(open).setShowWhen(true).setUsesChronometer(true)
        val first = run.firstOrNull()
        if (first != null) {
            b.setWhen(first.endsAt!!).setChronometerCountDown(true)
                .setContentTitle(first.label.ifBlank { Chrono.words(first.length) })
                .setContentText(listOfNotNull(if (run.size > 1) "and ${run.size - 1} more" else null, if (w.running) "stopwatch running" else null).joinToString(" · ").ifBlank { "Timer" })
        } else {
            b.setWhen(now - w.elapsed(now)).setContentTitle("Stopwatch").setContentText(if (w.laps.isEmpty()) "Running" else "Lap ${w.laps.size + 1}")
        }
        runCatching { nm.notify(NOTE_ID, b.build()) }
    }
}

/** A timer's end: it rings, until stopped, folded, or two minutes pass. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            END -> {
                val id = i.getIntExtra("id", -1)
                val t = Chrono.timers(c).firstOrNull { it.id == id } ?: return
                Chrono.put(c, t.copy(ringing = true, left = 0))
                c.startForegroundService(Intent(c, TimerRing::class.java).setAction(TimerRing.RING).putExtra("id", id))
            }
        }
    }

    companion object { const val END = "com.cerniera.sveglia.TIMER_END" }
}
