package com.cerniera.sveglia

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.withAlpha
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The chronograph's face. One dial (Membrane): a sweeping seconds hand, a 30-minute register at
 * 3 o'clock, and the bezel's scale (tachymeter or pulsometer) for the stopwatch. When a second
 * timer runs, a second register swells out of the face at 9 o'clock and the first shifts to make
 * room, the way a mono-compax becomes a bi-compax. A timer also draws what is left as an arc on
 * the bezel, readable across a kitchen.
 *
 * Concentrato: once something has run for a moment untouched, the scale's numerals and the minute
 * track fall back, leaving the hands and the register; they return at a touch or when it stops.
 */
class ChronoDial(ctx: Context) : View(ctx) {
    /** What the hands show, in milliseconds. */
    var main = 0L
    /** For a timer: its whole length, for the bezel arc. 0 for the stopwatch. */
    var length = 0L
    /** A second timer's time left, drawn in the register at 9; null for none. */
    var second: Long? = null
    var running = false
    var ringing = false
    var scale = Chrono.Scale.TACHYMETER

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var quietFrom = 0L
    private var calm = 0f
    private var bi = 0f
    private var biAnim: ValueAnimator? = null

    fun touched() { quietFrom = SystemClock.uptimeMillis() + Kit.RECEDE_MS; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val sw = MeasureSpec.getSize(w); val sh = MeasureSpec.getSize(h)
        val side = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) sw else min(sw, sh)
        setMeasuredDimension(side, side)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN) touched(); return false }

    private fun ease(target: Float) {
        if (biAnim?.isRunning == true || abs(bi - target) < 0.01f) return
        biAnim = ValueAnimator.ofFloat(bi, target).apply {
            duration = Kit.GLIDE.toLong(); interpolator = Kit.ease
            addUpdateListener { bi = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        // Concentrato: calm rises once running untouched past the recede time
        val wantCalm = if (running && now > quietFrom) 1f else 0f
        calm += (wantCalm - calm) * 0.08f
        ease(if (second != null) 1f else 0f)

        val cx = width / 2f; val cy = height / 2f
        val R = min(width, height) / 2f - context.px(6)
        val face = R * 0.80f
        // The case and the bezel
        p.style = Paint.Style.FILL; p.color = Kit.RAISED; c.drawCircle(cx, cy, R, p)
        p.color = Kit.SURFACE; c.drawCircle(cx, cy, face, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.px(1).toFloat(); p.color = Kit.HAIRLINE
        c.drawCircle(cx, cy, R, p); c.drawCircle(cx, cy, face, p)

        // The pushers' crowns in the case at 2 and 4 o'clock (the real ones sit at the screen's edge)
        p.style = Paint.Style.FILL; p.color = withAlpha(Kit.CHAMPAGNE, 0.55f)
        for (deg in listOf(-30f, 30f)) {
            val a = Math.toRadians(deg.toDouble())
            val x = cx + (R + context.px(1)) * cos(a).toFloat(); val y = cy + (R + context.px(1)) * sin(a).toFloat()
            c.save(); c.rotate(deg, x, y)
            c.drawRoundRect(RectF(x - context.px(2), y - context.px(7), x + context.px(5), y + context.px(7)), context.px(2).toFloat(), context.px(2).toFloat(), p)
            c.restore()
        }

        // The minute track: 60 marks, the fives longer
        val track = 1f - 0.65f * calm
        for (i in 0 until 60) {
            val a = (i / 60f) * 2 * PI - PI / 2
            val long = i % 5 == 0
            val r0 = face - context.px(if (long) 12 else 6)
            p.strokeWidth = context.px(if (long) 2 else 1).toFloat()
            p.color = withAlpha(Kit.INK, (if (long) 0.75f else 0.35f) * track)
            c.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + face * cos(a).toFloat(), cy + face * sin(a).toFloat(), p)
        }

        // The bezel: a timer's time left as an arc; the stopwatch's scale as numerals
        val ring = (R + face) / 2f
        if (length > 0) {
            val left = (main.toFloat() / length).coerceIn(0f, 1f)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = (R - face) * 0.34f
            p.color = withAlpha(Kit.INK, 0.08f); c.drawCircle(cx, cy, ring, p)
            p.color = if (ringing) Kit.ROSSO else Kit.CHAMPAGNE
            if (left > 0f) c.drawArc(RectF(cx - ring, cy - ring, cx + ring, cy + ring), -90f, 360f * left, false, p)
            p.strokeCap = Paint.Cap.BUTT
        } else if (scale != Chrono.Scale.PLAIN) {
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = Kit.sans(context, 500)
            p.textSize = (R - face) * 0.36f
            p.color = withAlpha(Kit.CHAMPAGNE, 0.9f - 0.55f * calm)
            val marks = if (scale == Chrono.Scale.TACHYMETER) listOf(500, 300, 200, 150, 120, 100, 90, 80, 70, 60).map { it to 3600f / it }
                else listOf(200, 150, 120, 100, 90, 80, 70, 60, 50, 40, 30, 20).map { it to 900f / it }
            marks.forEach { (v, sec) ->
                if (sec > 60f) return@forEach
                val a = (sec / 60f) * 2 * PI - PI / 2
                c.save(); c.rotate((sec / 60f) * 360f, cx, cy)
                c.drawText("$v", cx, cy - ring + p.textSize * 0.36f, p)
                c.restore()
                p.strokeWidth = context.px(1).toFloat()
                c.drawLine(cx + (face + 1) * cos(a).toFloat(), cy + (face + 1) * sin(a).toFloat(), cx + (face + context.px(4)) * cos(a).toFloat(), cy + (face + context.px(4)) * sin(a).toFloat(), p)
            }
            p.textSize = (R - face) * 0.24f; p.color = withAlpha(Kit.MUTED, 1f - 0.7f * calm)
            c.drawText(if (scale == Chrono.Scale.TACHYMETER) "TACHYMETER" else "PULSATIONS · 15", cx, cy + face * 0.62f, p)
        }

        // The registers: at 3 always; at 9 when a second timer runs (swelling out as the first moves aside)
        val reg = face * 0.25f
        val regX = face * 0.48f
        fun register(x: Float, y: Float, rad: Float, value: Long, of: Long, colour: Int) {
            p.style = Paint.Style.FILL; p.color = Kit.GROUND; c.drawCircle(x, y, rad, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = context.px(1).toFloat(); p.color = Kit.HAIRLINE; c.drawCircle(x, y, rad, p)
            for (i in 0 until 6) {
                val a = (i / 6f) * 2 * PI - PI / 2
                p.color = withAlpha(Kit.INK, 0.5f)
                c.drawLine(x + rad * 0.8f * cos(a).toFloat(), y + rad * 0.8f * sin(a).toFloat(), x + rad * cos(a).toFloat(), y + rad * sin(a).toFloat(), p)
            }
            val turn = ((value.toFloat() / of) % 1f) * 2 * PI - PI / 2
            p.strokeWidth = context.px(2).toFloat(); p.strokeCap = Paint.Cap.ROUND; p.color = colour
            c.drawLine(x, y, x + rad * 0.82f * cos(turn).toFloat(), y + rad * 0.82f * sin(turn).toFloat(), p)
            p.strokeCap = Paint.Cap.BUTT
        }
        val minutes = main / 60_000f
        register(cx + regX * (1f - 0.1f * bi), cy, reg, (minutes * 60_000).toLong(), 30 * 60_000L, Kit.CHAMPAGNE)
        if (bi > 0.01f) second?.let { register(cx - regX, cy, reg * bi, it, 60 * 60_000L, Kit.INK) }

        // The readout under the centre
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = Kit.display(context)
        p.textSize = face * 0.26f; p.color = if (ringing) Kit.ROSSO else Kit.INK
        c.drawText(Chrono.clock(main, tenths = length == 0L), cx, cy + face * 0.42f, p)

        // The seconds hand, sweeping, with a champagne tail
        val secs = (main % 60_000) / 60_000f
        val a = secs * 2 * PI - PI / 2
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = context.px(2).toFloat(); p.color = if (ringing) Kit.ROSSO else Kit.CHAMPAGNE
        c.drawLine(cx - face * 0.16f * cos(a).toFloat(), cy - face * 0.16f * sin(a).toFloat(), cx + face * 0.92f * cos(a).toFloat(), cy + face * 0.92f * sin(a).toFloat(), p)
        p.strokeCap = Paint.Cap.BUTT
        p.color = Kit.CHAMPAGNE; c.drawCircle(cx, cy, context.px(5).toFloat(), p)
        p.color = Kit.GROUND; c.drawCircle(cx, cy, context.px(2).toFloat(), p)

        if (running || abs(calm - wantCalm) > 0.01f || ringing) postInvalidateDelayed(33)
    }
}

/**
 * A pusher in the screen's edge (Giulietta): low and quiet at rest, rising when the hand comes to
 * the screen, receding 3.2 s after. [label] says what it does now.
 */
@SuppressLint("ViewConstructor")
class Pusher(ctx: Context, var label: String, private val onPush: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lit = 0.35f
    var big = false

    init {
        isClickable = true
        contentDescription = label
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); onPush() }
    }

    fun wake() {
        animate().cancel()
        ValueAnimator.ofFloat(lit, 1f).apply { duration = Kit.QUICK.toLong(); addUpdateListener { lit = it.animatedValue as Float; invalidate() }; start() }
        removeCallbacks(rest); postDelayed(rest, Kit.RECEDE_MS)
    }

    private val rest = Runnable {
        ValueAnimator.ofFloat(lit, 0.35f).apply { duration = Kit.SETTLE.toLong(); addUpdateListener { lit = it.animatedValue as Float; invalidate() }; start() }
    }

    fun set(l: String) { label = l; contentDescription = l; invalidate() }

    override fun onDraw(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val rad = min(width, height) / 2f
        p.style = Paint.Style.FILL; p.color = withAlpha(Kit.CHAMPAGNE, if (big) 0.9f else 0.18f + 0.72f * lit)
        c.drawRoundRect(r, rad, rad, p)
        if (lit > 0.6f || big) {
            p.color = Kit.GROUND; p.textAlign = Paint.Align.CENTER; p.typeface = Kit.sans(context, 600)
            p.textSize = if (big) context.px(18).toFloat() else context.px(11).toFloat()
            p.alpha = (255 * if (big) 1f else ((lit - 0.6f) / 0.4f).coerceIn(0f, 1f)).roundToInt()
            if (big || width > height) c.drawText(label, r.centerX(), r.centerY() + p.textSize * 0.35f, p)
            else { c.save(); c.rotate(90f, r.centerX(), r.centerY()); c.drawText(label, r.centerX(), r.centerY() + p.textSize * 0.35f, p); c.restore() }
        }
    }
}

/**
 * Setting a timer's length by turning three drums (hours, minutes, seconds), as Sveglia's alarm
 * drums turn: the numbers roll with the finger and settle with a tick for each step.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class LengthDial(ctx: Context, ms: Long, private val changed: (Long) -> Unit) : View(ctx) {
    private val sizes = intArrayOf(24, 60, 60)
    private val pos = floatArrayOf((ms / 3_600_000).toFloat(), (ms / 60_000 % 60).toFloat(), (ms / 1000 % 60).toFloat())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var active = -1
    private var lastY = 0f

    init { background = rounded(Kit.RAISED, ctx.px(24).toFloat(), Kit.HAIRLINE, ctx.px(1)); describe() }

    private fun value(i: Int) = ((pos[i].roundToInt() % sizes[i]) + sizes[i]) % sizes[i]
    val length get() = value(0) * 3_600_000L + value(1) * 60_000L + value(2) * 1000L
    private fun step() = context.px(56).toFloat()

    fun set(ms: Long) { pos[0] = (ms / 3_600_000).toFloat(); pos[1] = (ms / 60_000 % 60).toFloat(); pos[2] = (ms / 1000 % 60).toFloat(); describe(); invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(176))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { active = (e.x / (width / 3f)).toInt().coerceIn(0, 2); lastY = e.y; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                val before = value(active)
                pos[active] -= (e.y - lastY) / step(); lastY = e.y
                if (value(active) != before) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active >= 0) {
                val i = active; val from = pos[i]; val to = from.roundToInt().toFloat()
                ValueAnimator.ofFloat(from, to).apply {
                    duration = Kit.GLIDE.toLong(); interpolator = Kit.ease
                    addUpdateListener { pos[i] = it.animatedValue as Float; invalidate() }
                    addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { describe(); changed(length) } })
                    start()
                }
                active = -1
            }
        }
        return true
    }

    private fun describe() { contentDescription = "Timer length " + Chrono.words(length) }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val cols = floatArrayOf(width / 6f, width / 2f, width * 5 / 6f)
        p.typeface = Kit.display(context)
        p.color = withAlpha(Kit.CHAMPAGNE, 0.5f); p.strokeWidth = context.px(1).toFloat()
        val half = step() / 2f
        c.drawLine(context.px(24).toFloat(), cy - half, width - context.px(24).toFloat(), cy - half, p)
        c.drawLine(context.px(24).toFloat(), cy + half, width - context.px(24).toFloat(), cy + half, p)
        p.typeface = Kit.sans(context, 500); p.textSize = context.px(10).toFloat(); p.color = Kit.MUTED
        listOf("H", "MIN", "S").forEachIndexed { i, s -> c.drawText(s, cols[i], cy + half + context.px(14), p) }
        p.typeface = Kit.display(context)
        for (i in 0..2) {
            val base = floor(pos[i])
            for (k in -2..3) {
                val v = base + k
                val off = (v - pos[i]) * step()
                if (abs(off) > height / 2f + step()) continue
                val near = 1f - (abs(off) / (step() * 2.2f)).coerceIn(0f, 1f)
                p.color = withAlpha(Kit.INK, 0.12f + 0.88f * near * near)
                p.textSize = context.px(30 + 16 * near).toFloat()
                val n = ((v.toInt() % sizes[i]) + sizes[i]) % sizes[i]
                c.drawText("%02d".format(n), cols[i], cy + off + p.textSize * 0.33f, p)
            }
        }
    }
}
