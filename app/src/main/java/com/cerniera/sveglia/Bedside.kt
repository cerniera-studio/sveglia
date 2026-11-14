package com.cerniera.sveglia

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import kotlin.math.ln

/**
 * The bedside clock: the time over the night (see [NightView] for its styles), dimmed to the room. The
 * ambient light sensor sets how bright it is, from barely there in a dark room to readable by a lamp,
 * never below the chosen floor. A touch shows Done for 3.2 seconds (Giulietta).
 */
class BedsideActivity : Activity(), SensorEventListener {
    private lateinit var clock: NightView
    private var light: Sensor? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        clock = NightView(this)
        val done = text("Done", 16f, 0xFFF6F1E6.toInt(), 500).apply {
            setPadding(px(26), px(14), px(26), px(14)); background = rounded(0x22F6F1E6, px(26).toFloat()); alpha = 0f
            setOnClickListener { finish() }
        }
        setContentView(FrameLayout(this).apply {
            addView(clock, FrameLayout.LayoutParams(-1, -1))
            addView(done, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(48) })
        })
        // Only once there is a window: hide the bars for a full-screen sky
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        clock.setOnClickListener {
            done.animate().alpha(1f).setDuration(Kit.QUICK).start()
            done.postDelayed({ done.animate().alpha(0f).setDuration(Kit.SETTLE).start() }, Kit.RECEDE_MS)
        }
        light = getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
        dimTo(0.05f)
    }

    private val recount = object : Runnable {
        override fun run() { clock.caption = nextCaption(this@BedsideActivity); clock.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 80) }
    }

    override fun onResume() {
        super.onResume()
        recount.run()
        light?.let { getSystemService(SensorManager::class.java)?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        clock.removeCallbacks(recount)
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values.firstOrNull() ?: return
        val (b, d) = Bedside.forLux(lux)
        dimTo(b); clock.dim = d
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun dimTo(b: Float) { window.attributes = window.attributes.apply { screenBrightness = maxOf(b, Night.floorBrightness(this@BedsideActivity)) } }
}

/** The same clock as a screen saver, for when the phone is charging on the bedside table. */
class Nightstand : DreamService(), SensorEventListener {
    private lateinit var clock: NightView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        clock = NightView(this).apply { caption = nextCaption(this@Nightstand) }
        val recount = object : Runnable {
            override fun run() { clock.caption = nextCaption(this@Nightstand); clock.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 80) }
        }
        clock.post(recount)
        setContentView(clock)
        getSystemService(SensorManager::class.java)?.let { sm -> sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
    }

    override fun onDetachedFromWindow() {
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(e: SensorEvent) { clock.dim = Bedside.forLux(e.values.firstOrNull() ?: return).second }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

object Bedside {
    /** Screen brightness and text dimming for the room's light: log-scaled, so a dark room gets a whisper. */
    fun forLux(lux: Float): Pair<Float, Float> {
        val t = (ln(1f + lux.coerceAtLeast(0f)) / ln(1f + 300f)).coerceIn(0f, 1f)
        return (0.01f + 0.24f * t) to (0.15f + 0.85f * t)
    }
}

/**
 * The line under the bedside time. Within a day of the alarm it is a quiet count, "7 h 40 until your
 * 07:00 alarm", so putting the phone down at night says how much sleep is left; further off, the day.
 */
fun nextCaption(c: Context): String {
    val n = Alarms.next(c) ?: return ""
    val now = Alarm.now()
    val time = "%02d:%02d".format(n.ring.hour, n.ring.minute)
    val m = java.time.Duration.between(now, n.ring).toMinutes().coerceAtLeast(0)
    return when {
        m < 1 -> "Your $time alarm"
        m < 60 -> "$m min until your $time alarm"
        m < 24 * 60 -> "${m / 60} h${if (m % 60 > 0) " ${m % 60}" else ""} until your $time alarm"
        else -> "Next alarm " + n.ring.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::titlecase) + " $time"
    }
}
