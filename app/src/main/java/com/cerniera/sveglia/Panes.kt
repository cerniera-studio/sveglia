package com.cerniera.sveglia

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.LinearLayout
import com.cerniera.kit.Kit
import com.cerniera.kit.Screen
import com.cerniera.kit.px

/**
 * The alarm list in every shape of the phone (13 Nov 2026), as in Solco and Fold Motion. Upright on
 * the cover: one scrolling column, the next alarm first. Anywhere wider (the cover turned, the main
 * screen either way round): the next alarm and Add an alarm stand in a pane of their own, and the
 * alarms, the bedside and the notes scroll beside it. Either way the next alarm, the way to add one
 * and the first alarms are in view at rest; only the bedside and the notes sit below the fold.
 */
class Panes(private val a: Activity, val screen: Screen) {
    val wide: Boolean = a.resources.configuration.let { it.screenWidthDp > it.screenHeightDp || it.smallestScreenWidthDp >= 600 }

    val lead = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

    fun add(v: View, top: Int = 0) = if (wide) lead.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }) else screen.add(v, top)

    /** Call once the lead is added, before the rest: returns the Activity's content. */
    fun build(): View {
        if (!wide) return screen.root
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Kit.GROUND)
            // The panes keep clear of the bars and the camera on every side
            setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        lead.setPadding(a.px(24), a.px(20), a.px(8), a.px(20))
        row.addView(lead, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(screen.root, LinearLayout.LayoutParams(0, -1, 1f))
        return row
    }

    /**
     * Records where the parts that must be in view at rest ended up, for the shapes test. Written to
     * files/layout.json once laid out; boxes are in window coordinates, origin is where the view starts.
     */
    fun record(root: View, parts: Map<String, View?>) {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root.height <= 0) return
                fun box(v: View): String { val p = IntArray(2); v.getLocationInWindow(p); return "[${p[0]}, ${p[1]}, ${p[0] + v.width}, ${p[1] + v.height}]" }
                val inset = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
                val o = IntArray(2).also { root.getLocationInWindow(it) }
                val boxes = parts.entries.filter { it.value != null && it.value!!.height > 0 }.joinToString(", ") { "\"${it.key}\": ${box(it.value!!)}" }
                val json = "{\"wide\": $wide, \"origin\": [${o[0]}, ${o[1]}], \"view\": [${root.width}, ${root.height}], " +
                    "\"insets\": [${inset?.left ?: 0}, ${inset?.top ?: 0}, ${inset?.right ?: 0}, ${inset?.bottom ?: 0}], \"parts\": {$boxes}}"
                runCatching { java.io.File(a.filesDir, "layout.json").writeText(json) }
            }
        })
    }
}
