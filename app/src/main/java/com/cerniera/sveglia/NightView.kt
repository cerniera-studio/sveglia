package com.cerniera.sveglia

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.view.View
import com.cerniera.kit.Hinge
import com.cerniera.kit.Kit
import com.cerniera.kit.blend
import com.cerniera.kit.px
import com.cerniera.kit.withAlpha
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.cerniera.kit.Sky as Heavens

/**
 * The bedside clock and screen saver (14 Nov 2026). Every element has a cause, and motion is earned:
 * like Solco's Marea, anything that moves all the time moves only while the phone is plugged in.
 *
 * - **Quiet:** the time alone, as before.
 * - **Sky:** the real sky outside, its colours following the sun's true altitude (day, golden hour,
 *   twilight, night), with soft bands of light drifting from the side the sun is on, or set on.
 * - **Tide:** a horizon of light at the battery's level, breathing while it charges, still when full.
 * - **The night counting down** (a switch): a hairline arc filling from when the clock was put down
 *   towards the alarm; in the half hour before the dawn begins, the foot of the screen warms, so the
 *   night hands straight over to the dawn.
 * - **The moon** (a switch): tonight's true phase, small and low, only while the sun is down.
 * - **The time glides** against burn-in, continuously and too slowly to see, instead of stepping.
 */
class NightView(ctx: Context) : View(ctx) {
    var caption = ""
        set(v) { field = v; invalidate() }
    /** Dims everything for a dark room, 0.15 to 1; never below the chosen floor. */
    var dim = 1f
        set(v) { field = max(v, Night.floorDim(context)).coerceIn(0.15f, 1f); invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fmt = DateTimeFormatter.ofPattern("HH:mm")
    private val born = System.currentTimeMillis()
    private val style = Night.style(context)
    private val countdown = Night.countdown(context)
    private val moonOn = Night.moon(context)
    private var level = 1f
    private var charging = false
    private var full = false

    init { contentDescription = "Clock" }

    private val battery = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = read(i)
    }

    private fun read(i: Intent?) {
        i ?: return
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l >= 0 && s > 0) level = l / s.toFloat()
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        full = st == BatteryManager.BATTERY_STATUS_FULL || level >= 0.999f
        invalidate()
    }

    /** Whether this view animates continuously: only with something to move, and only on charge. */
    private fun moving() = charging && (style == Night.Style.SKY || (style == Night.Style.TIDE && !full))

    private val slow = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 4000) }   // the glide and the minute
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        read(if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(battery, f, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(battery, f))
        post(slow)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(slow)
        runCatching { context.unregisterReceiver(battery) }
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val t = (now - born) / 1000f
        val crease = Hinge.unfolded(context) && w > h
        val alt = Daylight.altitude(now)
        val sun = Heavens.sun(Daylight.place, Instant.ofEpochMilli(now))

        // The ground: Quiet and Tide on the night's own dark; Sky on the real sky
        when (style) {
            Night.Style.SKY -> sky(c, w, h, alt, sun.azimuth, t)
            else -> { p.shader = null; p.color = 0xFF0B0F0D.toInt(); c.drawRect(0f, 0f, w, h, p) }
        }
        if (style == Night.Style.TIDE) tide(c, w, h, t)
        if (countdown) dawnNear(c, w, h, now)
        if (moonOn && alt < Heavens.HORIZON) moon(c, w, h, now)
        time(c, w, h, crease, now)
        if (countdown) arc(c, w, h, crease, now)
        if (moving()) postInvalidateDelayed(42)
    }

    // ---- Sky: the real sky's colours by the sun's altitude, light drifting from the sun's side ----

    private val skyAlt = floatArrayOf(-18f, -12f, -6f, -0.8f, 6f, 20f)
    private val skyTop = intArrayOf(0x07090E, 0x0A0E1C, 0x141C36, 0x22345A, 0x2E4A6E, 0x35587E)
    private val skyLow = intArrayOf(0x0C0E14, 0x1A1A30, 0x3A2A40, 0x7A3A24, 0xB0784A, 0x8FA6BC)

    private fun skyAt(cols: IntArray, alt: Float): Int {
        if (alt <= skyAlt.first()) return cols.first() or 0xFF000000.toInt()
        if (alt >= skyAlt.last()) return cols.last() or 0xFF000000.toInt()
        var i = 0
        while (alt > skyAlt[i + 1]) i++
        val k = (alt - skyAlt[i]) / (skyAlt[i + 1] - skyAlt[i])
        return blend(cols[i] or 0xFF000000.toInt(), cols[i + 1] or 0xFF000000.toInt(), k)
    }

    private fun sky(c: Canvas, w: Float, h: Float, alt: Double, az: Double, t: Float) {
        val a = alt.toFloat()
        // The bedside never glares: the sky is shown at a third of its brightness, then dimmed to the room
        val top = blend(0xFF050706.toInt(), skyAt(skyTop, a), 0.55f * dim)
        val low = blend(0xFF050706.toInt(), skyAt(skyLow, a), 0.55f * dim)
        p.shader = LinearGradient(0f, 0f, 0f, h, top, low, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // How much light is in the sky: most at twilight and by day, a trace at deep night
        val light = ((a + 18f) / 18f).coerceIn(0.08f, 1f)
        // The side the sun is on (or set on): east to the left, west to the right
        val side = sin(Math.toRadians(az)).toFloat().let { if (abs(it) < 0.2f) 0.2f * if (it < 0) -1 else 1 else it }
        val lean = -side * 0.6f   // bands fall away from the sun
        val drift = if (charging) t else 0f
        for (k in 0 until 3) {
            val phase = drift / (70f + 23f * k) * 2f * PI.toFloat() + k * 2.1f
            val cx = w * (0.5f - side * 0.35f) + w * 0.18f * sin(phase)
            val bw = w * (0.22f + 0.06f * k)
            val col = blend(skyAt(skyLow, a), 0xFFF6F1E6.toInt(), 0.25f)
            val alpha = (0.05f + 0.03f * sin(phase * 0.7f + k)) * light * dim
            c.save()
            c.rotate(lean * 35f, cx, h / 2)
            p.shader = LinearGradient(cx - bw, 0f, cx + bw, 0f, intArrayOf(withAlpha(col, 0f), withAlpha(col, alpha), withAlpha(col, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(cx - bw, -h, cx + bw, h * 2, p)
            c.restore()
        }
        p.shader = null
    }

    // ---- Tide: a horizon at the battery's level, breathing on charge ----

    private fun tide(c: Canvas, w: Float, h: Float, t: Float) {
        // Full a sixth of the way down, empty near the floor, as in Solco's Marea
        val y = h / 6f + (1f - level) * (h * 0.88f - h / 6f)
        val breath = if (charging && !full) sin(t / 4.2f * 2f * PI.toFloat()) else 0f
        val yy = y + context.px(3) * breath
        val sea = 0xFF6F8E8A.toInt()
        p.shader = LinearGradient(0f, yy, 0f, h, withAlpha(sea, (0.10f + 0.03f * breath) * dim), withAlpha(sea, 0.02f * dim), Shader.TileMode.CLAMP)
        c.drawRect(0f, yy, w, h, p)
        p.shader = null
        p.color = withAlpha(0xFFF6F1E6.toInt(), (0.22f + 0.08f * breath) * dim)
        c.drawRect(0f, yy - context.px(1) / 2f, w, yy + context.px(1) / 2f, p)
    }

    // ---- The night counting down ----

    /** When this night's count began: when the clock was first shown for this alarm. */
    private fun countStart(ring: Long, now: Long): Long {
        val prefs = context.getSharedPreferences("night", Context.MODE_PRIVATE)
        val key = "start_$ring"
        val s = prefs.getLong(key, 0L)
        if (s in 1 until ring) return s
        prefs.edit().clear().putLong(key, now).apply()
        return now
    }

    private fun arc(c: Canvas, w: Float, h: Float, crease: Boolean, now: Long) {
        val n = Alarms.next(context) ?: return
        val ring = n.ring.toInstant().toEpochMilli()
        if (ring - now > 16 * 3_600_000L) return
        val start = countStart(ring, now)
        val f = ((now - start).toFloat() / (ring - start).coerceAtLeast(1)).coerceIn(0f, 1f)
        val cy = if (crease) h * 0.46f else h * 0.62f
        val r = min(w * 0.36f, if (crease) h * 0.2f else h * 0.22f)
        val box = RectF(w / 2 - r, cy - r, w / 2 + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = context.px(1).toFloat()
        p.color = withAlpha(0xFFF6F1E6.toInt(), 0.10f * dim)
        c.drawArc(box, 180f, 180f, false, p)
        p.color = withAlpha(Kit.CHAMPAGNE, 0.55f * dim)
        p.strokeWidth = (context.px(1) * 1.4f).coerceAtLeast(1f)
        c.drawArc(box, 180f, 180f * f, false, p)
        p.style = Paint.Style.FILL
        val ang = Math.toRadians(180.0 + 180.0 * f)
        c.drawCircle(w / 2 + r * cos(ang).toFloat(), cy + r * sin(ang).toFloat(), context.px(1) * 2.5f, p)
    }

    /** In the half hour before the dawn begins, the foot of the screen starts to warm. */
    private fun dawnNear(c: Canvas, w: Float, h: Float, now: Long) {
        val n = Alarms.next(context) ?: return
        val a = Alarms.get(context, n.id) ?: return
        val dawnAt = n.ring.toInstant().toEpochMilli() - a.dawn * 60_000L
        val k = (1f - (dawnAt - now) / (30 * 60_000f)).coerceIn(0f, 1f)
        if (k <= 0f) return
        val warm = Dawn.horizonAt(0.12f * k)
        p.shader = LinearGradient(0f, h, 0f, h * (1f - 0.35f * k), withAlpha(warm, 0.5f * k * dim), withAlpha(warm, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.5f, w, h, p)
        p.shader = null
    }

    // ---- The moon ----

    private fun moon(c: Canvas, w: Float, h: Float, now: Long) {
        val ph = Heavens.moon(Instant.ofEpochMilli(now)).fraction.toFloat()
        val r = min(w, h) * 0.03f
        val cx = w * 0.84f; val cy = h * 0.88f
        val ink = 0xFFE9E3D6.toInt()
        p.color = withAlpha(ink, 0.07f * dim); c.drawCircle(cx, cy, r, p)
        val circle = RectF(cx - r, cy - r, cx + r, cy + r)
        val rx = max(.01f, r * abs(cos(2 * PI * ph)).toFloat())
        val ell = RectF(cx - rx, cy - r, cx + rx, cy + r)
        val path = Path()
        if (ph < .5f) { path.arcTo(circle, -90f, 180f, true); if (ph < .25f) path.arcTo(ell, 90f, -180f) else path.arcTo(ell, 90f, 180f) }
        else { path.arcTo(circle, 90f, 180f, true); if (ph > .75f) path.arcTo(ell, 270f, -180f) else path.arcTo(ell, -90f, 180f) }
        path.close()
        p.color = withAlpha(ink, 0.5f * dim); c.drawPath(path, p)
        // A faint halo, as a bright moon has
        p.shader = RadialGradient(cx, cy, r * 4f, withAlpha(ink, 0.05f * dim * (1 - abs(1 - 2 * ph))), withAlpha(ink, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 4f, p); p.shader = null
    }

    // ---- The time, gliding ----

    private fun time(c: Canvas, w: Float, h: Float, crease: Boolean, now: Long) {
        val dusk = Daylight.dusk(now)
        val ink = blend(0xFFF6F1E6.toInt(), 0xFFE3A05E.toInt(), 0.75f * dusk)
        // Burn-in: a slow continuous glide, a few pixels over many minutes, never a step
        val m = (now - born) / 60_000f
        val dx = context.px(9) * sin(m / 11f * 2f * PI.toFloat())
        val dy = context.px(7) * sin(m / 7.3f * 2f * PI.toFloat() + 1f)
        p.color = withAlpha(ink, 0.62f * dim * (1f - 0.22f * dusk))
        p.typeface = Kit.display(context)
        p.textAlign = Paint.Align.CENTER
        p.textSize = min(w * 0.30f, h * (if (crease) 0.14f else 0.24f))
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
}

/** The screen saver's choices. */
object Night {
    enum class Style { QUIET, SKY, TIDE }

    private fun prefs(c: Context) = c.getSharedPreferences("sveglia", Context.MODE_PRIVATE)
    fun style(c: Context) = runCatching { Style.valueOf(prefs(c).getString("night_style", "QUIET")!!) }.getOrDefault(Style.QUIET)
    fun setStyle(c: Context, s: Style) = prefs(c).edit().putString("night_style", s.name).apply()
    fun countdown(c: Context) = prefs(c).getBoolean("night_countdown", true)
    fun setCountdown(c: Context, on: Boolean) = prefs(c).edit().putBoolean("night_countdown", on).apply()
    fun moon(c: Context) = prefs(c).getBoolean("night_moon", true)
    fun setMoon(c: Context, on: Boolean) = prefs(c).edit().putBoolean("night_moon", on).apply()

    /** How dim the clock may go: 0 a whisper (as before), 1 soft, 2 clear. */
    fun floor(c: Context) = prefs(c).getInt("night_floor", 1)
    fun setFloor(c: Context, f: Int) = prefs(c).edit().putInt("night_floor", f).apply()
    fun floorDim(c: Context) = floatArrayOf(0.15f, 0.35f, 0.6f)[floor(c).coerceIn(0, 2)]
    fun floorBrightness(c: Context) = floatArrayOf(0.01f, 0.04f, 0.1f)[floor(c).coerceIn(0, 2)]
}
