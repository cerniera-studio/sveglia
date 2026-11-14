package com.cerniera.sveglia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.cerniera.kit.Kit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sveglia's own sounds (14 Nov 2026), made for the dawn: each one starts sparse and soft, and the
 * ring swells it from a whisper over a minute, so sound and sky rise together. Synthesised from
 * scratch (tools/sounds.py), no samples. A new alarm starts with Alba.
 */
object Sounds {
    class Sound(val key: String, val name: String, val detail: String)

    val OWN = listOf(
        Sound("alba", "Alba", "Soft bells climbing over a low drone"),
        Sound("campanile", "Campanile", "Two distant bells, slow"),
        Sound("brezza", "Brezza", "A warm chord breathing, notes drifting above"),
    )

    fun uri(c: Context, key: String) = "android.resource://${c.packageName}/raw/$key"

    fun own(c: Context, uri: String?): Sound? = OWN.firstOrNull { uri == uri(c, it.key) }

    fun default(c: Context) = uri(c, "alba")

    fun name(c: Context, uri: String?): String = own(c, uri)?.name
        ?: runCatching { RingtoneManager.getRingtone(c, uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).getTitle(c) }.getOrNull()
        ?: "The phone's alarm sound"

    /** A small drawing for each sound's row: bells as rising points, a bell's rings, a breathing wave. */
    fun painter(key: String): (Canvas, RectF) -> Unit = { c, r ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.CHAMPAGNE; style = Paint.Style.STROKE; strokeWidth = r.height() / 28 }
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.CHAMPAGNE }
        when (key) {
            "alba" -> for (i in 0 until 6) {
                val x = r.left + r.width() * (0.18f + i * 0.13f)
                val y = r.bottom - r.height() * (0.25f + i * 0.1f)
                c.drawCircle(x, y, r.height() * (0.035f + 0.008f * i), f)
            }
            "phone" -> c.drawRoundRect(RectF(r.centerX() - r.height() * 0.2f, r.top + r.height() * 0.18f, r.centerX() + r.height() * 0.2f, r.bottom - r.height() * 0.18f),
                r.height() * 0.07f, r.height() * 0.07f, p)
            "campanile" -> for (i in 0 until 3) c.drawCircle(r.centerX(), r.centerY(), r.height() * (0.12f + i * 0.12f), p.apply { alpha = 255 - i * 70 })
            else -> {
                val path = android.graphics.Path()
                for (i in 0..40) {
                    val t = i / 40f
                    val x = r.left + r.width() * (0.1f + 0.8f * t)
                    val y = r.centerY() - r.height() * 0.22f * sin(t * 2 * PI).toFloat() * (0.4f + 0.6f * sin(t * PI).toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, p)
            }
        }
    }

    private var player: MediaPlayer? = null

    /** A few seconds of a sound, at a modest level, while choosing. */
    fun preview(c: Context, uri: String?) {
        stopPreview()
        val u = uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(c, u); setVolume(0.35f, 0.35f); prepare(); start()
            }
        }.getOrNull()
        val mine = player
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (player === mine) stopPreview() }, 6000)
    }

    fun stopPreview() { runCatching { player?.stop(); player?.release() }; player = null }
}
