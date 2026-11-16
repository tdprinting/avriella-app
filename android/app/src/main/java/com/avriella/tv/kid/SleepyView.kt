package com.avriella.tv.kid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.avriella.tv.games.Shapes
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Calm "night night" screen shown when the sleep timer ends or the playlist is finished. */
class SleepyView(ctx: Context) : View(ctx) {
    var title = "Night night"
    var subtitle = ""
    private val bg = Paint()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val moon = Path(); private val cut = Path()
    private var stars = FloatArray(0)
    private var t = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0E1440.toInt(), 0xFF3A2466.toInt(), Shader.TileMode.CLAMP)
        stars = FloatArray(40 * 3) { i -> when (i % 3) { 0 -> Random.nextFloat() * w; 1 -> Random.nextFloat() * h; else -> Random.nextFloat() * 6f } }
    }

    override fun onDraw(c: Canvas) {
        t += 1 / 60f
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        p.color = Color.WHITE
        val d = resources.displayMetrics.density
        for (i in stars.indices step 3) {
            p.alpha = (110 + 100 * sin(t * 0.8f + stars[i + 2])).toInt().coerceIn(20, 230)
            Shapes.draw(c, Shapes.STAR, stars[i], stars[i + 1], 4 * d, p)
        }
        val r = min(w, h) * 0.16f
        val mx = w / 2; val my = h * 0.36f
        moon.reset(); moon.addCircle(mx, my, r, Path.Direction.CW)
        cut.reset(); cut.addCircle(mx + r * 0.45f, my - r * 0.25f, r * 0.85f, Path.Direction.CW)
        moon.op(cut, Path.Op.DIFFERENCE)
        p.alpha = 255; p.color = 0xFFFFE08A.toInt()
        c.drawPath(moon, p)
        text.textSize = 34 * resources.displayMetrics.scaledDensity
        c.drawText(title, w / 2, h * 0.72f, text)
        if (subtitle.isNotEmpty()) {
            text.textSize = 18 * resources.displayMetrics.scaledDensity
            text.alpha = 190
            c.drawText(subtitle, w / 2, h * 0.72f + 40 * d, text)
            text.alpha = 255
        }
        if (visibility == VISIBLE) postInvalidateDelayed(50)
    }
}
