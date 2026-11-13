package com.cerniera.sveglia

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.cerniera.kit.Hinge
import com.cerniera.kit.Kit
import com.cerniera.kit.blend
import com.cerniera.kit.px
import com.cerniera.kit.withAlpha
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.sin

/**
 * The sky and the time. [dawn] runs from 0 (night) to 1 (day): the sky warms from the horizon up
 * and the sun climbs out of the hinge. On the main screen the sun rises on the crease itself; on the
 * cover it rises from the foot of the screen, where the hinge is.
 *
 * At night nothing moves but the minute; nothing redraws between minutes. Over a night the time
 * drifts a few pixels each minute so it never burns into the screen.
 */
class ClockView(ctx: Context) : View(ctx) {
    var dawn = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** A line under the time: the next alarm, or what the alarm is called. */
    var caption = ""
        set(v) { field = v; invalidate() }
    /** Dims the time at night, from 0.15 (a dark room) to 1. */
    var dim = 1f
        set(v) { field = v.coerceIn(0.15f, 1f); invalidate() }
    /** False for a small preview of the sky alone. */
    var showTime = true
    /** While ringing: a slow breath of light around the sun. */
    var breathing = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fmt = DateTimeFormatter.ofPattern("HH:mm")
    private var startedAt = System.currentTimeMillis()

    init { contentDescription = "Clock" }

    private val minuteTick = object : Runnable {
        override fun run() {
            invalidate()
            val now = System.currentTimeMillis()
            postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(minuteTick) }
    override fun onDetachedFromWindow() { removeCallbacks(minuteTick); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = Dawn.topAt(dawn); val horizon = Dawn.horizonAt(dawn)
        p.shader = LinearGradient(0f, 0f, 0f, h, top, horizon, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // The sun: from below the hinge at night to a third of the way up by full day
        if (dawn > 0f) {
            val wide = Hinge.unfolded(context)
            val cx = w / 2f
            // Smaller on a short, wide screen (the cover turned), so the risen sun stays clear of the
            // wake screen's Snooze and Stop at the foot
            val r = minOf(w, h) * (if (wide) 0.09f else if (w > h) 0.10f else 0.14f)
            // It climbs to just over a third of the way up, always below the time
            val cy = h + r * 1.2f - (h * 0.36f + r * 1.2f) * Kit.ease.getInterpolation(dawn)
            val breath = if (breathing) 1f + 0.08f * sin((System.currentTimeMillis() - startedAt) / 900.0).toFloat() else 1f
            val glowR = r * (3.2f + 1.6f * dawn) * breath
            val sunC = blend(0xFFB8141E.toInt(), 0xFFFFF4E0.toInt(), Kit.ease.getInterpolation(dawn))
            p.shader = RadialGradient(cx, cy, glowR, withAlpha(sunC, 0.55f * dawn), withAlpha(sunC, 0f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, glowR, p)
            p.shader = null
            p.color = sunC
            c.drawCircle(cx, cy, r, p)
            if (breathing) postInvalidateDelayed(40)
        }

        if (!showTime) return
        // The time, in a colour that reads on whatever the sky has become
        val lum = luminance(top)
        val ink = if (lum > 0.45f) 0xFF1D1B19.toInt() else 0xFFF6F1E6.toInt()
        val drift = ((System.currentTimeMillis() - startedAt) / 60_000L).toInt()
        val dx = context.px(((drift * 7) % 17) - 8).toFloat()
        val dy = context.px(((drift * 5) % 13) - 6).toFloat()
        p.color = withAlpha(ink, (if (dawn == 0f) 0.62f else 0.92f) * dim)
        p.typeface = Kit.display(context)
        p.textAlign = Paint.Align.CENTER
        p.textSize = minOf(w * 0.30f, h * 0.24f)
        val y = h * 0.30f + dy
        c.drawText(LocalTime.now().format(fmt), w / 2f + dx, y, p)
        if (caption.isNotEmpty()) {
            p.typeface = Kit.sans(context, 450)
            p.textSize = context.px(15).toFloat()
            p.letterSpacing = 0.06f
            p.color = withAlpha(ink, 0.6f * dim)
            c.drawText(caption, w / 2f + dx, y + context.px(42), p)
            p.letterSpacing = 0f
        }
    }

    private fun luminance(c: Int): Float {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
