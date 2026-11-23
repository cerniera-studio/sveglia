package com.cerniera.sveglia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.cerniera.kit.Crash
import com.cerniera.kit.Hinge

/**
 * A timer that has run out. Unlike the alarm it is heard across a kitchen at once: Campanile's two
 * bells rise to full in eight seconds, with a pulse. It stops on Stop, on "+1 min", when the phone
 * is folded or unfolded (the hand that moves the hinge has heard it, as with the alarm), or by
 * itself after two minutes, when the timer is left showing 0:00 until dismissed.
 */
class TimerRing : Service(), Hinge.Listener {
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var hinge: Hinge? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            RING -> { foreground(); ring() }
            STOP -> stop(addMinute = false)
            MORE -> stop(addMinute = true)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ringing() = Chrono.timers(this).filter { it.ringing }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timer ends", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); description = "A timer that has run out"
        })
        val show = PendingIntent.getActivity(this, 42, Intent(this, CronografoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("face", "timer"), PendingIntent.FLAG_IMMUTABLE)
        fun act(label: String, action: String) = Notification.Action.Builder(null, label,
            PendingIntent.getService(this, action.hashCode(), android.content.Intent(this, TimerRing::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)).build()
        val t = ringing()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t.firstOrNull()?.let { it.label.ifBlank { Chrono.words(it.length) } } ?: "Timer")
            .setContentText(if (t.size > 1) "${t.size} timers are up · fold or unfold to stop" else "Time is up · fold or unfold to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(show, true)
            .setContentIntent(show)
            .setOngoing(true)
            .addAction(act("+1 min", MORE))
            .addAction(act("Stop", STOP))
            .build()
        runCatching { startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            .onFailure { Crash.save(this, "Sveglia", "Timer startForeground", it) }
        // While the phone is in use the full-screen intent becomes a heads-up; with "Appear on top" the face opens too
        if (android.provider.Settings.canDrawOverlays(this)) runCatching { startActivity(Intent(this, CronografoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("face", "timer")) }
    }

    private fun ring() {
        Chrono.changed()
        if (player == null) {
            val u = Uri.parse(Sounds.uri(this, "campanile"))
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@TimerRing, u); isLooping = true; setVolume(0.25f, 0.25f); prepare(); start()
                }
            }.onFailure { Crash.save(this, "Sveglia", "Timer sound", it) }.getOrNull()
            val start = System.currentTimeMillis()
            h.post(object : Runnable {
                override fun run() {
                    val p = player ?: return
                    val t = ((System.currentTimeMillis() - start) / 8_000f).coerceIn(0f, 1f)
                    runCatching { p.setVolume(0.25f + 0.75f * t, 0.25f + 0.75f * t) }
                    if (t < 1f) h.postDelayed(this, 250)
                }
            })
            vibrator = getSystemService(Vibrator::class.java)
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 250, 250, 1200), 0)) }
            hinge = Hinge(this, this).also { it.start() }
            h.postDelayed({ silence() }, 2 * 60_000L)
        }
    }

    /** Folding or unfolding is the hand saying it heard. */
    override fun onFold(opened: Boolean, fast: Boolean) = stop(addMinute = false)

    /** After two minutes the sound ends; the timer stays at 0:00 until dismissed. */
    private fun silence() {
        h.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { vibrator?.cancel() }
        hinge?.stop(); hinge = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stop(addMinute: Boolean) {
        val now = System.currentTimeMillis()
        val list = Chrono.timers(this).map { t -> if (!t.ringing) t else if (addMinute) Chrono.addMinute(t, now) else Chrono.reset(t) }
        Chrono.saveTimers(this, list)
        silence()
        getSystemService(NotificationManager::class.java).cancel(NOTE)
        Chrono.changed()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        runCatching { vibrator?.cancel() }
        hinge?.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "timer-end"
        const val NOTE = 43
        const val RING = "com.cerniera.sveglia.TIMER_RING"
        const val STOP = "com.cerniera.sveglia.TIMER_STOP"
        const val MORE = "com.cerniera.sveglia.TIMER_MORE"
        fun send(c: android.content.Context, action: String) = c.startService(Intent(c, TimerRing::class.java).setAction(action))
    }
}
