package com.avriella.tv.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================
// Finger Paint – rainbow strokes, sparkles and notes that follow her finger.
// =====================================================================
class PaintGame(ctx: Context) : GameView(ctx) {
    private var bmp: Bitmap? = null
    private var canvas: Canvas? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 30 * dpf
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val last = HashMap<Int, PointF>()
    private val travel = HashMap<Int, Float>()
    private val sparkles = Particles()
    private var hue = 0f
    private var lastSound = 0L
    private val bg = 0xFFFFFBF2.toInt()
    override val canClear = true

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0) return
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val nc = Canvas(nb)
        nc.drawColor(bg)
        bmp?.let { nc.drawBitmap(it, 0f, 0f, null) }
        bmp = nb; canvas = nc
    }

    override fun clear() {
        canvas?.drawColor(bg)
        Sounds.play("whoosh")
        sparkles.burst(width / 2f, height / 2f, 30, BRIGHT, 700 * dpf, 12 * dpf, 0f, intArrayOf(Shapes.STAR), 1.0f)
    }

    private fun color(id: Int) = Color.HSVToColor(floatArrayOf((hue + id * 70) % 360, 0.75f, 1f))
    private fun noteFor(y: Float) = ((1 - y / height) * (Sounds.noteCount - 0.01f)).toInt().coerceIn(0, Sounds.noteCount - 1)

    override fun step(dt: Float) { hue = (hue + dt * 45) % 360; sparkles.update(dt) }

    override fun render(c: Canvas) {
        bmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        sparkles.draw(c)
    }

    override fun onTap(x: Float, y: Float, id: Int) {
        last[id] = PointF(x, y); travel[id] = 0f
        dot.color = color(id)
        canvas?.drawCircle(x, y, stroke.strokeWidth / 2, dot)
        sparkles.burst(x, y, 7, intArrayOf(color(id), Color.WHITE), 200 * dpf, 8 * dpf, 0f, intArrayOf(Shapes.STAR), 0.7f)
        Sounds.note(noteFor(y))
    }

    override fun onDrag(x: Float, y: Float, id: Int) {
        val p = last[id] ?: run { onTap(x, y, id); return }
        val d = hypot(x - p.x, y - p.y)
        if (d < 1f) return
        stroke.color = color(id)
        canvas?.drawLine(p.x, p.y, x, y, stroke)
        p.set(x, y)
        val tr = (travel[id] ?: 0f) + d
        if (tr > 40 * dpf) {
            travel[id] = 0f
            sparkles.burst(x, y, 2, intArrayOf(color(id), Color.WHITE), 90 * dpf, 6 * dpf, 0f, intArrayOf(Shapes.STAR, Shapes.HEART), 0.6f)
        } else travel[id] = tr
        val now = SystemClock.uptimeMillis()
        if (now - lastSound > 170) { lastSound = now; Sounds.note(noteFor(y), 0.35f) }
    }

    override fun onLift(id: Int) { last.remove(id); travel.remove(id) }
}

// =====================================================================
// Bubble Pop – smiley bubbles float up; any tap pops one or blows a new one.
// =====================================================================
class BubbleGame(ctx: Context) : GameView(ctx) {
    private class Bubble(var x: Float, var y: Float, val r: Float, val speed: Float, val phase: Float, val color: Int, val face: Boolean) { var t = 0f }

    private val bubbles = ArrayList<Bubble>()
    private val parts = Particles()
    private var spawnT = 0f
    private val bgPaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2845.toInt() }
    private val smile = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF2B2845.toInt() }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FF6B9A }
    private val oval = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFBFE6FF.toInt(), 0xFFFFE9F3.toInt(), Shader.TileMode.CLAMP)
    }

    private fun spawn(x: Float = Random.nextFloat() * width, y: Float = height + 80 * dpf, small: Boolean = false) {
        val m = min(width, height)
        val r = if (small) m * 0.07f else m * (0.08f + Random.nextFloat() * 0.07f)
        bubbles += Bubble(x, y, r, (55 + Random.nextFloat() * 60) * dpf, Random.nextFloat() * 6f, BRIGHT.random(), Random.nextFloat() < 0.6f)
    }

    override fun step(dt: Float) {
        spawnT -= dt
        if (spawnT <= 0 && bubbles.size < 9) { spawn(); spawnT = 0.6f + Random.nextFloat() * 0.7f }
        val it = bubbles.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.t += dt
            b.y -= b.speed * dt
            b.x += sin(b.t * 1.6f + b.phase) * 24 * dpf * dt
            if (b.y < -b.r * 2) it.remove()
        }
        parts.update(dt)
    }

    override fun render(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (b in bubbles) {
            val wob = 1f + 0.04f * sin(b.t * 5 + b.phase)
            c.save(); c.translate(b.x, b.y); c.scale(wob, 2f - wob)
            fill.color = b.color; fill.alpha = 185
            c.drawCircle(0f, 0f, b.r, fill)
            ring.strokeWidth = 3.5f * dpf; ring.alpha = 220
            c.drawCircle(0f, 0f, b.r, ring)
            shine.alpha = 200
            c.save(); c.rotate(-35f)
            oval.set(-b.r * 0.55f, -b.r * 0.78f, -b.r * 0.05f, -b.r * 0.52f); c.drawOval(oval, shine)
            c.restore()
            if (b.face) {
                c.drawCircle(-b.r * 0.28f, -b.r * 0.05f, b.r * 0.085f, ink)
                c.drawCircle(b.r * 0.28f, -b.r * 0.05f, b.r * 0.085f, ink)
                c.drawCircle(-b.r * 0.45f, b.r * 0.18f, b.r * 0.1f, cheek)
                c.drawCircle(b.r * 0.45f, b.r * 0.18f, b.r * 0.1f, cheek)
                smile.strokeWidth = b.r * 0.07f
                oval.set(-b.r * 0.26f, -b.r * 0.05f, b.r * 0.26f, b.r * 0.33f)
                c.drawArc(oval, 20f, 140f, false, smile)
            }
            c.restore()
        }
        parts.draw(c)
    }

    override fun onTap(x: Float, y: Float, id: Int) {
        val hit = bubbles.filter { hypot(it.x - x, it.y - y) < it.r * 1.45f }.minByOrNull { hypot(it.x - x, it.y - y) }
        if (hit != null) {
            bubbles.remove(hit)
            parts.burst(hit.x, hit.y, 18, intArrayOf(hit.color, Color.WHITE), 520 * dpf, hit.r * 0.18f, 600 * dpf, intArrayOf(Shapes.CIRCLE, Shapes.STAR), 0.9f)
            Sounds.pop()
            if (Random.nextFloat() < 0.25f) Sounds.play("giggle", 0.5f)
        } else {
            spawn(x, y, small = true)
            parts.burst(x, y, 6, BRIGHT, 160 * dpf, 6 * dpf, 0f, intArrayOf(Shapes.CIRCLE), 0.6f)
            Sounds.note(Random.nextInt(Sounds.noteCount), 0.4f)
        }
    }
}

// =====================================================================
// Peekaboo Pup – an original cartoon puppy hides behind bushes. Any tap = PEEKABOO!
// =====================================================================
class PupGame(ctx: Context) : GameView(ctx) {
    private val HIDDEN = 0; private val UP = 1; private val SHOWN = 2; private val DOWN = 3
    private var state = HIDDEN
    private var st = 0f
    private var rise = 0f
    private var idle = 0f
    private var spot = 1
    private var hideFor = 1.2f
    private var happy = 0f
    private var blinkT = 2f
    private var jump = 0f
    private var time = 0f
    private val bx = FloatArray(3)
    private var by = 0f
    private var br = 0f
    private val parts = Particles()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sky = Paint()
    private val oval = RectF()

    private val FUR = 0xFFF2B866.toInt()
    private val EAR = 0xFF9A5B2E.toInt()
    private val CREAM = 0xFFFFF1D9.toInt()
    private val DARK = 0xFF3A2A22.toInt()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sky.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF8FD3FF.toInt(), 0xFFDDF3FF.toInt(), Shader.TileMode.CLAMP)
        br = min(w / 7.5f, h / 4.2f)
        by = h * 0.74f
        bx[0] = w * 0.2f; bx[1] = w * 0.5f; bx[2] = w * 0.8f
    }

    private fun backOut(t: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; return 1 + c3 * (t - 1).pow(3) + c1 * (t - 1).pow(2) }

    private fun headR() = br * 0.78f
    private fun headY(): Float {
        val upY = by - br * 0.95f - headR() * 0.55f
        val downY = by + headR() * 1.3f
        return downY + (upY - downY) * rise - jump * br * 0.5f
    }

    private fun popAt(i: Int) {
        spot = i; state = UP; st = 0f
        Sounds.play("boing", 0.8f)
    }

    override fun step(dt: Float) {
        time += dt; st += dt; idle += dt
        happy = (happy - dt).coerceAtLeast(0f)
        jump = (jump - dt * 2.5f).coerceAtLeast(0f)
        blinkT -= dt; if (blinkT < -0.15f) blinkT = 2f + Random.nextFloat() * 2.5f
        when (state) {
            HIDDEN -> {
                // ears peek out to invite a tap
                val target = if (st > hideFor) 0.22f + 0.04f * sin(time * 6) else 0f
                rise += (target - rise) * min(1f, dt * 6)
                if (st > hideFor + 3.5f && idle > 4f) popAt(spot)
            }
            UP -> {
                rise = backOut(min(1f, st / 0.35f))
                if (st >= 0.35f) {
                    state = SHOWN; st = 0f; happy = 1.4f
                    Sounds.play("giggle", 0.8f)
                    parts.burst(bx[spot], headY(), 14, intArrayOf(0xFFFF5A8A.toInt(), 0xFFFF9EC1.toInt(), 0xFFFFD34D.toInt()), 420 * dpf, 14 * dpf, 250 * dpf, intArrayOf(Shapes.HEART, Shapes.STAR), 1.4f, upward = true)
                }
            }
            SHOWN -> { rise = 1f; if (st > 2.6f) { state = DOWN; st = 0f } }
            DOWN -> {
                rise = 1f - min(1f, st / 0.45f)
                if (st >= 0.45f) {
                    state = HIDDEN; st = 0f
                    spot = (spot + 1 + Random.nextInt(2)) % 3
                    hideFor = 0.8f + Random.nextFloat() * 1.2f
                }
            }
        }
        parts.update(dt)
    }

    override fun render(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, sky)
        // sun
        p.color = 0xFFFFD84D.toInt()
        val sx = w * 0.86f; val sy = h * 0.16f; val sr = min(w, h) * 0.08f
        c.save(); c.rotate(time * 12, sx, sy)
        for (i in 0 until 10) { c.rotate(36f, sx, sy); c.drawRoundRect(sx - sr * 0.12f, sy - sr * 1.75f, sx + sr * 0.12f, sy - sr * 1.25f, sr * 0.1f, sr * 0.1f, p) }
        c.restore()
        c.drawCircle(sx, sy, sr, p)
        // clouds
        p.color = Color.WHITE
        for (k in 0 until 2) {
            val cx = ((time * 18 * dpf + k * w * 0.55f) % (w + 300 * dpf)) - 150 * dpf
            val cy = h * (0.14f + k * 0.12f); val cr = min(w, h) * 0.06f
            c.drawCircle(cx, cy, cr, p); c.drawCircle(cx + cr, cy - cr * 0.4f, cr * 1.2f, p); c.drawCircle(cx + cr * 2.2f, cy, cr, p)
        }
        // hills
        p.color = 0xFF9BE08A.toInt(); c.drawCircle(w * 0.15f, h * 1.15f, h * 0.65f, p)
        p.color = 0xFF86D676.toInt(); c.drawCircle(w * 0.85f, h * 1.2f, h * 0.7f, p)
        // pup (behind the bushes)
        drawPup(c, bx[spot], headY(), headR())
        // bushes + ground
        for (i in 0 until 3) {
            val x = bx[i]
            val sway = sin(time * 2 + i) * br * 0.02f
            p.color = 0xFF3FAE5A.toInt()
            c.drawCircle(x - br * 0.75f + sway, by + br * 0.2f, br * 0.72f, p)
            c.drawCircle(x + br * 0.75f + sway, by + br * 0.2f, br * 0.72f, p)
            p.color = 0xFF4CC266.toInt()
            c.drawCircle(x + sway, by - br * 0.05f, br, p)
            p.color = 0xFF6BD883.toInt()
            c.drawCircle(x - br * 0.35f + sway, by - br * 0.4f, br * 0.25f, p)
            // flowers
            p.color = 0xFFFF7EB0.toInt(); c.drawCircle(x + br * 0.45f, by - br * 0.35f, br * 0.09f, p)
            p.color = 0xFFFFE14D.toInt(); c.drawCircle(x - br * 0.6f, by + br * 0.05f, br * 0.08f, p)
        }
        p.color = 0xFF5BC46E.toInt()
        c.drawRect(0f, by + br * 0.35f, w, h, p)
        parts.draw(c)
    }

    private fun drawPup(c: Canvas, cx: Float, cy: Float, r: Float) {
        val wig = sin(time * 9) * (if (state == SHOWN) 10f else 4f)
        // body
        p.color = FUR
        oval.set(cx - r * 0.85f, cy + r * 0.55f, cx + r * 0.85f, cy + r * 2.3f); c.drawOval(oval, p)
        p.color = CREAM
        oval.set(cx - r * 0.45f, cy + r * 0.8f, cx + r * 0.45f, cy + r * 2.0f); c.drawOval(oval, p)
        // ears
        p.color = EAR
        c.save(); c.rotate(28f + wig, cx - r * 0.72f, cy - r * 0.55f)
        oval.set(cx - r * 1.05f, cy - r * 0.8f, cx - r * 0.5f, cy + r * 0.45f); c.drawOval(oval, p); c.restore()
        c.save(); c.rotate(-28f - wig, cx + r * 0.72f, cy - r * 0.55f)
        oval.set(cx + r * 0.5f, cy - r * 0.8f, cx + r * 1.05f, cy + r * 0.45f); c.drawOval(oval, p); c.restore()
        // head
        p.color = FUR; c.drawCircle(cx, cy, r, p)
        // eye patch (makes her unique)
        p.color = 0xFFD99A4E.toInt()
        oval.set(cx + r * 0.12f, cy - r * 0.48f, cx + r * 0.62f, cy + r * 0.08f); c.drawOval(oval, p)
        // muzzle
        p.color = CREAM
        oval.set(cx - r * 0.5f, cy + r * 0.02f, cx + r * 0.5f, cy + r * 0.78f); c.drawOval(oval, p)
        // eyes
        val ey = cy - r * 0.17f
        p.color = DARK
        if (happy > 0f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.08f; p.strokeCap = Paint.Cap.ROUND
            oval.set(cx - r * 0.5f, ey - r * 0.12f, cx - r * 0.2f, ey + r * 0.14f); c.drawArc(oval, 200f, 140f, false, p)
            oval.set(cx + r * 0.2f, ey - r * 0.12f, cx + r * 0.5f, ey + r * 0.14f); c.drawArc(oval, 200f, 140f, false, p)
            p.style = Paint.Style.FILL
        } else if (blinkT < 0f) {
            c.drawRect(cx - r * 0.47f, ey - r * 0.02f, cx - r * 0.23f, ey + r * 0.03f, p)
            c.drawRect(cx + r * 0.23f, ey - r * 0.02f, cx + r * 0.47f, ey + r * 0.03f, p)
        } else {
            c.drawCircle(cx - r * 0.35f, ey, r * 0.12f, p); c.drawCircle(cx + r * 0.35f, ey, r * 0.12f, p)
            p.color = Color.WHITE
            c.drawCircle(cx - r * 0.31f, ey - r * 0.05f, r * 0.04f, p); c.drawCircle(cx + r * 0.39f, ey - r * 0.05f, r * 0.04f, p)
        }
        // cheeks
        p.color = 0x55FF6B8A
        c.drawCircle(cx - r * 0.58f, cy + r * 0.25f, r * 0.13f, p); c.drawCircle(cx + r * 0.58f, cy + r * 0.25f, r * 0.13f, p)
        // nose
        p.color = DARK
        oval.set(cx - r * 0.17f, cy + r * 0.12f, cx + r * 0.17f, cy + r * 0.34f); c.drawOval(oval, p)
        // mouth
        if (happy > 0f || state == SHOWN) {
            p.color = 0xFF7A2E3A.toInt()
            oval.set(cx - r * 0.24f, cy + r * 0.3f, cx + r * 0.24f, cy + r * 0.72f); c.drawArc(oval, 0f, 180f, true, p)
            p.color = 0xFFFF7E9D.toInt()
            oval.set(cx - r * 0.13f, cy + r * 0.48f, cx + r * 0.13f, cy + r * 0.74f); c.drawOval(oval, p)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.05f; p.color = DARK
            oval.set(cx - r * 0.22f, cy + r * 0.25f, cx, cy + r * 0.48f); c.drawArc(oval, 20f, 140f, false, p)
            oval.set(cx, cy + r * 0.25f, cx + r * 0.22f, cy + r * 0.48f); c.drawArc(oval, 20f, 140f, false, p)
            p.style = Paint.Style.FILL
        }
    }

    override fun onTap(x: Float, y: Float, id: Int) {
        idle = 0f
        val nearest = (0 until 3).minByOrNull { abs(bx[it] - x) } ?: 1
        when (state) {
            HIDDEN, DOWN -> popAt(nearest)
            else -> {
                happy = 1.2f; jump = 1f; st = 0f
                Sounds.play("giggle", 0.8f, 0.9f + Random.nextFloat() * 0.3f)
                parts.burst(bx[spot], headY(), 10, intArrayOf(0xFFFF5A8A.toInt(), 0xFFFFD34D.toInt(), 0xFF4DA3FF.toInt()), 380 * dpf, 13 * dpf, 250 * dpf, intArrayOf(Shapes.HEART, Shapes.STAR), 1.3f, upward = true)
            }
        }
    }
}

// =====================================================================
// Music Pads – six giant buttons, each with a shape, color and note.
// =====================================================================
class MusicGame(ctx: Context) : GameView(ctx) {
    private val colors = intArrayOf(0xFFFF5A5F.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFD23F.toInt(), 0xFF3DDC97.toInt(), 0xFF4DA3FF.toInt(), 0xFFB57BFF.toInt())
    private val shapes = intArrayOf(Shapes.CIRCLE, Shapes.STAR, Shapes.HEART, Shapes.TRIANGLE, Shapes.SQUARE, Shapes.DIAMOND)
    private val notes = intArrayOf(0, 1, 2, 3, 4, 5)
    private val rects = Array(6) { RectF() }
    private val press = FloatArray(6)
    private val parts = Particles()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()
    private var t = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cols = if (w > h) 3 else 2
        val rows = 6 / cols
        val gap = 16 * dpf
        val cw = (w - gap * (cols + 1)) / cols
        val ch = (h - gap * (rows + 1)) / rows
        for (i in 0 until 6) {
            val cx = i % cols; val cy = i / cols
            rects[i].set(gap + cx * (cw + gap), gap + cy * (ch + gap), gap + cx * (cw + gap) + cw, gap + cy * (ch + gap) + ch)
        }
    }

    override fun step(dt: Float) {
        t += dt
        for (i in 0 until 6) press[i] = (press[i] - dt * 3f).coerceAtLeast(0f)
        parts.update(dt)
    }

    override fun render(c: Canvas) {
        c.drawColor(0xFFFFF7EC.toInt())
        for (i in 0 until 6) {
            val r = rects[i]
            val s = 1f - 0.08f * press[i] + 0.012f * sin(t * 2.2f + i)
            val hw = r.width() * s / 2; val hh = r.height() * s / 2
            tmp.set(r.centerX() - hw, r.centerY() - hh, r.centerX() + hw, r.centerY() + hh)
            p.color = if (press[i] > 0f) blendWhite(colors[i], press[i] * 0.35f) else colors[i]
            c.drawRoundRect(tmp, 34 * dpf, 34 * dpf, p)
            p.color = 0x33FFFFFF
            tmp.bottom = tmp.top + hh * 0.7f; tmp.inset(10 * dpf, 8 * dpf)
            c.drawRoundRect(tmp, 26 * dpf, 26 * dpf, p)
            p.color = Color.WHITE
            Shapes.draw(c, shapes[i], r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.24f * (1f + 0.3f * press[i]), p, sin(t + i) * 6f)
        }
        parts.draw(c)
    }

    private fun blendWhite(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(), (Color.green(c) + (255 - Color.green(c)) * f).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
    )

    override fun onTap(x: Float, y: Float, id: Int) {
        val i = rects.indices.firstOrNull { rects[it].contains(x, y) }
            ?: rects.indices.minByOrNull { hypot(rects[it].centerX() - x, rects[it].centerY() - y) } ?: return
        press[i] = 1f
        Sounds.note(notes[i])
        parts.burst(x, y, 9, intArrayOf(colors[i], Color.WHITE), 520 * dpf, 16 * dpf, 500 * dpf, intArrayOf(shapes[i]), 1.1f, upward = true)
    }
}

// =====================================================================
// Sparkle Sky – calm night sky; every tap makes a soft firework of stars and hearts.
// =====================================================================
class SparkleGame(ctx: Context) : GameView(ctx) {
    private class Star(val x: Float, val y: Float, val r: Float, val ph: Float)
    private class Ring(val x: Float, val y: Float, val color: Int) { var t = 0f }

    private val stars = ArrayList<Star>()
    private val rings = ArrayList<Ring>()
    private val parts = Particles()
    private val sky = Paint()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var t = 0f
    private var idle = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sky.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF141B4D.toInt(), 0xFF4B2C7A.toInt(), Shader.TileMode.CLAMP)
        stars.clear()
        repeat(45) { stars += Star(Random.nextFloat() * w, Random.nextFloat() * h, (1.5f + Random.nextFloat() * 3f) * dpf, Random.nextFloat() * 6f) }
    }

    override fun step(dt: Float) {
        t += dt; idle += dt
        parts.update(dt)
        rings.forEach { it.t += dt }
        rings.removeAll { it.t > 0.9f }
        // a gentle automatic sparkle now and then so it never feels "dead"
        if (idle > 3f) { idle = 0f; burst(Random.nextFloat() * width, height * (0.2f + Random.nextFloat() * 0.4f), quiet = true) }
    }

    override fun render(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
        p.color = Color.WHITE
        for (s in stars) {
            p.alpha = (140 + 115 * sin(t * 1.3f + s.ph)).toInt().coerceIn(30, 255)
            Shapes.draw(c, Shapes.STAR, s.x, s.y, s.r * 1.6f, p)
        }
        p.alpha = 255
        for (r in rings) {
            val f = r.t / 0.9f
            ringP.color = r.color; ringP.alpha = ((1 - f) * 200).toInt()
            ringP.strokeWidth = 6 * dpf * (1 - f) + 1
            c.drawCircle(r.x, r.y, 20 * dpf + f * 140 * dpf, ringP)
        }
        parts.draw(c)
    }

    private fun burst(x: Float, y: Float, quiet: Boolean = false) {
        val col = BRIGHT.random()
        rings += Ring(x, y, col)
        parts.burst(x, y, if (quiet) 14 else 26, intArrayOf(col, BRIGHT.random(), Color.WHITE), 620 * dpf, 13 * dpf, 260 * dpf, intArrayOf(Shapes.STAR, Shapes.HEART, Shapes.CIRCLE), 1.6f)
        val n = ((x / width) * (Sounds.noteCount - 0.01f)).toInt().coerceIn(0, Sounds.noteCount - 1)
        if (quiet) Sounds.note(n, 0.2f) else { Sounds.note(n, 0.7f); if (Random.nextFloat() < 0.3f) Sounds.play("chime", 0.4f) }
    }

    override fun onTap(x: Float, y: Float, id: Int) { idle = 0f; burst(x, y) }
}
