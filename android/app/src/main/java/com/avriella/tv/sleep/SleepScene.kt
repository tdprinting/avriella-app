package com.avriella.tv.sleep

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

/** Slow, dark, gentle pictures to go with the sleep sounds. Nothing flashes or moves fast. */
class SleepScene(ctx: Context) : View(ctx) {
    companion object {
        const val OCEAN = 0; const val RAIN = 1; const val WATER = 2; const val FOREST = 3; const val NIGHT = 4; const val WOMB = 5
    }

    var theme = NIGHT
        set(v) { field = v; setupColors() }
    var label = ""
    var paused = false

    private val bg = Paint()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path(); private val cut = Path()
    private var t = 0f
    private val rnd = Random(7)
    private var stars = FloatArray(0)
    private var drops = FloatArray(0)
    private var flies = FloatArray(0)
    private val d = resources.displayMetrics.density

    private fun setupColors() {
        if (width == 0) return
        val (a, b) = when (theme) {
            OCEAN -> 0xFF06163A.toInt() to 0xFF0B3E6B.toInt()
            RAIN -> 0xFF0B1022.toInt() to 0xFF1C2747.toInt()
            WATER -> 0xFF04202A.toInt() to 0xFF0B4A4F.toInt()
            FOREST -> 0xFF020D08.toInt() to 0xFF0E2A1A.toInt()
            WOMB -> 0xFF1A0612.toInt() to 0xFF4A1430.toInt()
            else -> 0xFF0A0E2E.toInt() to 0xFF2A1A52.toInt()
        }
        bg.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), a, b, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        stars = FloatArray(50 * 3) { i -> when (i % 3) { 0 -> rnd.nextFloat() * w; 1 -> rnd.nextFloat() * h * 0.7f; else -> rnd.nextFloat() * 6f } }
        drops = FloatArray(70 * 3) { i -> when (i % 3) { 0 -> rnd.nextFloat() * w; 1 -> rnd.nextFloat() * h; else -> 0.6f + rnd.nextFloat() * 0.8f } }
        flies = FloatArray(16 * 3) { i -> when (i % 3) { 0 -> rnd.nextFloat() * w; 1 -> h * (0.35f + rnd.nextFloat() * 0.55f); else -> rnd.nextFloat() * 6f } }
        setupColors()
    }

    override fun onDraw(c: Canvas) {
        t += 1 / 30f
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        when (theme) {
            OCEAN -> { drawStars(c, 0.5f); drawMoon(c, w * 0.78f, h * 0.2f, min(w, h) * 0.07f); drawWaves(c, w, h) }
            RAIN -> drawRain(c, w, h)
            WATER -> { drawStars(c, 0.4f); drawFlow(c, w, h) }
            FOREST -> { drawStars(c, 0.5f); drawTrees(c, w, h); drawFireflies(c) }
            WOMB -> drawGlow(c, w, h)
            else -> { drawStars(c, 1f); drawMoon(c, w * 0.5f, h * 0.36f, min(w, h) * 0.13f) }
        }
        if (label.isNotEmpty()) {
            text.textSize = 15 * resources.displayMetrics.scaledDensity
            text.alpha = 90
            c.drawText(label, w / 2, h - 28 * d, text)
        }
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateDelayed(33)
    }

    private fun drawStars(c: Canvas, amount: Float) {
        p.color = Color.WHITE
        val n = (stars.size / 3 * amount).toInt()
        for (k in 0 until n) {
            val i = k * 3
            p.alpha = (70 + 70 * sin(t * 0.6f + stars[i + 2])).toInt().coerceIn(10, 160)
            Shapes.draw(c, Shapes.STAR, stars[i], stars[i + 1], 3.2f * d, p)
        }
        p.alpha = 255
    }

    private fun drawMoon(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset(); path.addCircle(x, y, r, Path.Direction.CW)
        cut.reset(); cut.addCircle(x + r * 0.45f, y - r * 0.25f, r * 0.85f, Path.Direction.CW)
        path.op(cut, Path.Op.DIFFERENCE)
        p.color = 0xFFFFE6A0.toInt(); p.alpha = 200
        c.drawPath(path, p)
        p.alpha = 255
    }

    private fun drawWaves(c: Canvas, w: Float, h: Float) {
        val layers = intArrayOf(0x553A7BD5, 0x664A90E2, 0x88326FB0.toInt())
        for (k in 0 until 3) {
            val base = h * (0.62f + k * 0.11f)
            val amp = h * 0.025f * (1 + k * 0.4f)
            path.reset(); path.moveTo(0f, h)
            var x = 0f
            while (x <= w + 10) {
                val y = base + sin(x / w * 6.3f * (1.2f + k * 0.3f) + t * (0.35f + k * 0.12f)) * amp
                path.lineTo(x, y); x += 12f
            }
            path.lineTo(w, h); path.close()
            p.color = layers[k]
            c.drawPath(path, p)
        }
    }

    private fun drawRain(c: Canvas, w: Float, h: Float) {
        p.color = 0x55A9C4FF; p.strokeWidth = 1.6f * d; p.strokeCap = Paint.Cap.ROUND
        for (i in drops.indices step 3) {
            drops[i + 1] += drops[i + 2] * 6f * d
            if (drops[i + 1] > h) { drops[i + 1] = -20f; drops[i] = rnd.nextFloat() * w }
            c.drawLine(drops[i], drops[i + 1], drops[i] - 2 * d, drops[i + 1] + 14 * d * drops[i + 2], p)
        }
        // soft cloud
        p.color = 0x22FFFFFF
        c.drawCircle(w * 0.3f, h * 0.05f, w * 0.25f, p); c.drawCircle(w * 0.7f, h * 0.02f, w * 0.3f, p)
    }

    private fun drawFlow(c: Canvas, w: Float, h: Float) {
        p.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 9) {
            val x = w * (0.1f + k * 0.1f)
            val off = ((t * 40 * d + k * 77) % (h * 1.4f)) - h * 0.2f
            p.color = 0x334FD1C5; p.strokeWidth = (10 + k % 3 * 6) * d
            c.drawLine(x, off, x, off + h * 0.25f, p)
        }
    }

    private fun drawTrees(c: Canvas, w: Float, h: Float) {
        p.color = 0xFF04140B.toInt()
        for (k in 0 until 7) {
            val x = w * (k / 6f)
            val th = h * (0.32f + (k * 37 % 5) * 0.04f)
            path.reset(); path.moveTo(x - th * 0.28f, h); path.lineTo(x, h - th); path.lineTo(x + th * 0.28f, h); path.close()
            c.drawPath(path, p)
        }
    }

    private fun drawFireflies(c: Canvas) {
        for (i in flies.indices step 3) {
            val x = flies[i] + sin(t * 0.3f + flies[i + 2]) * 30 * d
            val y = flies[i + 1] + sin(t * 0.4f + flies[i + 2] * 2) * 20 * d
            val a = ((sin(t * 0.9f + flies[i + 2]) + 1) / 2)
            p.color = 0xFFE8FF8A.toInt(); p.alpha = (40 + 150 * a).toInt()
            c.drawCircle(x, y, 2.6f * d, p)
            p.alpha = (20 * a).toInt(); c.drawCircle(x, y, 10 * d, p)
        }
        p.alpha = 255
    }

    private fun drawGlow(c: Canvas, w: Float, h: Float) {
        val beat = (sin(t * 2 * Math.PI.toFloat() * 68f / 60f) + 1) / 2
        val r = min(w, h) * (0.28f + 0.02f * beat)
        for (k in 4 downTo 1) {
            p.color = 0xFFFF6B9A.toInt(); p.alpha = (10 + 8 * k * (0.6f + 0.4f * beat)).toInt()
            c.drawCircle(w / 2, h / 2, r * (1 + k * 0.35f), p)
        }
        p.alpha = 70; c.drawCircle(w / 2, h / 2, r, p)
        p.alpha = 255
    }
}
