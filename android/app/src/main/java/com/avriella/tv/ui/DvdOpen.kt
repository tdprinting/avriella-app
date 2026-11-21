package com.avriella.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.avriella.tv.data.Covers

/**
 * Picking a movie: the DVD case flies to the middle, its front swings open on the spine,
 * the shiny disc slides out and spins. Then either the movie starts ([play]) or you choose
 * where the disc goes, her phone or a TV ([choose]), and it flies there.
 */
object DvdOpen {
    private const val TAG_OVERLAY = "dvd-open-overlay"

    /** Open the case and start the movie on this phone. */
    fun play(a: Activity, case: DvdCase, then: () -> Unit) = open(a, case, null, then)

    /** Open the case and ask: her phone ([here]) or one of the TVs on the Wi-Fi. */
    fun choose(a: Activity, case: DvdCase, here: () -> Unit) = open(a, case, here, null)

    private class Scene(
        val a: Activity, val case: DvdCase, val overlay: FrameLayout, val stage: FrameLayout, val front: ImageView,
        val disc: DiscView, val cover: Bitmap, val cw: Int, val ch: Int, val d: Int,
        val scale: Float, val tx: Float, val ty: Float, val left: Int, val top: Int,
    ) {
        var spin: ObjectAnimator? = null
        var gone = false
    }

    private fun open(a: Activity, case: DvdCase, here: (() -> Unit)?, then: (() -> Unit)?) {
        val root = a.window.decorView as ViewGroup
        if (root.findViewWithTag<View>(TAG_OVERLAY) != null) return
        val cover = snapshot(case)
        val loc = IntArray(2); case.getLocationInWindow(loc)
        val cw = case.width; val ch = case.height
        if (cw == 0 || cover == null) { (then ?: here)?.invoke(); return }

        val overlay = FrameLayout(a).apply { tag = TAG_OVERLAY; isClickable = true; setBackgroundColor(0x00000000); clipChildren = false }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // the open case: dark blue tray with the disc in it, and the front cover on top
        val stage = FrameLayout(a).apply { clipChildren = false; clipToPadding = false }
        overlay.addView(stage, FrameLayout.LayoutParams(cw, ch).apply { leftMargin = loc[0]; topMargin = loc[1] })
        stage.addView(View(a).apply { background = rounded(0xFF1B2E6B.toInt(), a.dpf(7f), a.dp(2), 0x55FFFFFF) }, FrameLayout.LayoutParams(cw, ch))
        val d = (cw * 0.86f).toInt()
        val disc = DiscView(a, cover, Covers.discBitmap(a, case.video.id))
        stage.addView(disc, FrameLayout.LayoutParams(d, d).apply { leftMargin = (cw - d) / 2; topMargin = (ch - d) / 2 })
        val front = ImageView(a).apply {
            setImageDrawable(BitmapDrawable(a.resources, cover)); scaleType = ImageView.ScaleType.FIT_XY
            pivotX = 0f; pivotY = ch / 2f
            cameraDistance = cw * 18f * a.resources.displayMetrics.density
        }
        stage.addView(front, FrameLayout.LayoutParams(cw, ch))

        // fly to the middle of the screen (a bit higher when we'll ask where it goes)
        val sw = root.width; val sh = root.height
        // sideways phone + chooser: the case on the left, the choices on the right
        val land = here != null && sw > sh
        val scale = when { land -> minOf(sw * 0.18f / cw, sh * 0.6f / ch); here != null -> minOf(sw * 0.34f / cw, sh * 0.34f / ch); else -> minOf(sw * 0.42f / cw, sh * 0.5f / ch) }
        stage.pivotX = 0f; stage.pivotY = 0f
        // centre the whole open case: the lid swung out on the left, the disc out on the right
        val tx = (if (land) sw * 0.27f - cw * scale * 0.37f else if (here != null) sw / 2f - cw * scale * 0.37f else (sw - cw * scale) / 2f - cw * scale * 0.18f) - loc[0]
        val ty = (if (land) (sh - ch * scale) / 2f + sh * 0.02f else if (here != null) sh * 0.36f - ch * scale / 2f else (sh - ch * scale) / 2f) - loc[1]
        case.alpha = 0f
        val sc = Scene(a, case, overlay, stage, front, disc, cover, cw, ch, d, scale, tx, ty, loc[0], loc[1])

        val dim = ValueAnimator.ofInt(0, 0xD0).apply { addUpdateListener { overlay.setBackgroundColor((it.animatedValue as Int) shl 24) } }
        val fly = AnimatorSet().apply {
            playTogether(dim,
                ObjectAnimator.ofFloat(stage, View.TRANSLATION_X, tx), ObjectAnimator.ofFloat(stage, View.TRANSLATION_Y, ty),
                ObjectAnimator.ofFloat(stage, View.SCALE_X, scale), ObjectAnimator.ofFloat(stage, View.SCALE_Y, scale))
            duration = 380; interpolator = DecelerateInterpolator()
        }
        val open = lidAnim(sc, 0f, -150f, 520)
        val out = ObjectAnimator.ofPropertyValuesHolder(disc,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, cw * 0.62f), PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -ch * 0.05f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.12f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.12f),
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 540f)).apply { duration = 650; interpolator = OvershootInterpolator(0.9f) }

        AnimatorSet().apply {
            playSequentially(fly, open, out)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(anim: Animator) {
                    if (then != null) {
                        ObjectAnimator.ofFloat(disc, View.ROTATION, 540f, 900f).apply {
                            duration = 380; interpolator = LinearInterpolator()
                            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(x: Animator) { then(); case.alpha = 1f } })
                            start()
                        }
                    } else {
                        sc.spin = ObjectAnimator.ofFloat(disc, View.ROTATION, 540f, 900f).apply {
                            duration = 2600; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE; start()
                        }
                        chooser(sc, here!!)
                    }
                }
            })
            start()
        }
        // never leave the overlay stuck if something goes wrong (the chooser waits for you instead)
        if (then != null) overlay.postDelayed({ if (overlay.parent != null && !a.isFinishing) clear(a) }, 6000)
    }

    /** Swings the lid; past halfway we see the inside of the lid (plain plastic), not the cover backwards. */
    private fun lidAnim(sc: Scene, from: Float, to: Float, ms: Long) = ObjectAnimator.ofFloat(sc.front, View.ROTATION_Y, from, to).apply {
        duration = ms; interpolator = AccelerateDecelerateInterpolator()
        var inside = from < -90f
        addUpdateListener {
            val now = sc.front.rotationY < -90f
            if (now != inside) {
                inside = now
                if (now) sc.front.setImageDrawable(android.graphics.drawable.LayerDrawable(arrayOf(
                    rounded(0xFF22397F.toInt(), sc.a.dpf(7f), sc.a.dp(2), 0x55FFFFFF),
                    rounded(0x22FFFFFF, sc.a.dpf(4f))
                )).apply { val m = sc.a.dp(10); setLayerInset(1, m, m, m, m) })
                else sc.front.setImageDrawable(BitmapDrawable(sc.a.resources, sc.cover))
            }
        }
    }

    /** "Where should it play?" Her phone, or any TV found on the Wi-Fi. */
    private fun chooser(sc: Scene, here: () -> Unit) {
        val a = sc.a
        val land = sc.overlay.width > sc.overlay.height
        val title = TextView(a).apply {
            text = sc.case.video.title; setTextColor(Color.WHITE); textSize = 22f; typeface = CHUNKY; gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, 0x99000000.toInt()); maxLines = 2; alpha = 0f
        }
        val panel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f; translationY = a.dpf(30f) }
        if (land) panel.addView(title.apply { alpha = 1f; setPadding(0, 0, 0, a.dp(18)) })
        else sc.overlay.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = a.dp(44); leftMargin = a.dp(20); rightMargin = a.dp(20) })
        panel.addView(TextView(a).apply { text = "Where should it play?"; setTextColor(Color.WHITE); textSize = 19f; typeface = CHUNKY; gravity = Gravity.CENTER })
        panel.addView(TextView(a).apply { text = "Send the DVD to her phone or a TV."; setTextColor(0xB3FFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, a.dp(4), 0, a.dp(14)) })
        // the choices, three to a row (there can be several TVs)
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rows, LinearLayout.LayoutParams(-1, -2))
        var dests = LinearLayout(a)
        fun dest(emoji: String, name: String, sub: String): LinearLayout {
            val b = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = rounded(0xFF2E3566.toInt(), a.dpf(22f), a.dp(1), 0x40FFFFFF)
                setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(12)); isClickable = true
                pressScale(0.94f)
            }
            b.addView(TextView(a).apply { text = emoji; textSize = 30f; gravity = Gravity.CENTER })
            b.addView(TextView(a).apply { text = name; setTextColor(Color.WHITE); textSize = 15f; typeface = CHUNKY; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, a.dp(4), 0, 0) })
            b.addView(TextView(a).apply { text = sub; setTextColor(0xA8FFFFFF.toInt()); textSize = 11.5f; gravity = Gravity.CENTER; maxLines = 1 })
            if (rows.childCount == 0 || dests.childCount >= 3) {
                dests = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                rows.addView(dests, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (rows.childCount > 0) a.dp(10) else 0 })
            }
            dests.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(5); rightMargin = a.dp(5) })
            return b
        }
        val phone = dest("📱", "Her phone", "Plays right here")
        phone.setOnClickListener { send(sc, phone) { here() } }
        val tv = dest("📺", "A TV", "Looking for TVs…").apply { alpha = 0.6f }
        tv.setOnClickListener { a.toast("Still looking for TVs on the Wi-Fi…") }
        Thread {
            val found = try { com.avriella.tv.cam.Cast.scan(a) } catch (_: Exception) { org.json.JSONArray() }
            a.runOnUiThread {
                if (sc.gone) return@runOnUiThread
                if (found.length() == 0) {
                    (tv.getChildAt(2) as TextView).text = "None found"
                    tv.setOnClickListener { a.toast("No TV found. On a Fire TV, open the Silk browser at this phone's address with /tv on the end.") }
                    return@runOnUiThread
                }
                for (i in 0 until minOf(6, found.length())) {
                    val t = found.getJSONObject(i)
                    val kind = when (t.optString("kind")) { "chromecast" -> "Chromecast"; "web", "firetv" -> "Fire TV"; else -> "Smart TV" }
                    val b = if (i == 0) tv.apply { alpha = 1f; (getChildAt(1) as TextView).text = t.optString("name"); (getChildAt(2) as TextView).text = kind }
                        else dest("📺", t.optString("name"), kind)
                    val tvId = t.optString("id"); val tvName = t.optString("name")
                    b.setOnClickListener {
                        send(sc, b) {
                            a.toast("Opening it on $tvName…")
                            Thread {
                                val r = com.avriella.tv.cam.Cast.play(a, tvId, sc.case.video.id)
                                a.runOnUiThread {
                                    a.toast(if (r == "ok") "Playing on $tvName" else r)
                                    if (r == "ok") a.startActivity(android.content.Intent(a, com.avriella.tv.TvRemoteActivity::class.java))
                                }
                            }.start()
                        }
                    }
                }
            }
        }.start()
        panel.addView(TextView(a).apply {
            text = "Put it back"; setTextColor(0xC8FFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(6)); setOnClickListener { putBack(sc) }
        }, LinearLayout.LayoutParams(-2, -2))
        sc.overlay.addView(panel, if (land) FrameLayout.LayoutParams((sc.overlay.width * 0.46f).toInt(), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = a.dp(28) }
            else FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = a.dp(30); leftMargin = a.dp(14); rightMargin = a.dp(14) })
        sc.overlay.setOnClickListener { putBack(sc) }
        title.animate().alpha(1f).setDuration(350).start()
        panel.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    /** Close it up again: disc back in, lid shut, case back on the shelf. */
    private fun putBack(sc: Scene) {
        if (sc.gone) return; sc.gone = true
        sc.spin?.cancel()
        for (i in sc.overlay.childCount - 1 downTo 0) sc.overlay.getChildAt(i).let { if (it !== sc.stage) it.animate().alpha(0f).setDuration(200).start() }
        val disc = ObjectAnimator.ofPropertyValuesHolder(sc.disc,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f), PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f)).apply { duration = 420; interpolator = AccelerateDecelerateInterpolator() }
        val shut = lidAnim(sc, -150f, 0f, 380)
        val undim = ValueAnimator.ofInt(0xD0, 0).apply { addUpdateListener { sc.overlay.setBackgroundColor((it.animatedValue as Int) shl 24) } }
        val home = AnimatorSet().apply {
            playTogether(undim,
                ObjectAnimator.ofFloat(sc.stage, View.TRANSLATION_X, 0f), ObjectAnimator.ofFloat(sc.stage, View.TRANSLATION_Y, 0f),
                ObjectAnimator.ofFloat(sc.stage, View.SCALE_X, 1f), ObjectAnimator.ofFloat(sc.stage, View.SCALE_Y, 1f))
            duration = 340; interpolator = DecelerateInterpolator()
        }
        AnimatorSet().apply {
            playSequentially(disc, shut, home)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(anim: Animator) { sc.case.alpha = 1f; (sc.overlay.parent as? ViewGroup)?.removeView(sc.overlay) }
            })
            start()
        }
    }

    /** The disc flies into the button you picked, then it plays there. */
    private fun send(sc: Scene, target: View, then: () -> Unit) {
        if (sc.gone) return; sc.gone = true
        val a = sc.a
        sc.spin?.cancel()
        // where the disc is on screen now
        val s = sc.scale
        val cx = sc.left + sc.tx + s * (sc.disc.left + sc.d / 2f + sc.disc.translationX)
        val cy = sc.top + sc.ty + s * (sc.disc.top + sc.d / 2f + sc.disc.translationY)
        val size = (sc.d * s * 1.12f).toInt()
        val fly = DiscView(a, sc.cover, Covers.discBitmap(a, sc.case.video.id)).apply { rotation = sc.disc.rotation }
        sc.overlay.addView(fly, FrameLayout.LayoutParams(size, size).apply { leftMargin = (cx - size / 2f).toInt(); topMargin = (cy - size / 2f).toInt() })
        sc.disc.visibility = View.INVISIBLE
        val r = Rect(); target.getGlobalVisibleRect(r)
        val o = IntArray(2); sc.overlay.getLocationOnScreen(o)
        val mx = r.exactCenterX() - o[0] - cx; val my = r.exactCenterY() - o[1] - cy
        fly.animate().translationX(mx).translationY(my).rotationBy(620f).scaleX(0.12f).scaleY(0.12f).alpha(0.2f)
            .setDuration(620).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                fly.visibility = View.GONE
                target.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).withEndAction {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
                sc.overlay.postDelayed({
                    then()
                    sc.case.alpha = 1f
                    sc.overlay.animate().alpha(0f).setDuration(300).withEndAction { (sc.overlay.parent as? ViewGroup)?.removeView(sc.overlay) }.start()
                }, 220)
            }.start()
    }

    /** Removes the animation (when coming back from the movie). */
    fun clear(a: Activity) {
        val root = a.window.decorView as ViewGroup
        root.findViewWithTag<View>(TAG_OVERLAY)?.let { root.removeView(it) }
    }

    private fun snapshot(v: View): Bitmap? = try {
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    } catch (e: Exception) { android.util.Log.w("AvriellaDvd", "snapshot failed", e); null }

    /** The disc: its printed art if her phone has made it, or a shiny disc with the cover on the label. */
    private class DiscView(ctx: Context, private val art: Bitmap, private val printed: Bitmap?) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width / 2f; val cx = r; val cy = r
            p.setShadowLayer(r * 0.08f, 0f, r * 0.04f, 0x88000000.toInt())
            p.color = 0xFFD9DEE8.toInt(); c.drawCircle(cx, cy, r * 0.96f, p)
            p.clearShadowLayer()
            if (printed != null) {
                c.drawBitmap(printed, null, RectF(cx - r * 0.97f, cy - r * 0.97f, cx + r * 0.97f, cy + r * 0.97f), p)
                return
            }
            // silver with a rainbow sheen
            p.shader = SweepGradient(cx, cy, intArrayOf(0xFFE8ECF4.toInt(), 0xFFB9F0FF.toInt(), 0xFFF6C8FF.toInt(), 0xFFFFF2B8.toInt(),
                0xFFE8ECF4.toInt(), 0xFFC8FFD8.toInt(), 0xFFB9D0FF.toInt(), 0xFFE8ECF4.toInt()), null)
            c.drawCircle(cx, cy, r * 0.96f, p)
            // the printed label: the cover art, round
            val labelR = r * 0.78f
            val sh = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = (labelR * 2) / minOf(art.width, art.height)
            sh.setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(cx - art.width * s / 2, cy - art.height * s * 0.42f) })
            p.shader = sh; c.drawCircle(cx, cy, labelR, p)
            // clear hub
            p.shader = RadialGradient(cx, cy, r * 0.26f, intArrayOf(0xFFF4F6FA.toInt(), 0xFFC9CED8.toInt()), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 0.26f, p)
            p.shader = null
            p.color = 0xFF2A2F45.toInt(); c.drawCircle(cx, cy, r * 0.09f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.012f; p.color = 0x55FFFFFF
            c.drawCircle(cx, cy, r * 0.17f, p); c.drawCircle(cx, cy, labelR, p)
            p.style = Paint.Style.FILL
            p.color = 0x40FFFFFF
            c.drawArc(RectF(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f), 200f, 40f, true, p)
            p.color = Color.TRANSPARENT
        }
    }
}
