package com.avriella.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.avriella.tv.data.Covers
import com.avriella.tv.data.Thumbs
import com.avriella.tv.data.Video

/**
 * A DVD case: the movie's cover in a plastic case, with the hinged spine on the left and a
 * shine across the front. Without a cover yet, it shows a picture from the video and its title.
 */
class DvdCase(ctx: Context, val video: Video, color: Int) : FrameLayout(ctx) {
    private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val fallbackTitle = TextView(ctx).apply {
        text = video.title; setTextColor(Color.WHITE); textSize = 15f; typeface = CHUNKY
        gravity = Gravity.CENTER; maxLines = 4; setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        setPadding(ctx.dp(12), 0, ctx.dp(8), ctx.dp(14)); visibility = GONE
    }
    private val bg = color

    init {
        setBackgroundColor(blend(color, Color.BLACK, 0.35f))
        addView(img, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fallbackTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(CaseShine(ctx), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, ctx.dpf(7f)) }
        }
        elevation = ctx.dpf(8f)
        Covers.load(ctx, video, img) {
            // no cover: a picture from the video, fitted into a colored case, with the title
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(bg)
            fallbackTitle.visibility = VISIBLE
            Thumbs.load(ctx, video, img)
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = w * 3 / 2 // DVD cases are tall: 2 wide by 3 high
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** The case stands on the shelf: scaling and turning happen from its bottom edge. */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); pivotX = w / 2f; pivotY = h.toFloat() }

    /** The plastic: spine with its hinge ridges, a thin clear rim, and light catching the front. */
    private class CaseShine(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val spine = w * 0.075f
            p.shader = LinearGradient(0f, 0f, spine, 0f,
                intArrayOf(0xEE0E0F1A.toInt(), 0x99464B66.toInt(), 0xCC12131F.toInt(), 0x33000000), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, spine, h, p)
            p.shader = null
            p.color = 0x22FFFFFF; p.strokeWidth = w * 0.006f
            c.drawLine(spine * 0.45f, 0f, spine * 0.45f, h, p)
            // light sweeping across the front
            p.shader = LinearGradient(0f, 0f, w, h * 0.8f,
                intArrayOf(0x40FFFFFF, 0x10FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x14FFFFFF), floatArrayOf(0f, 0.22f, 0.4f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            // clear plastic rim
            p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.02f; p.color = 0x33FFFFFF
            val r = w * 0.04f
            c.drawRoundRect(RectF(p.strokeWidth / 2, p.strokeWidth / 2, w - p.strokeWidth / 2, h - p.strokeWidth / 2), r, r, p)
            p.style = Paint.Style.FILL
        }
    }
}

/**
 * Her movies on a 3D shelf: swipe through the DVDs; the one in the middle comes forward and faces you,
 * the others turn away on either side. Tap the middle one to open it, or tap another to bring it over.
 */
class DvdShelf(ctx: Context, private val videos: List<Video>, private val onMid: (Int) -> Unit, private val onPick: (DvdCase, Video) -> Unit) : FrameLayout(ctx) {
    private val itemW = ctx.dp(132)
    private val row = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL; clipChildren = false }
    private val scroller = object : android.widget.HorizontalScrollView(ctx) {
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            val side = (w - itemW) / 2 + ctx.dp(12)
            row.setPadding(side, ctx.dp(18), side, ctx.dp(30))
            post { scrollTo(centerOf(mid), 0); paint() }
        }
    }.apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false }
    val cases = ArrayList<DvdCase>()
    var mid = 0; private set
    private val settle = Runnable { snap() }

    init {
        clipChildren = false
        addView(Plank(ctx), LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(46), android.view.Gravity.BOTTOM))
        scroller.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        videos.forEachIndexed { i, v ->
            val c = DvdCase(ctx, v, Pal.TILE[i % Pal.TILE.size]).apply { cameraDistance = ctx.dp(132) * 14f * ctx.resources.displayMetrics.density }
            c.setOnClickListener { if (i == mid) onPick(c, v) else scroller.smoothScrollTo(centerOf(i), 0) }
            cases += c
            row.addView(c, android.widget.LinearLayout.LayoutParams(itemW, LayoutParams.WRAP_CONTENT).apply { leftMargin = -ctx.dp(12); rightMargin = -ctx.dp(12) })
        }
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> paint(); removeCallbacks(settle); postDelayed(settle, 140) }
        scroller.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) removeCallbacks(settle)
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) { removeCallbacks(settle); postDelayed(settle, 140) }
            false
        }
    }

    /** Start with this DVD in the middle. */
    fun showAt(i: Int) { mid = i.coerceIn(0, maxOf(0, videos.size - 1)); scroller.post { scroller.scrollTo(centerOf(mid), 0); paint() } }

    private fun centerOf(i: Int): Int {
        val c = row.getChildAt(i) ?: return 0
        return c.left + c.width / 2 - scroller.width / 2
    }

    private fun snap() { val t = centerOf(mid); if (kotlin.math.abs(scroller.scrollX - t) > 2) scroller.smoothScrollTo(t, 0) }

    private fun paint() {
        val center = scroller.scrollX + scroller.width / 2f
        var best = 0; var bestD = Float.MAX_VALUE
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            val d = (c.left + c.width / 2f - center) / c.width
            val a = d.coerceIn(-1f, 1f); val ad = kotlin.math.abs(d)
            if (ad < bestD) { bestD = ad; best = i }
            c.rotationY = a * 52f
            val s = 1f - minOf(ad, 2.5f) * 0.11f
            c.scaleX = s; c.scaleY = s
            c.translationX = -a * c.width * 0.16f
            c.translationZ = (3f - minOf(ad, 3f)) * context.dpf(3f)
            c.alpha = 1f - minOf(ad, 3f) * 0.12f
        }
        if (best != mid || bestD < 0.5f) { if (best != mid) { mid = best; onMid(best) } }
    }

    /** The wooden shelf they stand on, drawn going back into the screen. */
    private class Plank(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val top = h * 0.62f; val inset = w * 0.07f
            path.reset(); path.moveTo(inset, 0f); path.lineTo(w - inset, 0f); path.lineTo(w, top); path.lineTo(0f, top); path.close()
            p.shader = LinearGradient(0f, 0f, 0f, top, intArrayOf(0xFF5C3519.toInt(), 0xFF94602F.toInt(), 0xFFC48A4D.toInt(), 0xFFDBA56A.toInt()),
                floatArrayOf(0f, 0.35f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            // a little wood grain
            p.shader = null; p.color = 0x14000000; p.strokeWidth = h * 0.02f
            var x = w * 0.04f; var k = 0
            while (x < w) { c.drawLine(inset + (x - inset) * (1 - 0f), 0f, x, top, p); x += w * (0.09f + (k++ % 3) * 0.03f) }
            // the front edge
            p.shader = LinearGradient(0f, top, 0f, h, intArrayOf(0xFFC38548.toInt(), 0xFF8D5A2B.toInt(), 0xFF6A3F1C.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, top, w, h, p)
            p.shader = null; p.color = 0x59FFDCAA; c.drawRect(0f, top, w, top + h * 0.04f, p)
        }
    }
}
