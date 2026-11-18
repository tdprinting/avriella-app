package com.avriella.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * "Sunny backyard" look for her phone: a bright sky, a smiling sun, drifting clouds and green hills,
 * chunky toy-like cards and an original blue puppy mascot. Everything is drawn in code (no images).
 */

/** The animated backdrop behind every screen: sky, sun, clouds and hills. Moves slowly and gently. */
class BackyardView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var sky: Shader? = null
    private var sunGlow: Shader? = null
    private val hill = Path()
    private val start = SystemClock.uptimeMillis()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { if (++tick % 2 == 0) invalidate() } // ~30 fps is plenty for clouds
    }
    private var tick = 0

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sky = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF7CC8FF.toInt(), 0xFFB5E2FF.toInt(), 0xFFE6F6FF.toInt(), 0xFFFFF6E0.toInt()),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        val sx = w * 0.86f; val sy = h * 0.07f
        sunGlow = RadialGradient(sx, sy, w * 0.5f, intArrayOf(0x88FFE680.toInt(), 0x00FFE680), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        p.shader = sky; c.drawRect(0f, 0f, w, h, p); p.shader = null
        // sun with slowly turning rays
        val sx = w * 0.86f; val sy = h * 0.07f; val sr = w * 0.09f
        p.shader = sunGlow; c.drawCircle(sx, sy, w * 0.5f, p); p.shader = null
        p.color = 0xFFFFD84D.toInt(); p.strokeWidth = sr * 0.22f; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        for (i in 0 until 10) {
            val a = (i / 10f) * 2 * PI + t * 0.25
            val r1 = sr * 1.35f; val r2 = sr * (1.75f + 0.12f * sin(t * 2 + i).toFloat())
            c.drawLine(sx + cos(a).toFloat() * r1, sy + sin(a).toFloat() * r1, sx + cos(a).toFloat() * r2, sy + sin(a).toFloat() * r2, p)
        }
        p.style = Paint.Style.FILL
        p.color = 0xFFFFCF3A.toInt(); c.drawCircle(sx, sy, sr, p)
        p.color = 0xFFFFE27A.toInt(); c.drawCircle(sx - sr * 0.25f, sy - sr * 0.25f, sr * 0.55f, p)
        // clouds drifting right, wrapping around
        cloud(c, ((t * 9f) % (w + 400f)) - 200f, h * 0.05f, w * 0.18f)
        cloud(c, ((t * 6f + w * 0.55f) % (w + 400f)) - 200f, h * 0.16f, w * 0.13f)
        cloud(c, ((t * 4f + w * 0.2f) % (w + 400f)) - 200f, h * 0.30f, w * 0.10f)
        // rolling hills at the bottom
        hills(c, w, h, h * 0.86f, 0xFFA8E07A.toInt(), t * 0.0f, 0.9f)
        hills(c, w, h, h * 0.91f, 0xFF7CCB5B.toInt(), 1.3f, 1.4f)
    }

    private fun cloud(c: Canvas, x: Float, y: Float, s: Float) {
        p.color = 0xF2FFFFFF.toInt()
        c.drawCircle(x, y + s * 0.2f, s * 0.42f, p)
        c.drawCircle(x + s * 0.45f, y, s * 0.55f, p)
        c.drawCircle(x + s * 0.95f, y + s * 0.18f, s * 0.42f, p)
        c.drawRoundRect(RectF(x - s * 0.2f, y + s * 0.1f, x + s * 1.2f, y + s * 0.6f), s * 0.3f, s * 0.3f, p)
    }

    private fun hills(c: Canvas, w: Float, h: Float, top: Float, color: Int, phase: Float, waves: Float) {
        hill.reset(); hill.moveTo(0f, h)
        var x = 0f
        while (x <= w + 10f) {
            hill.lineTo(x, top + sin(x / w * waves * 2 * PI + phase).toFloat() * h * 0.025f)
            x += 12f
        }
        hill.lineTo(w, h); hill.close()
        p.color = color; c.drawPath(hill, p)
    }
}

/**
 * An original cartoon blue puppy (her app's mascot): blinks, wags its ears and bobs happily.
 * Tap it and it does a little jump.
 */
class PupView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val start = SystemClock.uptimeMillis()
    private var jumpAt = -10_000L
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        isClickable = true
        contentDescription = "Puppy"
        setOnClickListener { jumpAt = SystemClock.uptimeMillis() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val s = minOf(width, height) / 100f
        val jump = ((now - jumpAt) / 600f).let { if (it in 0f..1f) sin(it * PI).toFloat() * 14f else 0f }
        val bob = sin(t * 3f) * 1.6f
        c.save()
        c.translate(width / 2f - 50 * s, height / 2f - 50 * s - (bob + jump) * s)
        val wag = sin(t * 5f) * 6f

        // floppy ears hanging down at the sides (dark blue, sandy inside), flapping a little
        fun ear(cx: Float, dir: Float) {
            c.save(); c.rotate(dir * (22f + wag), cx * s, 36 * s)
            p.color = 0xFF2C5BBF.toInt(); c.drawOval(RectF((cx - 10) * s, 30 * s, (cx + 10) * s, 74 * s), p)
            p.color = 0xFFF5C77E.toInt(); c.drawOval(RectF((cx - 5.5f) * s, 38 * s, (cx + 5.5f) * s, 68 * s), p)
            c.restore()
        }
        ear(20f, 1f); ear(80f, -1f)

        // head
        p.color = 0xFF4E8FEA.toInt(); c.drawOval(RectF(16 * s, 26 * s, 84 * s, 88 * s), p)
        // a little tuft of fur on top
        p.color = 0xFF4E8FEA.toInt(); c.drawCircle(44 * s, 28 * s, 6 * s, p); c.drawCircle(53 * s, 26 * s, 7 * s, p)
        // lighter face patch and muzzle
        p.color = 0xFFA9D2FF.toInt(); c.drawOval(RectF(30 * s, 44 * s, 70 * s, 86 * s), p)
        p.color = 0xFFFFF1D9.toInt(); c.drawOval(RectF(36 * s, 60 * s, 64 * s, 85 * s), p)
        // cheeks
        p.color = 0x66FF8FA3; c.drawCircle(27 * s, 66 * s, 6 * s, p); c.drawCircle(73 * s, 66 * s, 6 * s, p)

        // eyes (blink every few seconds)
        val blink = (t % 3.6f).let { if (it > 3.45f) 0.15f else 1f }
        fun eye(cx: Float) {
            p.color = 0xFF1C2541.toInt()
            c.drawOval(RectF((cx - 5) * s, (54 - 6.5f * blink) * s, (cx + 5) * s, (54 + 6.5f * blink) * s), p)
            if (blink > 0.5f) { p.color = Color.WHITE; c.drawCircle((cx + 1.8f) * s, 51.5f * s, 2f * s, p) }
        }
        eye(39f); eye(61f)

        // nose and smile with tongue
        p.color = 0xFF1C2541.toInt(); c.drawOval(RectF(44 * s, 63 * s, 56 * s, 70 * s), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * s; p.strokeCap = Paint.Cap.ROUND
        path.reset(); path.moveTo(42 * s, 73 * s); path.quadTo(46 * s, 78 * s, 50 * s, 73 * s); path.quadTo(54 * s, 78 * s, 58 * s, 73 * s)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFFF7A8E.toInt()
        val tongue = 4f + sin(t * 6f) * 0.8f
        c.drawRoundRect(RectF(46.5f * s, 75 * s, 53.5f * s, (77 + tongue) * s), 3.5f * s, 3.5f * s, p)
        c.restore()
    }
}

/** A chunky, toy-like card: colored face with a darker "edge" underneath, like a plastic block. */
fun Context.toyBackground(color: Int, radius: Float, edge: Int = blend(color, Color.BLACK, 0.22f), depth: Int = dp(5)): LayerDrawable {
    val under = GradientDrawable().apply { setColor(edge); cornerRadius = radius }
    val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blend(color, Color.WHITE, 0.16f), color)).apply { cornerRadius = radius }
    return LayerDrawable(arrayOf(under, face)).apply { setLayerInset(1, 0, 0, 0, depth) }
}

/** Pops a view in with a little bounce, after [delay] ms (for staggered entrances). */
fun View.popIn(delay: Long = 0) {
    alpha = 0f; scaleX = 0.82f; scaleY = 0.82f; translationY = context.dpf(18f)
    animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
        .setStartDelay(delay).setDuration(420).setInterpolator(OvershootInterpolator(1.6f)).start()
}

/** A soft endless "breathing" pulse, to draw a little eye to the big play buttons. */
fun View.breathe(amount: Float = 0.06f, ms: Long = 1400) {
    val a = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ms; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { val v = 1f + amount * (it.animatedValue as Float); scaleX = v; scaleY = v }
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { a.start() }
        override fun onViewDetachedFromWindow(v: View) { a.cancel() }
    })
    if (isAttachedToWindow) a.start()
}

val CHUNKY: android.graphics.Typeface by lazy {
    if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false)
    else android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
}
