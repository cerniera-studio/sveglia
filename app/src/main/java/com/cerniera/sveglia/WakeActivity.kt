package com.cerniera.sveglia

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cerniera.kit.Hinge
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The wake screen, over the lock screen. During the dawn it is only sky, sun and time, the screen
 * brightening with it. Once it rings: tap anywhere to snooze for nine minutes; fold or unfold the
 * phone to stop, because the hand that moves the hinge is awake.
 */
class WakeActivity : Activity(), Hinge.Listener {
    private lateinit var clock: ClockView
    private lateinit var controls: LinearLayout
    private lateinit var snoozeBtn: View
    private lateinit var stopBtn: android.widget.TextView
    private lateinit var hinge: Hinge
    private val h = Handler(Looper.getMainLooper())
    private var wide = false
    private val onLive: () -> Unit = { h.post { update() } }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wide = Hinge.unfolded(this)
        hinge = Hinge(this, this)

        clock = ClockView(this)
        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; alpha = 0f }
        fun pill(label: String, filled: Boolean, onTap: () -> Unit) = text(label, 16f, if (filled) 0xFF0E1311.toInt() else 0xFFF6F1E6.toInt(), 500).apply {
            setPadding(px(26), px(16), px(26), px(16))
            background = rounded(if (filled) 0xFFD9BB86.toInt() else 0x33000000, px(28).toFloat(), 0x40F6F1E6, px(1))
            setOnClickListener { onTap() }
        }
        snoozeBtn = pill("Snooze 9 min", false) { RingService.send(this, RingService.SNOOZE) }
        stopBtn = pill("Stop", true) { RingService.send(this, RingService.STOP) }
        controls.addView(snoozeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12) })
        controls.addView(stopBtn)

        setContentView(FrameLayout(this).apply {
            addView(clock, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(56) })
        })
        // The sky runs behind transparent bars. Hiding them would make Android's one-time
        // "viewing full screen" hint swallow the first tap, which here is the snooze.
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = 0; window.navigationBarColor = 0
        clock.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) tapped(); true }
    }

    override fun onResume() {
        super.onResume()
        Live.listen(onLive)
        hinge.start()
        tick.run()
    }

    override fun onPause() {
        Live.unlisten(onLive)
        hinge.stop()
        h.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() { update(); h.postDelayed(this, 1000) }
    }

    private fun update() {
        if (Live.phase == Live.Phase.IDLE) { finish(); return }
        val p = Live.progress()
        clock.skyAt = Live.skyAt()
        clock.dawn = p
        clock.breathing = Live.phase == Live.Phase.RING
        clock.caption = when (Live.phase) {
            Live.Phase.RING -> Live.morning.ifEmpty { "Tap to snooze · fold to stop" }
            else -> Alarms.get(this, Live.id)?.label?.takeIf { it.isNotBlank() }
                ?: "Alarm at " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(Live.ringAt).atZone(ZoneId.systemDefault()))
        }
        val ringing = Live.phase == Live.Phase.RING
        snoozeBtn.visibility = if (ringing) View.VISIBLE else View.GONE
        stopBtn.text = if (ringing) "Stop" else "I'm awake: skip the alarm"
        window.attributes = window.attributes.apply { screenBrightness = Dawn.brightness(p) }
        // Giulietta: once ringing, the controls are part of the screen; during the dawn they wait for a touch
        if (Live.phase == Live.Phase.RING && controls.alpha < 1f) controls.animate().alpha(1f).setDuration(Kit.SETTLE).start()
    }

    private fun tapped() {
        when (Live.phase) {
            Live.Phase.RING -> RingService.send(this, RingService.SNOOZE)
            else -> {
                controls.animate().alpha(1f).setDuration(Kit.QUICK).start()
                h.postDelayed({ if (Live.phase == Live.Phase.DAWN) controls.animate().alpha(0f).setDuration(Kit.SETTLE).start() }, Kit.RECEDE_MS)
            }
        }
    }

    /** Folding or unfolding stops it: from the hinge sensor, or from the screen changing. */
    override fun onFold(opened: Boolean, fast: Boolean) { if (Live.phase != Live.Phase.IDLE) RingService.send(this, RingService.STOP) }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val nowWide = Hinge.unfolded(this)
        if (nowWide != wide && Live.phase != Live.Phase.IDLE) RingService.send(this, RingService.STOP)
        wide = nowWide
        clock.invalidate()
    }
}
