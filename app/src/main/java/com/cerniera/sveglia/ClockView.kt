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
 * The sky and the time. [dawn] runs from 0 (night) to 1 (day): the sky warms from the horizon up.
 * The sun is the real one ([Daylight]): it climbs out of the hinge only when it is really up. On the main screen upright the sun rises up the crease; on the
 * cover it rises from the foot of the screen, where the hinge is.
 *
 * On the main screen turned, the crease runs side to side: the phone half-folded and standing on the
 * bedside table (13 Nov 2026). There the crease is the horizon. The top half is the sky, holding the
 * time, and the sun comes up out of the crease into it. The bottom half lies flat on the table, so it
 * is ground: dark, with nothing to read, only the sun's warmth spreading across it as it rises.
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
    /** The moment whose real sun is drawn, epoch ms; 0 for now. A preview shows the next alarm's sky. */
    var skyAt = 0L
        set(v) { field = v; invalidate() }

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
        val wide = Hinge.unfolded(context)
        // The main screen turned: the crease across the middle is the horizon
        val crease = wide && w > h
        val ground = if (crease) h / 2f else h
        p.shader = LinearGradient(0f, 0f, 0f, ground, top, horizon, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, ground, p)
        p.shader = null

        // The sun: the real one (14 Nov 2026). The alarm brings the light; the sun stands only where it
        // really is. Up, it climbs from the hinge with its true altitude, to just over a third of the way up
        // (over the crease, a little above it), always below the time. Just before sunrise it waits under
        // the horizon and only its glow shows; before first light there is no sun at all.
        if (dawn > 0f) {
            val cx = w / 2f
            // Smaller on a short, wide screen (the cover turned), so the risen sun stays clear of the
            // wake screen's Snooze and Stop at the foot; smaller again over the crease, where it has
            // only the top half to climb in
            val r = minOf(w, h) * (if (crease) 0.06f else if (wide) 0.09f else if (w > h) 0.10f else 0.14f)
            val alt = Daylight.altitude(if (skyAt > 0L) skyAt else System.currentTimeMillis())
            val twilight = Daylight.twilight(alt)
            if (twilight > 0f) {
                // At sunrise its upper edge touches the horizon; below that it is hidden under it
                val start = ground + r
                val end = if (crease) h * 0.37f else h * 0.64f
                val cy = start - (start - end) * Daylight.rise(alt)
                val e = Kit.ease.getInterpolation(dawn)
                val breath = if (breathing) 1f + 0.08f * sin((System.currentTimeMillis() - startedAt) / 900.0).toFloat() else 1f
                val glowR = r * (3.2f + 1.6f * dawn) * breath
                val sunC = blend(0xFFB8141E.toInt(), 0xFFFFF4E0.toInt(), e)
                p.shader = RadialGradient(cx, cy, glowR, withAlpha(sunC, 0.55f * dawn * twilight), withAlpha(sunC, 0f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, glowR, p)
                p.shader = null
                if (Daylight.rise(alt) > -0.2f) {
                    p.color = withAlpha(sunC, (0.25f + 0.75f * e).coerceAtMost(1f))
                    c.drawCircle(cx, cy, r, p)
                }
            }
            if (breathing) postInvalidateDelayed(40)
        }

        // Over the crease: the bottom half is ground, drawn over the sun so it rises from behind the
        // horizon. Dark, warming only a little, with the sun's light spreading across it from the crease.
        if (crease) {
            val e = Kit.ease.getInterpolation(dawn)
            p.color = blend(0xFF0A0E0C.toInt(), horizon, 0.16f * e)
            c.drawRect(0f, ground, w, h, p)
            if (dawn > 0f) {
                val warm = blend(0xFFB8141E.toInt(), 0xFFFFF4E0.toInt(), e)
                p.shader = LinearGradient(0f, ground, 0f, ground + h * 0.32f, withAlpha(warm, 0.30f * dawn), withAlpha(warm, 0f), Shader.TileMode.CLAMP)
                c.drawRect(0f, ground, w, h, p)
                p.shader = null
            }
            // The horizon itself, a hairline of the sky's lowest colour
            p.color = withAlpha(horizon, 0.35f + 0.4f * dawn)
            c.drawRect(0f, ground - context.px(1), w, ground + context.px(1), p)
        }

        if (!showTime) return
        // The time, in a colour that reads on whatever the sky has become
        val lum = luminance(top)
        // Wind-down (14 Nov 2026): at night the bedside clock follows the real dusk. From sunset the
        // time warms from ivory towards ember and dims a little, fully so once the sun is 12° down.
        val dusk = if (dawn == 0f && skyAt == 0L) Daylight.dusk(System.currentTimeMillis()) else 0f
        val ink = if (lum > 0.45f) 0xFF1D1B19.toInt() else blend(0xFFF6F1E6.toInt(), 0xFFE3A05E.toInt(), 0.75f * dusk)
        val drift = ((System.currentTimeMillis() - startedAt) / 60_000L).toInt()
        val dx = context.px(((drift * 7) % 17) - 8).toFloat()
        val dy = context.px(((drift * 5) % 13) - 6).toFloat()
        p.color = withAlpha(ink, (if (dawn == 0f) 0.62f else 0.92f) * dim * (1f - 0.22f * dusk))
        p.typeface = Kit.display(context)
        p.textAlign = Paint.Align.CENTER
        // Over the crease the time keeps to the sky, the top half: smaller and higher
        p.textSize = minOf(w * 0.30f, h * (if (crease) 0.14f else 0.24f))
        val y = h * (if (crease) 0.20f else 0.30f) + dy
        c.drawText(LocalTime.now().format(fmt), w / 2f + dx, y, p)
        if (caption.isNotEmpty()) {
            p.typeface = Kit.sans(context, 450)
            p.textSize = context.px(15).toFloat()
            p.letterSpacing = 0.06f
            p.color = withAlpha(ink, 0.6f * dim * (1f - 0.22f * dusk))
            c.drawText(caption, w / 2f + dx, y + context.px(42), p)
            p.letterSpacing = 0f
        }
    }

    private fun luminance(c: Int): Float {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
