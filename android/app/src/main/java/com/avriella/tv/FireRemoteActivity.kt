package com.avriella.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.avriella.tv.cam.FireRemote
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.card
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.vbox
import org.json.JSONArray
import java.util.concurrent.Executors

/** A backup remote for the Fire TVs: arrows, OK, back, home, play/pause, volume, power, typing and apps. */
class FireRemoteActivity : ParentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val line = Executors.newSingleThreadExecutor()   // presses go out one after another, in order
    @Volatile private var busy = 0
    private var host = ""
    private lateinit var msg: TextView
    private lateinit var tvRow: LinearLayout
    private lateinit var apps: LinearLayout

    override fun build() {
        titleView.text = "🎮 TV remote"
        tvRow = hbox()
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tvRow) }, lp(MATCH, WRAP, t = dp(4)))
        msg = label("Looking for Fire TVs…", 13f, Pal.SUB).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        body.addView(msg)

        val c = card(18)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(row3(round("⭕", "Power") { key("power") }, round("⌂", "Home") { key("home") }, round("☰", "Options") { key("menu") }))
        c.addView(Dpad(this) { k -> key(k) }, lp(dp(250), dp(250), t = dp(14), b = dp(14)))
        c.addView(row3(round("↩", "Back") { key("back") }, round("⏯", "Play", Pal.ORANGE, Color.WHITE) { key("playpause") }, round("🔇", "Mute") { key("mute") }))
        val vol = hbox().apply { gravity = Gravity.CENTER }
        vol.addView(round("⏪", "", hold = true) { key("rew") })
        vol.addView(pill("−  VOL", Pal.CARD, Pal.INK, 18f) {}.also { holdable(it) { key("voldown") } }, lp(0, dp(62), 1f, l = dp(12), r = dp(6)))
        vol.addView(pill("VOL  +", Pal.CARD, Pal.INK, 18f) {}.also { holdable(it) { key("volup") } }, lp(0, dp(62), 1f, l = dp(6), r = dp(12)))
        vol.addView(round("⏩", "", hold = true) { key("ff") })
        c.addView(vol, lp(MATCH, WRAP, t = dp(14)))
        body.addView(c, lp(t = dp(6)))

        // type on the TV
        val type = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "Type on the TV (search…)"; textSize = 16f; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply { setColor(Pal.CARD); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun sendText() {
            val t = input.text.toString().trim(); if (t.isEmpty()) return
            input.setText(""); send { FireRemote.text(this, host, t) }
        }
        input.setOnEditorActionListener { _, _, _ -> sendText(); true }
        type.addView(input, lp(0, WRAP, 1f))
        type.addView(pill("⌫", Pal.CARD, Pal.INK, 17f) { key("del") }, lp(dp(58), WRAP, l = dp(8)))
        type.addView(pill("Send", Pal.PURPLE, sp = 16f) { sendText() }, lp(WRAP, WRAP, l = dp(8)))
        body.addView(type, lp(t = dp(14)))

        apps = vbox()
        body.addView(apps, lp(t = dp(12)))
        loadTvs(false)
    }

    // ---------- talking to the TV ----------

    private fun key(k: String) { send { FireRemote.key(this, host, k) } }

    private fun send(job: () -> String) {
        if (host.isEmpty()) { say("Pick a TV first.", true); return }
        if (busy > 3) return
        busy++
        main.postDelayed({ if (busy > 0) say("Connecting to the TV… if it asks \"Allow debugging?\", press OK on its remote.", false) }, 1500)
        line.execute {
            val r = try { job() } catch (e: Exception) { "The TV didn't answer." }
            main.post { busy--; if (r == "ok") say("", false) else say(r, true) }
        }
    }

    private fun say(t: String, err: Boolean) { msg.text = t; msg.setTextColor(if (err) 0xFFD84A4A.toInt() else Pal.SUB) }

    private fun loadTvs(fresh: Boolean) {
        say("Looking for Fire TVs…", false)
        Thread {
            val list = try { FireRemote.tvs(this, fresh) } catch (_: Exception) { JSONArray() }
            main.post { if (!isFinishing) showTvs(list) }
        }.start()
    }

    private fun showTvs(list: JSONArray) {
        tvRow.removeAllViews()
        if (list.length() == 0) say("No Fire TVs found. Make sure they're on and on the same Wi-Fi as her phone.", true) else say("", false)
        val saved = getSharedPreferences("remote", Context.MODE_PRIVATE).getString("host", "") ?: ""
        var pickHost = ""
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i); val h = t.getString("host")
            if (h == saved || (pickHost.isEmpty() && i == 0)) pickHost = h
        }
        if (host.isNotEmpty() && (0 until list.length()).any { list.getJSONObject(it).getString("host") == host }) pickHost = host
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i); val h = t.getString("host")
            val on = h == pickHost
            tvRow.addView(pill(t.getString("name"), if (on) Pal.ORANGE else Pal.CARD, if (on) Color.WHITE else Pal.INK, 15f) { pick(h); showTvs(list) }, lp(WRAP, WRAP, r = dp(8)))
        }
        tvRow.addView(pill("↻", Pal.CARD, Pal.INK, 16f) { loadTvs(true) }, lp(WRAP, WRAP))
        if (pickHost.isNotEmpty() && pickHost != host) pick(pickHost)
    }

    private fun pick(h: String) {
        host = h
        getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putString("host", h).apply()
        line.execute { try { FireRemote.warm(this, h) } catch (_: Exception) {} }   // connect now so the first press is instant
        apps.removeAllViews()
        val grid = mutableListOf<View>(pill("📀  Her DVD shelf", Pal.ORANGE, Color.WHITE, 15f) { send { FireRemote.shelf(this, host) } })
        layoutApps(grid)
        Thread {
            val list = try { FireRemote.apps(this, h) } catch (_: Exception) { JSONArray() }
            main.post {
                if (host != h || isFinishing) return@post
                for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i); val pkg = a.getString("pkg")
                    grid.add(pill(a.getString("name"), Pal.CARD, Pal.INK, 15f) { send { FireRemote.launch(this, host, pkg) } })
                }
                layoutApps(grid)
            }
        }.start()
    }

    private fun layoutApps(items: List<View>) {
        apps.removeAllViews()
        items.forEach { (it.parent as? LinearLayout)?.removeView(it) }
        items.chunked(3).forEach { chunk ->
            val r = hbox()
            chunk.forEachIndexed { i, v -> r.addView(v, lp(0, WRAP, 1f, l = if (i > 0) dp(5) else 0, r = if (i < 2) dp(5) else 0)) }
            repeat(3 - chunk.size) { r.addView(View(this), lp(0, 1, 1f)) }
            apps.addView(r, lp(t = dp(8)))
        }
    }

    // ---------- buttons ----------

    private fun row3(a: View, b: View, c: View) = hbox().apply {
        gravity = Gravity.CENTER
        listOf(a, b, c).forEach { v -> addView(LinearLayout(context).apply { gravity = Gravity.CENTER; addView(v) }, lp(0, WRAP, 1f)) }
    }

    private fun round(icon: String, name: String, bg: Int = Pal.CARD, fg: Int = Pal.INK, hold: Boolean = false, onTap: () -> Unit): View {
        val size = if (name.isEmpty()) dp(58) else dp(74)
        val v = vbox().apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg); setStroke(dp(1), 0x14000000) }
            elevation = dp(4).toFloat()
            addView(TextView(context).apply { text = icon; textSize = if (name.isEmpty()) 20f else 22f; setTextColor(if (icon == "⭕") 0xFFE85D5D.toInt() else fg); gravity = Gravity.CENTER })
            if (name.isNotEmpty()) addView(TextView(context).apply { text = name; textSize = 10f; setTextColor(if (bg == Pal.CARD) Pal.SUB else fg); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        holdable(v, hold, onTap)
        return v
    }

    /** Press on touch-down (feels instant); arrows and volume keep going while held. */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdable(v: View, repeat: Boolean = true, onPress: () -> Unit) {
        val again = object : Runnable { override fun run() { onPress(); main.postDelayed(this, 170) } }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).start()
                    onPress(); if (repeat) main.postDelayed(again, 420)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(again); v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            true
        }
    }

    override fun onDestroy() { line.shutdownNow(); super.onDestroy() }

    /** The round arrow pad with OK in the middle. Tap a side, hold it to keep moving, or swipe across it. */
    private class Dpad(ctx: Context, val onKey: (String) -> Unit) : View(ctx) {
        private val h = Handler(Looper.getMainLooper())
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.CARD }
        private val ringEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x18000000 }
        private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.ORANGE }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.INK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val okText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FF9A3C }
        private var down = ""
        private var sx = 0f; private var sy = 0f; private var swiped = false; private var first = ""
        private val again = object : Runnable { override fun run() { if (down != "" && down != "ok") { onKey(down); h.postDelayed(this, 170) } } }

        init { elevation = 6f * ctx.resources.displayMetrics.density; outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) } } }

        override fun onDraw(c: Canvas) {
            val r = width / 2f; val cx = r; val cy = height / 2f
            c.drawCircle(cx, cy, r - 2, ring); ringEdge.strokeWidth = 2f; c.drawCircle(cx, cy, r - 2, ringEdge)
            if (down != "" && down != "ok") {
                val a = when (down) { "up" -> 225f; "right" -> 315f; "down" -> 45f; else -> 135f }
                c.drawArc(cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2, a, 90f, true, lit)
            }
            arrow.strokeWidth = r * 0.045f
            val d = r * 0.72f; val s = r * 0.1f
            fun chev(x: Float, y: Float, dx: Float, dy: Float) {
                val p = Path(); p.moveTo(x - dx * s - dy * s, y - dy * s - dx * s); p.lineTo(x, y); p.lineTo(x - dx * s + dy * s, y - dy * s + dx * s); c.drawPath(p, arrow)
            }
            chev(cx, cy - d, 0f, -1f); chev(cx, cy + d, 0f, 1f); chev(cx - d, cy, -1f, 0f); chev(cx + d, cy, 1f, 0f)
            val okR = r * 0.36f
            okPaint.alpha = if (down == "ok") 200 else 255
            c.drawCircle(cx, cy, okR, okPaint)
            okText.textSize = r * 0.17f
            c.drawText("OK", cx, cy + okText.textSize * 0.35f, okText)
        }

        private fun zone(x: Float, y: Float): String {
            val dx = x - width / 2f; val dy = y - height / 2f
            if (Math.hypot(dx.toDouble(), dy.toDouble()) < width * 0.18) return "ok"
            return if (Math.abs(dx) > Math.abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    sx = e.x; sy = e.y; swiped = false
                    down = zone(e.x, e.y); first = down; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    // arrows move straight away; OK waits for the finger to lift, so a swipe from the middle never selects
                    if (down != "ok") { onKey(down); h.postDelayed(again, 420) }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    // a swipe across the pad moves one step per finger-width, like a touch remote
                    val dx = e.x - sx; val dy = e.y - sy; val step = width * 0.16f
                    if (Math.abs(dx) > step || Math.abs(dy) > step) {
                        h.removeCallbacks(again); swiped = true
                        val k = if (Math.abs(dx) > Math.abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
                        sx = e.x; sy = e.y; down = k; onKey(k); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(again)
                    if (e.actionMasked == MotionEvent.ACTION_UP && first == "ok" && !swiped) onKey("ok")
                    down = ""; invalidate()
                }
            }
            return true
        }
    }
}
