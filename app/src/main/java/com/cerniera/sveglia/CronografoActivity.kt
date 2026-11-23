package com.cerniera.sveglia

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.AlarmClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.cerniera.kit.Card
import com.cerniera.kit.Chips
import com.cerniera.kit.Crash
import com.cerniera.kit.Hinge
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.SECONDARY
import com.cerniera.kit.Screen
import com.cerniera.kit.Segmented
import com.cerniera.kit.Sheet
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.endValue
import com.cerniera.kit.field
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.text

/**
 * Cronografo: Sveglia's second face, timers and a stopwatch as a chronograph (23 Nov 2026).
 *
 * Every shape: upright on the cover, one column with the dial at the top and the pushers in the
 * right-hand edge at 2 and 4 o'clock; anywhere wider (the cover turned, the main screen either way),
 * the dial in one pane and the laps or timers in the other. Half-folded on a worktop (the hinge
 * between 60° and 150°, the crease across), the dial stands on the upper half and two large pushers
 * lie below the crease, readable across a kitchen.
 *
 * Other apps and assistants can hand a timer over with android.intent.action.SET_TIMER.
 */
class CronografoActivity : Activity(), Hinge.Listener {
    private enum class Face { STOPWATCH, TIMER }
    private var face = Face.TIMER
    private var sheet: Sheet? = null
    private var dial: ChronoDial? = null
    private val pushers = mutableListOf<Pusher>()
    private var hinge: Hinge? = null
    private var halfOpen = false
    private var lengthChosen = 0L
    private val redraw: () -> Unit = { if (sheet == null) render() }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Sveglia")) return
        Kit.follow(this)
        setShowWhenLocked(true)
        lengthChosen = Chrono.lastLength(this)
        face = if (Chrono.timers(this).isEmpty() && Chrono.watch(this).let { it.running || it.banked > 0 }) Face.STOPWATCH else Face.TIMER
        seedForTests()
        if (!handle(intent)) return
        render()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); if (handle(i)) render() }

    /** SET_TIMER and SHOW_TIMERS from other apps; "face" from Sveglia and the notification. False when there is nothing to show. */
    private fun handle(i: Intent?): Boolean {
        if (i == null) return true
        when (i.action) {
            AlarmClock.ACTION_SET_TIMER -> {
                face = Face.TIMER
                val secs = i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
                if (secs in 1..86_399) {
                    val t = Chrono.begin(this, secs * 1000L, i.getStringExtra(AlarmClock.EXTRA_MESSAGE) ?: "")
                    if (i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) {
                        Toast.makeText(this, "Timer set: ${t.label.ifBlank { Chrono.words(t.length) }}", Toast.LENGTH_SHORT).show()
                        finish(); return false
                    }
                }
            }
            AlarmClock.ACTION_SHOW_TIMERS -> face = Face.TIMER
        }
        when (i.getStringExtra("face")) { "timer" -> face = Face.TIMER; "stopwatch" -> face = Face.STOPWATCH }
        return true
    }

    /** Debug builds only, for the emulator's walk: --es demo running seeds two timers and a stopwatch with laps. */
    private fun seedForTests() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        when (intent?.getStringExtra("demo")) {
            "running" -> {
                val now = System.currentTimeMillis()
                Chrono.saveTimers(this, listOf(
                    Chrono.Timer(1, "Pasta", 10 * 60_000L, now + 7 * 60_000L + 12_000, 0),
                    Chrono.Timer(2, "", 25 * 60_000L, now + 18 * 60_000L, 0)))
                Chrono.saveWatch(this, Chrono.Watch(true, now - 83_400, 0, listOf(31_200, 58_900)))
            }
            "one" -> {
                val now = System.currentTimeMillis()
                Chrono.saveTimers(this, listOf(Chrono.Timer(1, "Eggs", 7 * 60_000L, now + 4 * 60_000L + 30_000, 0)))
                Chrono.saveWatch(this, Chrono.Watch())
            }
            "empty" -> { Chrono.saveTimers(this, emptyList()); Chrono.saveWatch(this, Chrono.Watch()) }
            // A timer that runs out six seconds from now, to hear it ring
            "ending" -> { Chrono.saveTimers(this, listOf(Chrono.Timer(1, "Test", 6_000L, System.currentTimeMillis() + 6_000, 0))); Chrono.saveWatch(this, Chrono.Watch()) }
        }
    }

    override fun onResume() {
        super.onResume()
        Chrono.listen(redraw)
        hinge = Hinge(this, this).also { it.start() }
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        Chrono.unlisten(redraw)
        hinge?.stop(); hinge = null
        dial?.removeCallbacks(tick)
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); Kit.follow(this); render() }

    /** The half-folded worktop posture comes and goes with the hinge, not with a configuration change. */
    override fun onAngle(angle: Float) {
        val half = angle in 60f..150f && wideMain()
        if (half != halfOpen) { halfOpen = half; if (sheet == null) render() }
    }

    private fun wideMain() = resources.configuration.let { it.smallestScreenWidthDp >= 600 && it.screenWidthDp > it.screenHeightDp }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { pushers.forEach { it.wake() }; dial?.touched() }
        return super.dispatchTouchEvent(e)
    }

    // ---- The live parts, a few times a second -------------------------------------------------------

    private val listLines = mutableListOf<Pair<Row, () -> String>>()

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            dial?.let { d -> fill(d, now) }
            listLines.forEach { (row, line) -> row.setSub(line()) }
            dial?.postDelayed(this, 250)
        }
    }

    private fun fill(d: ChronoDial, now: Long) {
        if (face == Face.STOPWATCH) {
            val w = Chrono.watch(this)
            d.main = w.elapsed(now); d.length = 0; d.second = null; d.running = w.running; d.ringing = false; d.scale = Chrono.scale(this)
        } else {
            val list = Chrono.timers(this).sortedWith(compareBy<Chrono.Timer> { !it.ringing }.thenBy { if (it.running) 0 else 1 }.thenBy { it.remaining(now) })
            val first = list.firstOrNull()
            d.main = first?.remaining(now) ?: lengthChosen
            d.length = first?.length ?: lengthChosen.coerceAtLeast(1)
            d.second = list.getOrNull(1)?.takeIf { it.running }?.remaining(now)
            d.running = first?.running == true; d.ringing = first?.ringing == true
        }
        d.invalidate()
    }

    // ---- Pages ------------------------------------------------------------------------------------

    private fun render() {
        window.statusBarColor = Kit.GROUND; window.navigationBarColor = Kit.GROUND
        pushers.clear(); listLines.clear()
        val now = System.currentTimeMillis()
        val d = ChronoDial(this).also { dial = it; fill(it, now) }
        val cfg = resources.configuration
        val wide = cfg.screenWidthDp > cfg.screenHeightDp || cfg.smallestScreenWidthDp >= 600
        val root: View = when {
            halfOpen -> worktop(d)
            wide -> twoPanes(d)
            else -> column(d)
        }
        setContentView(insetFrame(root))
        record(root)
        dial?.removeCallbacks(tick); dial?.post(tick)
    }

    private fun insetFrame(content: View) = FrameLayout(this).apply {
        setBackgroundColor(Kit.GROUND)
        addView(content, FrameLayout.LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { v, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
        }
    }

    /** The dial with its two pushers in the right-hand edge at 2 and 4 o'clock. */
    private fun dialBlock(d: ChronoDial, maxDp: Int): View {
        val (top, bottom) = pusherPair()
        val block = object : ViewGroup(this) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val limitH = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(h)
                val side = minOf(width - px(30), px(maxDp), limitH).coerceAtLeast(px(120))
                d.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
                val pw = MeasureSpec.makeMeasureSpec(px(18), MeasureSpec.EXACTLY); val ph = MeasureSpec.makeMeasureSpec(px(68), MeasureSpec.EXACTLY)
                top.measure(pw, ph); bottom.measure(pw, ph)
                setMeasuredDimension(width, side)
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val side = d.measuredWidth
                val x = ((r - l) - px(26) - side) / 2
                d.layout(x, 0, x + side, side)
                // 2 and 4 o'clock on the dial, carried out to the block's right-hand edge
                val cy = side / 2f; val rad = side / 2f
                for ((p, sgn) in listOf(top to -1, bottom to 1)) {
                    val y = (cy + sgn * rad * 0.5f).toInt()
                    p.layout((r - l) - px(20), y - px(34), (r - l) - px(2), y + px(34))
                }
            }
        }
        block.addView(d); block.addView(top); block.addView(bottom)
        return block
    }

    /** Start/Stop above, Lap/Reset below; for a timer, Start/Pause above and +1 min/Reset below. */
    private fun pusherPair(): Pair<Pusher, Pusher> {
        val now = System.currentTimeMillis()
        val top: Pusher; val bottom: Pusher
        if (face == Face.STOPWATCH) {
            val w = Chrono.watch(this)
            top = Pusher(this, if (w.running) "Stop" else "Start") { Chrono.saveWatch(this, Chrono.toggle(Chrono.watch(this), System.currentTimeMillis())); render() }
            bottom = Pusher(this, if (w.running) "Lap" else "Reset") {
                val cur = Chrono.watch(this)
                Chrono.saveWatch(this, if (cur.running) Chrono.lap(cur, System.currentTimeMillis()) else Chrono.Watch()); render()
            }
        } else {
            val first = Chrono.timers(this).sortedWith(compareBy<Chrono.Timer> { !it.ringing }.thenBy { if (it.running) 0 else 1 }.thenBy { it.remaining(now) }).firstOrNull()
            top = Pusher(this, when { first == null -> "Start"; first.ringing -> "Stop"; first.running -> "Pause"; else -> "Start" }) {
                val f = first
                when {
                    f == null -> { Chrono.begin(this, lengthChosen.coerceAtLeast(1000)); }
                    f.ringing -> TimerRing.send(this, TimerRing.STOP)
                    f.running -> Chrono.put(this, Chrono.pause(f, System.currentTimeMillis()))
                    else -> Chrono.put(this, Chrono.start(f, System.currentTimeMillis()))
                }
                render()
            }
            bottom = Pusher(this, when { first == null -> "Reset"; first.ringing || first.running -> "+1 min"; else -> "Reset" }) {
                val f = first ?: return@Pusher
                when {
                    f.ringing -> TimerRing.send(this, TimerRing.MORE)
                    f.running -> Chrono.put(this, Chrono.addMinute(f, System.currentTimeMillis()))
                    else -> Chrono.remove(this, f.id)
                }
                render()
            }
        }
        pushers += top; pushers += bottom
        return top to bottom
    }

    private fun faces() = Segmented(this, listOf("Timer", "Stopwatch"), if (face == Face.TIMER) 0 else 1) { i ->
        face = if (i == 0) Face.TIMER else Face.STOPWATCH; render()
    }

    private fun column(d: ChronoDial): View {
        val s = Screen(this, "Cronografo", "Sveglia · Timers and stopwatch")
        s.add(faces(), px(14))
        s.add(dialBlock(d, 420), px(14))
        details(s)
        return s.root
    }

    private fun twoPanes(d: ChronoDial): View {
        val s = Screen(this, "Cronografo", "Sveglia · Timers and stopwatch")
        s.add(faces(), px(14))
        details(s)
        val lead = FrameLayout(this).apply { setPadding(px(20), px(16), px(8), px(16)) }
        lead.addView(dialBlock(d, 560), FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Kit.GROUND)
            addView(lead, LinearLayout.LayoutParams(0, -1, 1f))
            addView(View(context).apply { setBackgroundColor(Kit.HAIRLINE) }, LinearLayout.LayoutParams(px(1), -1))
            addView(s.root, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    /** Half-folded on a worktop: the dial above the crease, two large pushers below it. */
    private fun worktop(d: ChronoDial): View {
        val (top, bottom) = pusherPair()
        top.big = true; bottom.big = true
        val upper = FrameLayout(this).apply { addView(d, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER).apply { setMargins(px(12), px(12), px(12), px(24)) }) }
        val lower = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(px(40), px(36), px(40), px(36))
            addView(top, LinearLayout.LayoutParams(0, px(96), 1f).apply { marginEnd = px(20) })
            addView(bottom, LinearLayout.LayoutParams(0, px(96), 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Kit.GROUND)
            addView(upper, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(lower, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    // ---- What sits beside the dial --------------------------------------------------------------

    private fun details(s: Screen) { if (face == Face.TIMER) timerDetails(s) else watchDetails(s) }

    private fun timerDetails(s: Screen) {
        val list = Chrono.timers(this)
        val ringing = list.filter { it.ringing }
        if (ringing.isNotEmpty()) {
            s.add(Card(this).apply {
                ringing.forEach { t -> row(Row(this@CronografoActivity, "Time is up", t.label.ifBlank { Chrono.words(t.length) }, endValue("0:00", Kit.ROSSO))) }
            }, px(16))
            s.add(primary("Stop") { TimerRing.send(this, TimerRing.STOP) }, px(10))
            s.add(primary("One more minute", filled = false) { TimerRing.send(this, TimerRing.MORE) }, px(10))
        }
        if (list.isEmpty()) {
            // Nothing set: the drums, a few common lengths, and Start
            s.section("Set a timer")
            val drums = LengthDial(this, lengthChosen) { lengthChosen = it; dial?.let { d -> fill(d, System.currentTimeMillis()) } }
            s.add(drums)
            val presets = listOf(1, 3, 5, 10, 15, 30, 60)
            s.add(Chips(this, presets.map { if (it == 60) "1 h" else "$it min" }, presets.indexOf((lengthChosen / 60_000).toInt()).coerceAtLeast(-1)) { i ->
                lengthChosen = presets[i] * 60_000L; drums.set(lengthChosen); dial?.let { d -> fill(d, System.currentTimeMillis()) }
            }, px(10))
            val label = field("Label (optional)")
            s.add(label, px(10))
            s.add(primary("Start") { if (lengthChosen >= 1000) { Chrono.begin(this, lengthChosen, label.text.toString().trim()); render() } }, px(14))
        } else {
            s.section("Timers")
            val c = Card(this)
            list.sortedWith(compareBy<Chrono.Timer> { !it.ringing }.thenBy { if (it.running) 0 else 1 }.thenBy { it.id }).forEach { t ->
                val line = {
                    val n = System.currentTimeMillis()
                    when {
                        t.ringing -> "Time is up"
                        t.running -> "${Chrono.clock(t.remaining(n))} left · rings at " + java.time.Instant.ofEpochMilli(t.endsAt!!).atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
                        else -> "Paused at ${Chrono.clock(t.remaining(n))} of ${Chrono.words(t.length)}"
                    }
                }
                val row = Row(this, t.label.ifBlank { Chrono.words(t.length) }, line(), endValue(if (t.running) "Pause" else if (t.ringing) "Stop" else "Start", Kit.CHAMPAGNE)) {
                    val n = System.currentTimeMillis()
                    when { t.ringing -> TimerRing.send(this, TimerRing.STOP); t.running -> Chrono.put(this, Chrono.pause(t, n)); else -> Chrono.put(this, Chrono.start(t, n)) }
                    render()
                }
                row.setOnLongClickListener { timerSheet(t); true }
                listLines += row to line
                c.row(row)
            }
            s.add(c)
            s.add(text("Hold a timer for +1 min, reset or remove. Two running: the second shows in the register at 9.", 12.5f, Kit.MUTED).apply { tag = SECONDARY; setPadding(px(4), px(8), 0, 0) }, 0)
            s.add(primary("Add a timer", filled = false) { addSheet() }, px(14))
        }
        notes(s)
    }

    private fun timerSheet(t: Chrono.Timer) {
        sheet = Sheet.show(this, t.label.ifBlank { Chrono.words(t.length) }, onClose = { sheet = null; render() }) { sh ->
            val n = System.currentTimeMillis()
            sh.body.addView(primary("+1 min") { Chrono.put(this, Chrono.addMinute(t, n)); sh.dismiss() })
            sh.body.addView(primary("Reset to ${Chrono.words(t.length)}", filled = false) { Chrono.put(this, Chrono.reset(t)); sh.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            sh.body.addView(primary("Remove", filled = false) { Chrono.remove(this, t.id); sh.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10); bottomMargin = px(16) })
        }
    }

    private fun addSheet() {
        var len = lengthChosen
        lateinit var label: android.widget.EditText
        sheet = Sheet.show(this, "New timer", onClose = { sheet = null; render() }, action = "Start" to { sh ->
            if (len >= 1000) Chrono.begin(this, len, label.text.toString().trim()); sh.dismiss()
        }) { sh ->
            val ld = LengthDial(this, len) { len = it }
            sh.body.addView(ld)
            val presets = listOf(1, 3, 5, 10, 15, 30, 60)
            sh.body.addView(Chips(this, presets.map { if (it == 60) "1 h" else "$it min" }, -1) { i -> len = presets[i] * 60_000L; ld.set(len) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            label = field("Label (optional)")
            sh.body.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10); bottomMargin = px(16) })
        }
    }

    private fun watchDetails(s: Screen) {
        val w = Chrono.watch(this)
        val now = System.currentTimeMillis()
        val scale = Chrono.scale(this)
        s.section("The bezel")
        s.add(Segmented(this, listOf("Tachymeter", "Pulsometer", "Plain"), scale.ordinal) { i -> Chrono.setScale(this, Chrono.Scale.values()[i]); render() })
        // What the bezel reads, in words, once the hand has stopped (or at the last lap)
        val read = if (w.laps.isNotEmpty()) Chrono.lapLengths(w.laps).last() else w.elapsed(now)
        val words = when (scale) {
            Chrono.Scale.TACHYMETER -> Chrono.tachymeter(read)?.let { "$it an hour over a measured mile or kilometre" } ?: "Time a measured mile or kilometre (7 to 60 seconds) and the bezel gives the speed"
            Chrono.Scale.PULSOMETER -> Chrono.pulsometer(read)?.let { "$it beats a minute, from 15 beats" } ?: "Start, count 15 beats, stop: the bezel gives the heart rate"
            Chrono.Scale.PLAIN -> null
        }
        if (words != null) s.add(text(words, 13.5f, Kit.MUTED).apply { setPadding(px(4), px(10), 0, 0) })

        s.section("Laps")
        val c = Card(this)
        if (w.laps.isEmpty()) c.row(Row(this, if (w.running) "Lap 1" else "No laps", if (w.running) "The lower pusher marks a lap" else "Start with the upper pusher, lap with the lower"))
        else {
            val lens = Chrono.lapLengths(w.laps)
            val best = lens.minOrNull()
            if (w.running) {
                val cur = Row(this, "Lap ${w.laps.size + 1}", Chrono.clock(w.elapsed(now) - w.laps.last(), tenths = true))
                listLines += cur to { Chrono.clock(Chrono.watch(this).elapsed(System.currentTimeMillis()) - w.laps.last(), tenths = true) }
                c.row(cur)
            }
            lens.withIndex().reversed().forEach { (i, len) ->
                c.row(Row(this, "Lap ${i + 1}", "${Chrono.clock(len, tenths = true)} · split ${Chrono.clock(w.laps[i], tenths = true)}",
                    if (lens.size > 1 && len == best) endValue("Best", Kit.CHAMPAGNE) else null))
            }
        }
        s.add(c)
        notes(s)
    }

    private fun notes(s: Screen) {
        s.add(doctrineCard(listOf(
            "Membrane" to "One dial. Start a second timer and a register swells out of the face at 9 o'clock while the first moves aside, as a mono-compax becomes a bi-compax.",
            "Concentrato" to "Once running and left alone, the bezel's numerals and the minute track fall back, leaving the hands and the register. A touch brings them back.",
            "Giulietta" to "The pushers sit low in the screen's edge at 2 and 4 o'clock and rise when your hand comes to the glass, receding 3.2 seconds after.",
            "The fold" to "Half-folded on a worktop, the dial stands above the crease and two large pushers lie below it. Folding or unfolding stops a timer that has run out.",
        )), px(28))
    }

    /** Where the dial and pushers landed, for the four-shape check (files/chrono-layout.json). */
    private fun record(root: View) {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root.height <= 0) return
                fun box(v: View?): String? { if (v == null || v.height <= 0 || !v.isShown) return null; val p = IntArray(2); v.getLocationInWindow(p); return "[${p[0]}, ${p[1]}, ${p[0] + v.width}, ${p[1] + v.height}]" }
                val o = IntArray(2).also { root.getLocationInWindow(it) }
                val parts = listOf("dial" to dial, "pusher-top" to pushers.getOrNull(0), "pusher-bottom" to pushers.getOrNull(1))
                    .mapNotNull { (k, v) -> box(v)?.let { "\"$k\": $it" } }.joinToString(", ")
                val json = "{\"face\": \"${face.name}\", \"half\": $halfOpen, \"rotation\": ${display?.rotation ?: 0}, \"origin\": [${o[0]}, ${o[1]}], \"view\": [${root.width}, ${root.height}], \"parts\": {$parts}}"
                runCatching { java.io.File(filesDir, "chrono-layout.json").writeText(json) }
            }
        })
    }
}
