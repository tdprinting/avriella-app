package com.avriella.tv.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

val BRIGHT = intArrayOf(
    0xFFFF5A5F.toInt(), 0xFFFFB400.toInt(), 0xFF3DDC97.toInt(), 0xFF4DA3FF.toInt(),
    0xFFB57BFF.toInt(), 0xFFFF7EC7.toInt(), 0xFFFFE14D.toInt(), 0xFF2FD4E0.toInt(),
)

class GameInfo(val id: String, val name: String, val emoji: String, val color: Int, val blurb: String)

val GAMES = listOf(
    GameInfo("paint", "Finger Paint", "🖍️", 0xFFFF7EB0.toInt(), "Rainbow drawing that sings"),
    GameInfo("bubbles", "Bubble Pop", "🫧", 0xFF4DA3FF.toInt(), "Pop the smiley bubbles"),
    GameInfo("pup", "Peekaboo Pup", "🐶", 0xFFFFA94D.toInt(), "Find the hiding puppy"),
    GameInfo("music", "Music Pads", "🎵", 0xFF5CC98A.toInt(), "Big colorful sound buttons"),
    GameInfo("sparkle", "Sparkle Sky", "✨", 0xFF8B5CF6.toInt(), "Tap for star fireworks"),
)

object Shapes {
    const val CIRCLE = 0; const val STAR = 1; const val HEART = 2
    const val DIAMOND = 3; const val TRIANGLE = 4; const val SQUARE = 5
    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, shape: Int, x: Float, y: Float, r: Float, p: Paint, rot: Float = 0f) {
        c.save(); c.translate(x, y); if (rot != 0f) c.rotate(rot)
        when (shape) {
            CIRCLE -> c.drawCircle(0f, 0f, r, p)
            STAR -> { star(r); c.drawPath(path, p) }
            HEART -> { heart(r); c.drawPath(path, p) }
            DIAMOND -> {
                path.reset(); path.moveTo(0f, -r); path.lineTo(r * 0.75f, 0f); path.lineTo(0f, r); path.lineTo(-r * 0.75f, 0f); path.close()
                c.drawPath(path, p)
            }
            TRIANGLE -> {
                path.reset(); path.moveTo(0f, -r); path.lineTo(r * 0.95f, r * 0.7f); path.lineTo(-r * 0.95f, r * 0.7f); path.close()
                c.drawPath(path, p)
            }
            else -> { rect.set(-r * 0.85f, -r * 0.85f, r * 0.85f, r * 0.85f); c.drawRoundRect(rect, r * 0.25f, r * 0.25f, p) }
        }
        c.restore()
    }

    private fun star(r: Float) {
        path.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.45f
            val a = PI / 5 * i - PI / 2
            val px = (cos(a) * rr).toFloat(); val py = (sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    private fun heart(r: Float) {
        path.reset()
        path.moveTo(0f, r * 0.9f)
        path.cubicTo(-r * 1.35f, -r * 0.05f, -r * 0.7f, -r * 1.15f, 0f, -r * 0.4f)
        path.cubicTo(r * 0.7f, -r * 1.15f, r * 1.35f, -r * 0.05f, 0f, r * 0.9f)
        path.close()
    }
}

class Particle {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var life = 0f; var max = 1f; var color = 0; var size = 0f
    var shape = 0; var rot = 0f; var vr = 0f; var g = 0f
}

class Particles {
    val items = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun burst(
        x: Float, y: Float, n: Int, colors: IntArray, speed: Float, size: Float,
        gravity: Float, shapes: IntArray, life: Float = 1.2f, upward: Boolean = false,
    ) {
        repeat(n) {
            val a = if (upward) -PI / 2 + (Random.nextDouble() - 0.5) * 1.6 else Random.nextDouble(0.0, 2 * PI)
            val s = speed * (0.4f + Random.nextFloat() * 0.8f)
            items += Particle().apply {
                this.x = x; this.y = y
                vx = (cos(a) * s).toFloat(); vy = (sin(a) * s).toFloat()
                max = life * (0.7f + Random.nextFloat() * 0.6f); this.life = max
                color = colors.random(); this.size = size * (0.6f + Random.nextFloat() * 0.8f)
                shape = shapes.random(); rot = Random.nextFloat() * 360; vr = (Random.nextFloat() - 0.5f) * 360
                g = gravity
            }
        }
        if (items.size > 450) items.subList(0, items.size - 450).clear()
    }

    fun update(dt: Float) {
        val it = items.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0) { it.remove(); continue }
            p.vy += p.g * dt
            p.x += p.vx * dt; p.y += p.vy * dt
            val drag = 1 - 0.9f * dt
            p.vx *= drag; p.vy *= drag
            p.rot += p.vr * dt
        }
    }

    fun draw(c: Canvas) {
        for (p in items) {
            val f = (p.life / p.max).coerceIn(0f, 1f)
            paint.color = p.color
            paint.alpha = (255 * min(1f, f * 2f)).toInt()
            Shapes.draw(c, p.shape, p.x, p.y, p.size * (0.5f + 0.5f * f), paint, p.rot)
        }
    }
}

/** Simple 60fps game canvas with multi-touch helpers. */
abstract class GameView(ctx: Context) : View(ctx) {
    protected val dpf = resources.displayMetrics.density
    private var lastT = 0L

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 1 / 60f else ((now - lastT) / 1000f).coerceIn(0f, 0.05f)
        lastT = now
        if (width > 0 && height > 0) { step(dt); render(c) }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    abstract fun step(dt: Float)
    abstract fun render(c: Canvas)
    open fun onTap(x: Float, y: Float, id: Int) {}
    open fun onDrag(x: Float, y: Float, id: Int) {}
    open fun onLift(id: Int) {}
    open val canClear = false
    open fun clear() {}

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                onTap(e.getX(i), e.getY(i), e.getPointerId(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) onDrag(e.getX(i), e.getY(i), e.getPointerId(i))
            MotionEvent.ACTION_POINTER_UP -> onLift(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) onLift(e.getPointerId(i))
        }
        return true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Ask Android not to treat edge swipes as "back" gestures while she plays.
        if (Build.VERSION.SDK_INT >= 29) {
            val w = r - l; val h = b - t
            val band = (200 * dpf).toInt()
            val top = (h - band) / 2
            systemGestureExclusionRects = listOf(
                Rect(0, top, (40 * dpf).toInt(), top + band),
                Rect(w - (40 * dpf).toInt(), top, w, top + band),
            )
        }
    }
}

fun makeGame(ctx: Context, id: String): GameView = when (id) {
    "bubbles" -> BubbleGame(ctx)
    "pup" -> PupGame(ctx)
    "music" -> MusicGame(ctx)
    "sparkle" -> SparkleGame(ctx)
    else -> PaintGame(ctx)
}
