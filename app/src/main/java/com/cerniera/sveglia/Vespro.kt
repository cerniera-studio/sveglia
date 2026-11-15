package com.cerniera.sveglia

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.cerniera.kit.Kit

/**
 * Notification access is how Android lets an app see other apps' media sessions (as Fold Widgets'
 * MediaListener does). Vespro reads no notifications; the service only has to exist and be allowed.
 */
class VesproListener : NotificationListenerService()

/**
 * Vespro, first step (15 Nov 2026): fade whatever is playing to silence on the family's curve, pause it,
 * then put its volume back for the morning. The evening built around it (a bedtime, the sunset into
 * the crease, Do Not Disturb) follows once this is proven with Audible, BBC Sounds and YT Music.
 */
object Vespro {
    private const val TAG = "Vespro"
    private val main = Handler(Looper.getMainLooper())
    private var running: Runnable? = null

    /** Where a fade has got to, for the settings row: the app, and how far through (0 to 1). */
    var onProgress: ((String, Float) -> Unit)? = null
    var onDone: ((String) -> Unit)? = null

    fun allowed(c: Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }
    }

    /** The session that is playing now, if any (the first one playing, as Android orders them). */
    fun playing(c: Context): MediaController? {
        if (!allowed(c)) return null
        val msm = c.getSystemService(MediaSessionManager::class.java)
        val list = runCatching { msm.getActiveSessions(ComponentName(c, VesproListener::class.java)) }.getOrElse { Log.w(TAG, "sessions: $it"); return null }
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }

    fun label(c: Context, mc: MediaController): String = runCatching {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(mc.packageName, 0)).toString()
    }.getOrDefault(mc.packageName)

    val fading get() = running != null

    fun stop() { running?.let { main.removeCallbacks(it) }; running = null }

    /**
     * Fades the playing session over [ms]. A remote session (a cast, or a speaker the app controls)
     * is lowered through the session itself; anything playing on the phone through the media stream,
     * which is put back to where it was a moment after the pause.
     */
    fun fade(c: Context, ms: Long = 60_000L): String? {
        val mc = playing(c) ?: return null
        stop()
        val name = label(c, mc)
        val am = c.getSystemService(AudioManager::class.java)
        val remote = mc.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        val start = if (remote) mc.playbackInfo?.currentVolume ?: 0 else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.i(TAG, "fade $name (${mc.packageName}) from $start over ${ms}ms, ${if (remote) "remote" else "media stream"}")
        val t0 = SystemClock.uptimeMillis()
        var last = start
        val step = object : Runnable {
            override fun run() {
                val p = ((SystemClock.uptimeMillis() - t0) / ms.toFloat()).coerceIn(0f, 1f)
                // The family's curve, read backwards: the sound leaves gently, then goes
                val v = Math.round(start * (1f - Kit.ease.getInterpolation(p)))
                if (v != last) {
                    last = v
                    runCatching { if (remote) mc.setVolumeTo(v, 0) else am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                }
                onProgress?.invoke(name, p)
                if (p < 1f && mc.playbackState?.state == PlaybackState.STATE_PLAYING) { main.postDelayed(this, 250); return }
                runCatching { mc.transportControls.pause() }
                Log.i(TAG, "paused $name at ${(p * 100).toInt()}%")
                // Back to how it was, once the pause has landed, so the morning isn't silent
                main.postDelayed({
                    runCatching { if (remote) mc.setVolumeTo(start, 0) else am.setStreamVolume(AudioManager.STREAM_MUSIC, start, 0) }
                    Log.i(TAG, "volume restored to $start")
                }, 1500)
                running = null
                onDone?.invoke(name)
            }
        }
        running = step
        main.post(step)
        return name
    }
}
