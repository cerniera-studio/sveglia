package com.cerniera.sveglia

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.withAlpha
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Setting a time by turning two drums: hours on the left, minutes on the right. Drag either one up
 * or down and its numbers roll with the finger, the neighbours fading as they leave the window;
 * let go and it settles on the nearest number on the family's curve, with a tick for each step.
 * Minutes move in ones; a quick flick carries on a little, the way a real drum would.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TimeDial(ctx: Context, hour: Int, minute: Int, private val changed: (Int, Int) -> Unit) : View(ctx) {
    private val drums = arrayOf(Drum(24, hour.toFloat()), Drum(60, minute.toFloat()))
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var active = -1
    private var lastY = 0f
    private var lastT = 0L
    private var velocity = 0f

    val hour get() = drums[0].value()
    val minute get() = drums[1].value()

    private inner class Drum(val n: Int, var pos: Float) {
        fun value() = ((pos.roundToInt() % n) + n) % n
    }

    init {
        background = rounded(Kit.RAISED, ctx.px(24).toFloat(), Kit.HAIRLINE, ctx.px(1))
        contentDescription = "Alarm time"
        updateDescription()
    }

    private fun step() = context.px(64).toFloat()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(200))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = if (e.x < width / 2f) 0 else 1
                lastY = e.y; lastT = e.eventTime; velocity = 0f
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val d = drums[active]
                val before = d.value()
                val dy = e.y - lastY
                d.pos -= dy / step()
                val dt = (e.eventTime - lastT).coerceAtLeast(1)
                velocity = velocity * 0.6f + (-dy / step() / dt * 1000f) * 0.4f
                lastY = e.y; lastT = e.eventTime
                if (d.value() != before) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> settle(active)
        }
        return true
    }

    /** Carries a flick on a little, then lands exactly on a number. */
    private fun settle(i: Int) {
        if (i < 0) return
        val d = drums[i]
        val from = d.pos
        val carry = (velocity * 0.18f).coerceIn(-8f, 8f)
        val to = (from + if (abs(velocity) > 3f) carry else 0f).roundToInt().toFloat()
        var lastV = d.value()
        ValueAnimator.ofFloat(from, to).apply {
            duration = (Kit.GLIDE + abs(to - from) * 30).toLong().coerceAtMost(700)
            interpolator = Kit.ease
            addUpdateListener {
                d.pos = it.animatedValue as Float
                if (d.value() != lastV) { lastV = d.value(); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                invalidate()
            }
            doOnEnd { updateDescription(); changed(hour, minute) }
            start()
        }
        active = -1
    }

    private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(a: android.animation.Animator) = f()
    })

    private fun updateDescription() { contentDescription = "Alarm time %02d:%02d".format(hour, minute) }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val cols = floatArrayOf(width * 0.30f, width * 0.70f)
        p.typeface = Kit.display(context)
        // The window: a champagne hairline above and below the chosen numbers
        p.color = withAlpha(Kit.CHAMPAGNE, 0.5f); p.strokeWidth = context.px(1).toFloat()
        val half = step() / 2f
        c.drawLine(context.px(24).toFloat(), cy - half, width - context.px(24).toFloat(), cy - half, p)
        c.drawLine(context.px(24).toFloat(), cy + half, width - context.px(24).toFloat(), cy + half, p)
        p.textSize = context.px(54).toFloat()
        p.color = Kit.CHAMPAGNE
        c.drawText(":", width / 2f, cy + p.textSize * 0.33f, p)
        drums.forEachIndexed { i, d ->
            val base = floor(d.pos)
            for (k in -2..3) {
                val v = base + k
                val off = (v - d.pos) * step()
                if (abs(off) > height / 2f + step()) continue
                val near = 1f - (abs(off) / (step() * 2.2f)).coerceIn(0f, 1f)
                p.color = withAlpha(Kit.INK, 0.12f + 0.88f * near * near)
                p.textSize = context.px(36 + 20 * near).toFloat()
                val n = ((v.toInt() % d.n) + d.n) % d.n
                c.drawText("%02d".format(n), cols[i], cy + off + p.textSize * 0.33f, p)
            }
        }
    }
}
