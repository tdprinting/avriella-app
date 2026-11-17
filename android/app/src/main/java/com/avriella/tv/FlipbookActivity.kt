package com.avriella.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.avriella.tv.data.Baby
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Flip mode: plays every monthly photo in order, newborn to now, so you can watch her grow. */
class FlipbookActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var a: ImageView
    private lateinit var b: ImageView
    private lateinit var title: android.widget.TextView
    private lateinit var sub: android.widget.TextView
    private lateinit var dots: LinearLayout
    private lateinit var playBtn: android.widget.TextView
    private lateinit var speedBtn: android.widget.TextView
    private var months: List<Int> = emptyList()
    private var i = 0
    private var playing = true
    private var front = true
    private val speeds = listOf(2200L to "🐢 Slow", 1300L to "🚶 Normal", 650L to "🐇 Fast")
    private var speed = 1
    private val cache = HashMap<Int, Bitmap?>()

    private val tick = object : Runnable {
        override fun run() {
            if (!playing || months.size < 2) return
            val next = (i + 1) % months.size
            // pause a little longer on the newest photo before starting over
            show(next)
            ui.postDelayed(this, speeds[speed].first * (if (next == months.size - 1) 2 else 1))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Baby.ensure()
        months = Baby.photoMonths().sorted()
        if (months.isEmpty()) { toast("Add a few monthly photos first."); finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        a = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        b = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
        root.addView(a, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(b, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setOnClickListener { togglePlay() }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(30))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        title = label("", 30f, Color.WHITE, true)
        sub = label("", 15f, 0xDDFFFFFF.toInt())
        top.addView(title); top.addView(sub)
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(30), dp(16), dp(28))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        dots = hbox().apply { gravity = Gravity.CENTER }
        months.forEach { _ -> dots.addView(View(this), lp(dp(8), dp(8), l = dp(3), r = dp(3))) }
        bottom.addView(dots, lp(WRAP, WRAP, b = dp(14)))
        val ctl = hbox().apply { gravity = Gravity.CENTER }
        ctl.addView(pill("⏮", 0x33FFFFFF, Color.WHITE, 20f) { step(-1) }, lp(dp(64), WRAP, r = dp(8)))
        playBtn = pill("⏸", 0x55FFFFFF, Color.WHITE, 20f) { togglePlay() }
        ctl.addView(playBtn, lp(dp(84), WRAP, r = dp(8)))
        ctl.addView(pill("⏭", 0x33FFFFFF, Color.WHITE, 20f) { step(1) }, lp(dp(64), WRAP, r = dp(8)))
        speedBtn = pill(speeds[speed].second, 0x33FFFFFF, Color.WHITE, 15f) {
            speed = (speed + 1) % speeds.size; speedBtn.text = speeds[speed].second; restart()
        }
        ctl.addView(speedBtn, lp(WRAP, WRAP))
        ctl.addView(pill("✕", 0x33FFFFFF, Color.WHITE, 18f) { finish() }, lp(dp(56), WRAP, l = dp(8)))
        bottom.addView(ctl)
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        if (months.size == 1) toast("Only one photo so far — add more each month to watch her grow!")
        show(0, animate = false)
        restart()
    }

    private fun bmp(m: Int): Bitmap? = cache.getOrPut(m) {
        val f = Baby.photoFile(m)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1400) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun label(m: Int) = when {
        m == 0 -> "Newborn"
        m % 12 == 0 -> "${m / 12} year${if (m == 12) "" else "s"} old 🎂"
        m > 12 -> "${m / 12} yr ${m % 12} mo"
        else -> "$m month${if (m == 1) "" else "s"} old"
    }

    private fun show(index: Int, animate: Boolean = true) {
        i = index
        val m = months[i]
        val incoming = if (front) b else a
        val outgoing = if (front) a else b
        incoming.setImageBitmap(bmp(m))
        if (animate) {
            val d = (speeds[speed].first / 3).coerceIn(200, 600)
            incoming.animate().alpha(1f).setDuration(d).start()
            outgoing.animate().alpha(0f).setDuration(d).start()
            front = !front
        } else { outgoing.setImageBitmap(bmp(m)); outgoing.alpha = 1f; incoming.alpha = 0f }
        title.text = label(m)
        sub.text = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(Baby.monthDate(m))) + "   ·   ${i + 1} of ${months.size}"
        for (k in 0 until dots.childCount) dots.getChildAt(k).background = rounded(if (k == i) Color.WHITE else 0x55FFFFFF, dpf(4f))
        // decode the next one ahead of time
        if (months.size > 1) { val n = months[(i + 1) % months.size]; ui.post { bmp(n) } }
    }

    private fun step(d: Int) { show((i + d + months.size) % months.size); restart() }
    private fun togglePlay() { playing = !playing; playBtn.text = if (playing) "⏸" else "▶"; restart() }
    private fun restart() { ui.removeCallbacks(tick); if (playing) ui.postDelayed(tick, speeds[speed].first) }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
