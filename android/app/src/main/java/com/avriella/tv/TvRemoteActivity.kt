package com.avriella.tv

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.avriella.tv.cam.Cast
import com.avriella.tv.data.Library
import com.avriella.tv.ui.DvdCase
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.card
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.formatTime
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.toast
import com.avriella.tv.ui.vbox
import org.json.JSONObject

/** The TV remote: what's playing on the TV, and play/pause, scrubbing, volume, fill screen, change movie and stop. */
class TvRemoteActivity : ParentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var st = JSONObject()
    private var seeking = false
    private var volTouched = 0L
    private var coverFor = ""
    private var built = false

    private lateinit var where: TextView
    private lateinit var title: TextView
    private lateinit var coverBox: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var pp: TextView
    private lateinit var vol: SeekBar
    private lateinit var volRow: View
    private lateinit var tvKeys: View
    private lateinit var fill: TextView
    private lateinit var picker: View
    private lateinit var ctl: LinearLayout
    private lateinit var tip: View

    private fun SeekBar.tint(c: Int) {
        val t = android.content.res.ColorStateList.valueOf(c)
        progressTintList = t; thumbTintList = t
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33000000)
    }

    override fun build() {
        titleView.text = "📺 TV remote"
        built = true
        val top = card(18)
        val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        coverBox = vbox()
        head.addView(coverBox, lp(dp(70), WRAP, r = dp(14)))
        val col = vbox()
        where = label("Connecting to the TV…", 13f, Pal.ORANGE, true)
        title = label("", 20f, Pal.INK, true, 2)
        col.addView(where); col.addView(title)
        head.addView(col, lp(0, WRAP, 1f))
        top.addView(head)
        ctl = vbox().apply { visibility = View.GONE }
        top.addView(ctl)

        // scrub through the movie
        seek = SeekBar(this).apply { max = 1000; tint(Pal.ORANGE) }
        ctl.addView(seek, lp(MATCH, dp(44), t = dp(14)))
        val times = hbox()
        pos = label("0:00", 13f, Pal.SUB); dur = label("", 13f, Pal.SUB).apply { gravity = Gravity.END }
        times.addView(pos, lp(0, WRAP, 1f)); times.addView(dur, lp(0, WRAP, 1f))
        ctl.addView(times)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) pos.text = formatTime((st.optDouble("dur", 0.0) * p).toLong()) }
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                val d = st.optDouble("dur", 0.0)
                if (d > 0) cmd("seek", "%.1f".format(java.util.Locale.US, d * s.progress / 1000))
                main.postDelayed({ seeking = false }, 1200)
            }
        })

        // play / pause and jumps
        val tr = hbox().apply { gravity = Gravity.CENTER }
        tr.addView(pill("⏪ 30", Pal.CARD, Pal.INK, 17f) { jump(-30) }, lp(0, WRAP, 1f, r = dp(6)))
        pp = pill("⏸  Pause", Pal.ORANGE, sp = 19f) {
            val paused = st.optString("state") == "paused"
            pp.text = if (paused) "⏸  Pause" else "▶  Play"
            cmd(if (paused) "resume" else "pause")
        }
        tr.addView(pp, lp(0, WRAP, 1.4f, l = dp(6), r = dp(6)))
        tr.addView(pill("30 ⏩", Pal.CARD, Pal.INK, 17f) { jump(30) }, lp(0, WRAP, 1f, l = dp(6)))
        ctl.addView(tr, lp(t = dp(14)))

        // volume of the movie, and the TV's own volume
        val vr = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        vr.addView(label("🔈", 18f))
        vol = SeekBar(this).apply { max = 100; progress = 100; tint(Pal.PURPLE) }
        vr.addView(vol, lp(0, dp(44), 1f))
        vr.addView(label("🔊", 18f))
        volRow = vr
        ctl.addView(vr, lp(t = dp(10)))
        vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private val send = Runnable { cmd("volume", "%.2f".format(java.util.Locale.US, vol.progress / 100.0)) }
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) { volTouched = System.currentTimeMillis(); main.removeCallbacks(send); main.postDelayed(send, 150) } }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val keys = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        keys.addView(label("TV volume", 14f, Pal.SUB), lp(0, WRAP, 1f))
        keys.addView(pill("−", Pal.CARD, Pal.INK, 20f) { cmd("voldown") }, lp(dp(64), WRAP, r = dp(8)))
        keys.addView(pill("+", Pal.CARD, Pal.INK, 20f) { cmd("volup") }, lp(dp(64), WRAP))
        tvKeys = keys
        ctl.addView(keys, lp(t = dp(8)))

        // fill screen, change movie, stop
        val acts = hbox()
        fill = pill("⛶  Fill", Pal.CARD, Pal.INK, 15f) { cmd("fill") }
        acts.addView(fill, lp(0, WRAP, 1f, r = dp(5)))
        acts.addView(pill("🎬  Change", Pal.PURPLE, sp = 15f) { picker.visibility = if (picker.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, lp(0, WRAP, 1f, l = dp(5), r = dp(5)))
        acts.addView(pill("⏹  Stop", 0xFFE85D5D.toInt(), sp = 15f) { cmd("stop"); main.postDelayed({ finish() }, 400) }, lp(0, WRAP, 1f, l = dp(5)))
        ctl.addView(acts, lp(t = dp(16)))
        body.addView(top, lp(t = dp(6)))

        // change movie: tap another DVD and it starts on the same TV
        val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; clipToPadding = false }
        val row = hbox().apply { setPadding(0, dp(12), 0, dp(12)) }
        Library.videos.values.sortedBy { it.title.lowercase() }.forEachIndexed { i, v ->
            val c = vbox()
            c.addView(DvdCase(this, v, Pal.TILE[i % Pal.TILE.size]).apply { isClickable = false }, lp(dp(96), WRAP))
            c.addView(label(v.title, 12f, Pal.INK, true, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) }, lp(dp(96), WRAP))
            c.setOnClickListener {
                val tvId = st.optString("tvId"); val tv = st.optString("tv", "the TV")
                if (tvId.isEmpty()) return@setOnClickListener
                strip.visibility = View.GONE
                toast("Starting ${v.title} on $tv…")
                Thread { val r = Cast.play(this, tvId, v.id); main.post { toast(if (r == "ok") "Playing ${v.title}" else r); poll() } }.start()
            }
            row.addView(c, lp(WRAP, WRAP, r = dp(12)))
        }
        strip.addView(row)
        picker = strip
        body.addView(strip, lp(MATCH, WRAP))
        tip = label("Tip: if the TV says to press OK for sound, press OK on its remote once.", 13f, Pal.SUB).apply { setPadding(dp(4), dp(10), dp(4), 0); visibility = View.GONE }
        body.addView(tip)
    }

    private fun jump(by: Int) = cmd("seek", "%.1f".format(java.util.Locale.US, maxOf(0.0, st.optDouble("pos", 0.0) + by)))

    private fun cmd(action: String, value: String? = null) {
        Thread {
            val r = Cast.control(action, value)
            main.post { if (r != "ok") toast(r); main.postDelayed({ poll() }, 400) }
        }.start()
    }

    private val tick = object : Runnable { override fun run() { poll(); main.postDelayed(this, 2000) } }

    private fun poll() {
        Thread {
            val s = try { Cast.status() } catch (_: Exception) { JSONObject() }
            main.post { if (!isFinishing && built) show(s) }
        }.start()
    }

    private fun show(s: JSONObject) {
        st = s
        val on = s.optBoolean("active")
        ctl.visibility = if (on) View.VISIBLE else View.GONE
        tip.visibility = ctl.visibility
        if (!on) {
            picker.visibility = View.GONE; coverBox.removeAllViews(); coverBox.visibility = View.GONE; coverFor = ""
            where.text = "Nothing is playing on a TV right now."
            title.text = "Open a DVD on the shelf and pick a TV to watch it there."
            title.textSize = 16f
            return
        }
        title.textSize = 20f
        val state = s.optString("state")
        where.text = (when (state) { "paused" -> "Paused on "; "buffering" -> "Loading on "; else -> "Playing on " }) + s.optString("tv", "the TV") +
            if (s.optBoolean("muted")) " · press OK on the TV remote for sound" else ""
        title.text = s.optString("title")
        val vid = s.optString("video")
        if (vid.isNotEmpty() && vid != coverFor) {
            Library.videos[vid]?.let { v -> coverFor = vid; coverBox.removeAllViews(); coverBox.visibility = View.VISIBLE; coverBox.addView(DvdCase(this, v, Pal.ORANGE).apply { isClickable = false }, lp(MATCH, WRAP)) }
        }
        val p = s.optDouble("pos", 0.0); val d = s.optDouble("dur", 0.0)
        if (!seeking) {
            pos.text = formatTime((p * 1000).toLong()); dur.text = if (d > 0) formatTime((d * 1000).toLong()) else ""
            seek.progress = if (d > 0) (1000 * p / d).toInt() else 0
        }
        if (System.currentTimeMillis() - volTouched > 4000 && s.has("vol")) vol.progress = (s.optDouble("vol", 1.0) * 100).toInt()
        val kind = s.optString("kind")
        val web = kind == "web" || kind == "firetv"
        volRow.visibility = if (kind == "dlna") View.GONE else View.VISIBLE
        tvKeys.visibility = if (web) View.VISIBLE else View.GONE
        fill.visibility = if (web) View.VISIBLE else View.GONE
        fill.text = if (s.optBoolean("fill")) "⛶  Fit" else "⛶  Fill"
        pp.text = if (state == "paused") "▶  Play" else "⏸  Pause"
    }

    override fun onResume() { super.onResume(); coverFor = ""; main.post(tick) }
    override fun onPause() { main.removeCallbacks(tick); super.onPause() }
}
