package com.cerniera.sveglia

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.SECONDARY
import com.cerniera.kit.Screen
import com.cerniera.kit.Segmented
import com.cerniera.kit.Sheet
import com.cerniera.kit.Toggle
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.endValue
import com.cerniera.kit.field
import com.cerniera.kit.pressable
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text

/**
 * Sveglia: alarms that wake you with light first. The list of alarms, the next one large at the
 * top, the bedside clock, and only the permissions that still need granting.
 */
private const val ASK_PLACE = 31

class MainActivity : Activity() {
    private var editing: Sheet? = null
    private var pickedSound: ((String?) -> Unit)? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Sveglia")) return
        Kit.follow(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        render()
        // For the emulator walk-through: open straight into the dawn preview or the bedside clock
        when (intent?.getStringExtra("open")) {
            "preview" -> startForegroundService(Intent(this, RingService::class.java).setAction(RingService.PREVIEW))
            "bedside" -> startActivity(Intent(this, BedsideActivity::class.java))
        }
    }

    /** Coming back from a picker leaves an open sheet as it was. */
    override fun onResume() {
        super.onResume(); Alarms.schedule(this); if (editing == null) render()
        // While Sveglia is open, the sky follows the phone (never in the background)
        Daylight.follow(this) { moved -> if (moved && editing == null) render() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_PLACE) Daylight.follow(this) { if (editing == null) render() }
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); Kit.follow(this); render() }

    private fun render() {
        window.statusBarColor = Kit.GROUND; window.navigationBarColor = Kit.GROUND
        val s = Screen(this, "Sveglia", "Cerniera · Alarm")
        val panes = Panes(this, s)
        val now = Alarm.now()
        val next = Alarms.next(this, now)

        // The next alarm, with its dawn drawn small beside it
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Kit.SURFACE, px(26).toFloat(), Kit.HAIRLINE, px(1))
            setPadding(px(22), px(20), px(16), px(20))
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(if (next?.snooze == true) "SNOOZED" else "NEXT", 11f, Kit.CHAMPAGNE, 500).apply { letterSpacing = 0.22f })
        words.addView(text(next?.let { "%02d:%02d".format(it.ring.hour, it.ring.minute) } ?: "—", 56f, Kit.INK, displayFace = true))
        words.addView(text(next?.let { Alarm.until(now, it.ring) + (it.dawnStart?.let { d -> " · dawn from %02d:%02d".format(d.hour, d.minute) } ?: "") } ?: "No alarms set",
            14f, Kit.MUTED).apply { tag = SECONDARY })
        hero.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(ClockView(this).apply { dawn = 0.55f; showTime = false; skyAt = next?.ring?.toInstant()?.toEpochMilli() ?: 0L; background = rounded(0, px(18).toFloat()); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND },
            LinearLayout.LayoutParams(px(88), px(116)))
        panes.add(hero, px(22))

        val list = Alarms.all(this)
        val addBtn = primary("Add an alarm", filled = list.isEmpty()) { edit(null) }
        panes.add(addBtn, px(12))
        val root = panes.build()

        // Permissions, only when something is missing (Giulietta)
        val missing = mutableListOf<Row>()
        if (!Alarms.canExact(this)) missing += Row(this, "Allow alarms", "Without it, Android may not wake the phone on time", endValue("›", Kit.WARN)) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) missing += Row(this, "Allow notifications", "The alarm shows as a notification while it rings", endValue("›", Kit.WARN)) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) missing += Row(this, "Allow full-screen alarms", "So the dawn can fill the screen over the lock screen", endValue("›", Kit.WARN)) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
        if (missing.isNotEmpty()) {
            s.section("Needs your permission")
            s.add(Card(this).apply { missing.forEach { row(it) } })
        }

        // The alarms
        s.section("Alarms")
        var firstAlarm: Row? = null
        if (list.isNotEmpty()) {
            val c = Card(this)
            list.forEach { a ->
                val sub = listOfNotNull(a.repeats(), if (a.dawn > 0) "dawn ${a.dawn} min" else "no dawn", a.label.takeIf { it.isNotBlank() }).joinToString(" · ")
                val row = Row(this, "%02d:%02d".format(a.hour, a.minute), sub, Toggle(this, a.enabled) { on -> Alarms.put(this, a.copy(enabled = on)); render() }) { edit(a) }
                row.titleView.apply { typeface = Kit.display(context); textSize = 30f; setTextColor(if (a.enabled) Kit.INK else Kit.MUTED) }
                c.row(row)
                if (firstAlarm == null) firstAlarm = row
            }
            s.add(c)
        }

        // The bedside
        s.section("Bedside")
        val b = Card(this)
        b.row(Row(this, "Bedside clock", "The time over the night sky, dimmed to the room", endValue("›")) { startActivity(Intent(this, BedsideActivity::class.java)) })
        b.row(Row(this, "As a screen saver", "Shows by itself while charging: choose Sveglia under Screen saver", endValue("›")) {
            runCatching { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
        })
        b.row(Row(this, "Sky over", Daylight.describe(), endValue(if (Daylight.hasLocation(this)) "›" else "Use my location", if (Daylight.hasLocation(this)) Kit.MUTED else Kit.CHAMPAGNE)) {
            if (Daylight.hasLocation(this)) Daylight.follow(this) { if (editing == null) render() }
            else requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), ASK_PLACE)
        })
        b.row(Row(this, "Try the dawn", "Thirty seconds of your next alarm's dawn, under its real sky, then ten of the alarm", endValue("›")) {
            startForegroundService(Intent(this, RingService::class.java).setAction(RingService.PREVIEW))
        })
        s.add(b)

        s.add(doctrineCard(listOf(
            "Membrane" to "The time drums roll with your finger and settle on the nearest number. The sun is one body with the sky: the whole screen warms as it climbs.",
            "Concentrato" to "At night the screen holds only the time. During the dawn nothing else appears until you touch it.",
            "Giulietta" to "The wake screen's controls wait for a touch during the dawn and recede after 3.2 seconds. Permissions show only when one is missing.",
            "The sky" to "The sun is the real one over the phone, the same as Solco's. The alarm brings the light, but the sun only appears when it is really up: on a winter morning before first light, only the horizon warms.",
            "The fold" to "The sun rises out of the hinge: from the foot of the cover screen, or up the crease when open. Folding or unfolding the phone stops the alarm, because the hand that moves the hinge is awake.",
        )), px(28))
        setContentView(root)
        panes.record(root, mapOf("next" to hero, "add" to addBtn, "first-alarm" to firstAlarm))
    }

    // ---- Editing -----------------------------------------------------------------------------

    private fun edit(a: Alarm?) {
        val start = a ?: Alarm(Alarms.newId(this), 7, 0, setOf(1, 2, 3, 4, 5))
        var hour = start.hour; var minute = start.minute
        val days = start.days.toMutableSet()
        var dawn = start.dawn
        var sound = start.sound
        lateinit var label: android.widget.EditText
        editing = Sheet.show(this, if (a == null) "New alarm" else "Alarm", onClose = { editing = null }, action = "Save" to { sh ->
            Alarms.put(this, Alarm(start.id, hour, minute, days.toSet(), label.text.toString().trim(), dawn, true, sound))
            sh.dismiss(); render()
        }) { sh ->
            sh.body.addView(TimeDial(this, hour, minute) { h, m -> hour = h; minute = m })

            // Days: seven small rounds, champagne when chosen. None chosen means once.
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val note = text("", 13f, Kit.MUTED)
            fun describe() { note.text = start.copy(days = days.toSet()).repeats().let { if (it == "Once") "Once, at the next %02d:%02d".format(hour, minute) else it } }
            "MTWTFSS".forEachIndexed { i, ch ->
                val d = i + 1
                val v = text(ch.toString(), 14f, Kit.INK, 500).apply { gravity = Gravity.CENTER; isClickable = true; pressable() }
                fun paint() { val on = d in days; v.setTextColor(if (on) Kit.GROUND else Kit.INK); v.background = rounded(if (on) Kit.CHAMPAGNE else Kit.RAISED, 100f) }
                paint()
                v.setOnClickListener { if (d in days) days -= d else days += d; paint(); describe() }
                row.addView(v, LinearLayout.LayoutParams(0, px(40), 1f).apply { if (i > 0) marginStart = px(6) })
            }
            describe()
            sh.body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
            sh.body.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })

            sh.body.addView(text("DAWN", 10.5f, Kit.MUTED, 500).apply { letterSpacing = 0.2f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18) })
            val dawns = listOf(0, 10, 20, 30)
            sh.body.addView(Segmented(this, listOf("None", "10 min", "20 min", "30 min"), dawns.indexOf(dawn).coerceAtLeast(0)) { dawn = dawns[it] },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })

            label = field("Label (optional)", start.label)
            sh.body.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })

            val soundRow = Row(this, "Sound", soundName(sound), endValue("›")) {
                pickedSound = { picked -> sound = picked }
                startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound?.let(Uri::parse)), 7)
            }.apply { setPadding(0, px(14), 0, px(6)) }
            pickedSound = { picked -> sound = picked; soundRow.setSub(soundName(picked)) }
            sh.body.addView(soundRow)

            if (a != null) sh.body.addView(primary("Delete", filled = false) { Alarms.remove(this, a.id); sh.dismiss(); render() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }
    }

    private fun soundName(uri: String?): String =
        runCatching { RingtoneManager.getRingtone(this, uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).getTitle(this) }.getOrNull()
            ?: "The phone's alarm sound"

    @Deprecated("A one-off pick")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req != 7 || res != RESULT_OK) return
        @Suppress("DEPRECATION") val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        pickedSound?.invoke(uri?.toString())
    }
}
