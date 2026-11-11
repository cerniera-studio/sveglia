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
 * The bedside clock: the time over the night sky, dimmed to the room. The ambient light sensor
 * sets how bright it is, from barely there in a dark room to readable by a lamp. Nothing else is
 * on screen (Concentrato); a touch shows Done for 3.2 seconds (Giulietta).
 */
class BedsideActivity : Activity(), SensorEventListener {
    private lateinit var clock: ClockView
    private var light: Sensor? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        clock = ClockView(this)
        val done = text("Done", 16f, 0xFFF6F1E6.toInt(), 500).apply {
            setPadding(px(26), px(14), px(26), px(14)); background = rounded(0x22F6F1E6, px(26).toFloat()); alpha = 0f
            setOnClickListener { finish() }
        }
        setContentView(FrameLayout(this).apply {
            addView(clock, FrameLayout.LayoutParams(-1, -1))
            addView(done, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(48) })
        })
        clock.setOnClickListener {
            done.animate().alpha(1f).setDuration(Kit.QUICK).start()
            done.postDelayed({ done.animate().alpha(0f).setDuration(Kit.SETTLE).start() }, Kit.RECEDE_MS)
        }
        light = getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
        dimTo(0.05f)
    }

    override fun onResume() {
        super.onResume()
        clock.caption = nextCaption(this)
        light?.let { getSystemService(SensorManager::class.java)?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values.firstOrNull() ?: return
        val (b, d) = Bedside.forLux(lux)
        dimTo(b); clock.dim = d
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun dimTo(b: Float) { window.attributes = window.attributes.apply { screenBrightness = b } }
}

/** The same clock as a screen saver, for when the phone is charging on the bedside table. */
class Nightstand : DreamService(), SensorEventListener {
    private lateinit var clock: ClockView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        clock = ClockView(this).apply { caption = nextCaption(this@Nightstand) }
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

fun nextCaption(c: Context): String {
    val n = Alarms.next(c) ?: return ""
    return "Alarm " + "%02d:%02d".format(n.ring.hour, n.ring.minute) + " · " + Alarm.until(Alarm.now(), n.ring).lowercase()
}
