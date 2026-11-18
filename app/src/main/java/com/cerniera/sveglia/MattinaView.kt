package com.cerniera.sveglia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import com.cerniera.kit.Hinge
import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha

/**
 * Mattina's page, under the time on the wake screen once the alarm is stopped: up to three rows
 * (first event, sky, light) and the morning's line, centred like the time.
 *
 * It keeps to the room the time leaves: below the time's line, above the foot (or above the crease
 * when the main screen is turned and the bottom half is ground). Four shapes, nothing cut off:
 * stacked when there is height; side by side when the glass is short and wide (the cover turned);
 * and only then smaller, never below 72%.
 *
 * Not touchable: a tap falls through to the clock, which closes the wake screen.
 */
class MattinaView(ctx: Context) : View(ctx) {
    var page: Mattina.Page? = null
        set(v) { field = v; contentDescription = v?.let { p -> (p.rows.map { "${it.main}. ${it.sub ?: ""}" } + p.line).joinToString(" ") }; invalidate() }
    /** The time's ink (from [ClockView]), so the page reads on the same sky. */
    var ink = 0xFFF6F1E6.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }
    var bottomInset = 0
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.sans(ctx, 500); letterSpacing = 0.16f; textAlign = Paint.Align.CENTER }
    private val main = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.sans(ctx, 500); textAlign = Paint.Align.CENTER }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.sans(ctx, 450); textAlign = Paint.Align.CENTER }
    private val line = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.display(ctx); textAlign = Paint.Align.CENTER }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    private fun sizes(s: Float) {
        label.textSize = 11 * dp * s; main.textSize = 19 * dp * s; sub.textSize = 14 * dp * s; line.textSize = 24 * dp * s
    }

    private fun rowH(s: Float) = (11 + 8 + 19 + 6 + 14) * dp * s
    private val gap get() = 18 * dp

    /** Words wrapped to [width], at most [max] lines; the last ends in an ellipsis if it must. */
    private fun wrap(text: String, p: TextPaint, width: Float, max: Int): List<String> {
        val out = mutableListOf<String>(); var cur = ""
        for (w in text.split(' ')) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(t) <= width) cur = t
            else { if (cur.isNotEmpty()) out += cur; cur = w }
        }
        if (cur.isNotEmpty()) out += cur
        if (out.size <= max) return out
        val kept = out.take(max).toMutableList()
        var last = kept.last()
        while (last.isNotEmpty() && p.measureText("$last…") > width) last = last.dropLast(1)
        kept[max - 1] = "$last…"
        return kept
    }

    /** Where the page may draw: below the time's caption, above the foot or the crease. */
    private fun room(): Pair<Float, Float> {
        val w = width.toFloat(); val h = height.toFloat()
        val crease = Hinge.unfolded(context) && w > h
        // The time's baseline as ClockView draws it, plus its caption, plus air
        val timeY = h * (if (crease) 0.20f else 0.30f)
        val top = timeY + 72 * dp
        val bottom = if (crease) h / 2f - 20 * dp else h - bottomInset - 28 * dp
        return top to bottom
    }

    override fun onDraw(c: Canvas) {
        val p = page ?: return
        val w = width.toFloat()
        val (top, bottom) = room()
        val avail = bottom - top
        if (avail <= 0f) return
        val colW = minOf(w - 48 * dp, 480 * dp)
        val n = p.rows.size

        // Stacked if it fits; side by side on short, wide glass; then smaller, to 72%
        var s = 1f
        sizes(s)
        fun lineH(width: Float) = wrap(p.line, line, width, 2).size * line.textSize * 1.25f
        fun stacked() = n * rowH(s) + (n - 1).coerceAtLeast(0) * gap + gap * 1.4f + lineH(colW)
        fun across() = rowH(s) + gap * 1.4f + lineH(colW)
        var sideBySide = false
        if (stacked() > avail) {
            if (w >= 560 * dp && n > 1 && across() <= avail) sideBySide = true
            else {
                while (s > 0.72f && stacked() > avail) { s -= 0.02f; sizes(s) }
                if (stacked() > avail && w >= 560 * dp && n > 1) { s = 1f; sizes(s); sideBySide = true
                    while (s > 0.72f && across() > avail) { s -= 0.02f; sizes(s) } }
            }
        }

        val cx = w / 2f
        label.color = withAlpha(ink, 0.55f); main.color = withAlpha(ink, 0.95f); sub.color = withAlpha(ink, 0.62f)
        line.color = withAlpha(ink, 0.92f); rule.color = withAlpha(ink, 0.18f)

        fun drawRow(r: Mattina.Row, x: Float, y0: Float, width: Float) {
            var y = y0 + label.textSize
            c.drawText(r.label, x, y, label)
            y += 8 * dp * s + main.textSize
            c.drawText(fit(r.main, main, width), x, y, main)
            r.sub?.let { y += 6 * dp * s + sub.textSize; c.drawText(fit(it, sub, width), x, y, sub) }
        }

        var y = top
        if (sideBySide) {
            val total = minOf(w - 48 * dp, 980 * dp)
            val each = total / n
            p.rows.forEachIndexed { i, r -> drawRow(r, cx - total / 2 + each * (i + 0.5f), y, each - 16 * dp) }
            y += rowH(s)
        } else {
            p.rows.forEachIndexed { i, r ->
                drawRow(r, cx, y, colW)
                y += rowH(s) + if (i < n - 1) gap else 0f
            }
        }
        // A hairline, then the morning's line
        y += gap * 0.7f
        if (n > 0) c.drawRect(cx - 18 * dp, y - 0.5f * dp, cx + 18 * dp, y + 0.5f * dp, rule)
        y += gap * 0.7f
        wrap(p.line, line, colW, 2).forEach { t -> y += line.textSize; c.drawText(t, cx, y, line); y += line.textSize * 0.25f }
    }

    private fun fit(s: String, p: TextPaint, width: Float): String {
        if (p.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
