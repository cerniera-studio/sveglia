package com.cerniera.sveglia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.cerniera.kit.Crash

/**
 * The alarm while it happens. The dawn phase is silent: the wake screen rises like a sunrise. At
 * the alarm's time the sound begins almost inaudibly and swells over a minute, with a soft pulse
 * after thirty seconds. It stops when the phone is folded or unfolded, when Stop is touched, or by
 * itself after fifteen minutes. A tap anywhere snoozes for nine.
 */
class RingService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val id = i?.getIntExtra("id", -1) ?: -1
        when (i?.action) {
            AlarmReceiver.DAWN -> { foreground(id); dawn(id) }
            AlarmReceiver.RING -> { foreground(id); ring(id) }
            PREVIEW -> { foreground(-1); preview() }
            STOP -> stop(snooze = false)
            SNOOZE -> stop(snooze = true)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun foreground(id: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); description = "The alarm while it rings"
        })
        val wake = PendingIntent.getActivity(this, 0, Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), PendingIntent.FLAG_IMMUTABLE)
        fun act(label: String, action: String) = Notification.Action.Builder(null, label,
            PendingIntent.getService(this, action.hashCode(), Intent(this, RingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)).build()
        val a = Alarms.get(this, id)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(a?.label?.takeIf { it.isNotBlank() } ?: "Sveglia")
            .setContentText(if (Live.phase == Live.Phase.RING) "Fold or unfold the phone to stop" else "Dawn is rising")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(wake, true)
            .setContentIntent(wake)
            .setOngoing(true)
            .addAction(act("Snooze", SNOOZE))
            .addAction(act("Stop", STOP))
            .build()
        runCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            .onFailure { Crash.save(this, "Sveglia", "startForeground", it) }
        // The full-screen intent only shows by itself when the phone is locked or the screen is off
        runCatching { startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun dawn(id: Int) {
        val a = Alarms.get(this, id) ?: return ring(id)
        val ringAt = a.next(Alarm.now().minusMinutes(a.dawn.toLong() + 1))?.toInstant()?.toEpochMilli() ?: return ring(id)
        Live.begin(Live.Phase.DAWN, id, System.currentTimeMillis(), ringAt)
    }

    private fun ring(id: Int) {
        if (Live.phase != Live.Phase.DAWN || Live.id != id) Live.begin(Live.Phase.RING, id, System.currentTimeMillis(), System.currentTimeMillis())
        Live.phase = Live.Phase.RING
        Live.changed()
        sound(Alarms.get(this, id)?.sound)
        h.postDelayed({ stop(snooze = false) }, 15 * 60_000L)
    }

    /** A thirty-second dawn and ten seconds of sound, from the app, to see and hear it. */
    private fun preview() {
        val now = System.currentTimeMillis()
        Live.begin(Live.Phase.DAWN, -1, now, now + 30_000)
        // The preview's sky is the next alarm's: the real sun as it will stand when that alarm rings
        Alarms.next(this)?.let { Live.skyOffset = it.ring.toInstant().toEpochMilli() - (now + 30_000) }
        h.postDelayed({ Live.phase = Live.Phase.RING; Live.changed(); sound(null); h.postDelayed({ stop(false) }, 10_000) }, 30_000)
    }

    private fun sound(uri: String?) {
        if (player != null) return
        val u = uri?.let(Uri::parse) ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@RingService, u)
                isLooping = true
                setVolume(0.03f, 0.03f)
                prepare(); start()
            }
        }.onFailure { Crash.save(this, "Sveglia", "Alarm sound", it) }.getOrNull()
        // Swell from a whisper to full over a minute
        val start = System.currentTimeMillis()
        h.post(object : Runnable {
            override fun run() {
                val p = player ?: return
                val t = ((System.currentTimeMillis() - start) / 60_000f).coerceIn(0f, 1f)
                val v = 0.03f + 0.97f * t * t
                runCatching { p.setVolume(v, v) }
                if (t < 1f) h.postDelayed(this, 500)
            }
        })
        h.postDelayed({
            vibrator = getSystemService(Vibrator::class.java)
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 900, 400, 1600), 0)) }
        }, 30_000)
    }

    private fun stop(snooze: Boolean) {
        val id = Live.id
        h.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        if (id >= 0) { if (snooze) Alarms.snooze(this, id) else Alarms.rang(this, id, Live.ringAt) }
        Live.end(snoozed = snooze)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        runCatching { vibrator?.cancel() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "alarm"
        const val STOP = "com.cerniera.sveglia.STOP"
        const val SNOOZE = "com.cerniera.sveglia.SNOOZE"
        const val PREVIEW = "com.cerniera.sveglia.PREVIEW"

        fun send(c: Context, action: String) = c.startService(Intent(c, RingService::class.java).setAction(action))
    }
}

/** What is happening now, shared between the service and the wake screen. */
object Live {
    enum class Phase { IDLE, DAWN, RING }

    @Volatile var phase = Phase.IDLE
    @Volatile var id = -1
    @Volatile var dawnStart = 0L
    @Volatile var ringAt = 0L
    @Volatile var snoozed = false
    /** Added to the clock to find the sky drawn: 0 for a real alarm; a preview shows its alarm's sky. */
    @Volatile var skyOffset = 0L
    private val listeners = mutableSetOf<() -> Unit>()

    fun begin(p: Phase, id: Int, dawnStart: Long, ringAt: Long) {
        phase = p; this.id = id; this.dawnStart = dawnStart; this.ringAt = ringAt; snoozed = false; skyOffset = 0L
        changed()
    }

    fun end(snoozed: Boolean) { phase = Phase.IDLE; this.snoozed = snoozed; changed(); id = -1 }

    /** 0 at the start of the dawn, 1 at the alarm's time. */
    fun progress(now: Long = System.currentTimeMillis()): Float = when (phase) {
        Phase.RING -> 1f
        Phase.DAWN -> if (ringAt <= dawnStart) 1f else ((now - dawnStart).toFloat() / (ringAt - dawnStart)).coerceIn(0f, 1f)
        Phase.IDLE -> 0f
    }

    /** The moment whose real sun the wake screen draws. */
    fun skyAt(now: Long = System.currentTimeMillis()) = now + skyOffset

    fun listen(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun unlisten(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }
}
