package com.avriella.tv.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.avriella.tv.data.Thumbs
import com.avriella.tv.data.Video

object Pal {
    const val BG = 0xFFFFF7EF.toInt()
    const val CARD = 0xFFFFFEFC.toInt()   // warm white, opaque so cards can cast a soft shadow
    const val EDGE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF2A2644.toInt()
    const val SUB = 0xFF746F8C.toInt()
    const val LINE = 0xFFF0E7DC.toInt()
    const val BLUE = 0xFF5878F8.toInt()   // the app's accent: a soft periwinkle
    const val ORANGE = 0xFFFF8F4A.toInt()
    const val GREEN = 0xFF22A866.toInt()
    const val RED = 0xFFE8505B.toInt()
    const val PURPLE = 0xFF8E63F7.toInt()
    const val SOFT_BLUE = 0xFFE8EEFF.toInt()
    const val SOFT_ORANGE = 0xFFFFEDDF.toInt()
    const val SOFT_GREEN = 0xFFE1F6EA.toInt()
    /** Tint for the soft shadows under cards and tiles (a warm plum instead of grey). */
    const val SHADOW = 0xFF7A5CA8.toInt()
    val TILE = intArrayOf(
        0xFF5E9DFA.toInt(), 0xFFFFA05C.toInt(), 0xFF57CB8C.toInt(), 0xFFF881B6.toInt(),
        0xFFA88CFB.toInt(), 0xFFF7C035.toInt(), 0xFF40C8C2.toInt(), 0xFFFF7D7F.toInt(),
    )
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeW: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, strokeColor)
    }

fun pressable(bg: Drawable, radius: Float, rippleColor: Int = 0x1F3A2A6B): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), bg, rounded(Color.BLACK, radius))

/** Top-to-bottom sheen of one color: a touch lighter on top, so buttons look softly lit. */
fun sheen(color: Int, radius: Float, amount: Float = 0.2f): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blend(color, Color.WHITE, amount), color)).apply {
        cornerRadius = radius
    }

fun isLight(color: Int): Boolean =
    (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) > 200f

/** Soft tinted drop shadow. Only shows under opaque backgrounds. */
fun View.lift(elev: Float, tint: Int = Pal.SHADOW) {
    elevation = elev
    if (Build.VERSION.SDK_INT >= 28) {
        outlineSpotShadowColor = tint
        outlineAmbientShadowColor = tint
    }
}

/** Gently shrinks the view while it is pressed and springs back on release. */
fun View.pressScale(to: Float = 0.96f) {
    fun anim(v: Float, ms: Long, spring: Boolean) = AnimatorSet().apply {
        playTogether(
            ObjectAnimator.ofFloat(this@pressScale, View.SCALE_X, v),
            ObjectAnimator.ofFloat(this@pressScale, View.SCALE_Y, v),
        )
        duration = ms
        interpolator = if (spring) OvershootInterpolator(2.5f) else DecelerateInterpolator()
    }
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed), anim(to, 90, false))
        addState(intArrayOf(), anim(1f, 220, true))
    }
}

/** Pastel wash with a few soft color blooms: the backdrop of the grown-up screens. */
class WashDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var base: Shader? = null
    private var blooms: List<Shader> = emptyList()
    override fun onBoundsChange(b: android.graphics.Rect) {
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        base = LinearGradient(0f, 0f, w * 0.35f, h,
            intArrayOf(0xFFFFEAF3.toInt(), 0xFFFFF4E4.toInt(), 0xFFEFEBFF.toInt(), 0xFFE3F3FF.toInt()),
            floatArrayOf(0f, 0.36f, 0.72f, 1f), Shader.TileMode.CLAMP)
        fun bloom(cx: Float, cy: Float, r: Float, c: Int) =
            RadialGradient(cx, cy, r, intArrayOf(c, c and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        blooms = listOf(
            bloom(w * 0.92f, h * 0.06f, w * 0.75f, 0x66FFD9A8),
            bloom(w * 0.02f, h * 0.42f, w * 0.70f, 0x55D9CCFF),
            bloom(w * 0.95f, h * 0.86f, w * 0.80f, 0x55BDE9F7),
            bloom(w * 0.10f, h * 0.98f, w * 0.60f, 0x4DFFC9DE),
        )
    }
    override fun draw(c: Canvas) {
        val b = bounds
        p.shader = base; c.drawRect(b, p)
        blooms.forEach { p.shader = it; c.drawRect(b, p) }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

fun Context.label(text: CharSequence, sp: Float = 16f, color: Int = Pal.INK, bold: Boolean = false, lines: Int = 0): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (sp >= 20f) letterSpacing = -0.01f
        setLineSpacing(0f, 1.08f)
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

fun Context.pill(text: CharSequence, bg: Int, fg: Int = Color.WHITE, sp: Float = 16f, onClick: () -> Unit): TextView =
    label(text, sp, fg, true).apply {
        gravity = Gravity.CENTER
        val r = dpf(28f)
        val opaque = Color.alpha(bg) == 255
        val light = opaque && isLight(bg)
        val base: Drawable = when {
            !opaque -> rounded(bg, r, dp(1), 0x38FFFFFF)          // glassy, for colorful or dark backgrounds
            light -> rounded(bg, r, dp(1), Pal.LINE)
            else -> sheen(bg, r)
        }
        background = pressable(base, r, if (light) 0x1F3A2A6B else 0x40FFFFFF)
        if (opaque) lift(dpf(if (light) 2f else 4f), if (light) Pal.SHADOW else bg)
        pressScale()
        setPadding(dp(20), dp(12), dp(20), dp(12))
        minHeight = dp(50)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.outlinePill(text: CharSequence, fg: Int = Pal.INK, sp: Float = 15f, onClick: () -> Unit): TextView =
    label(text, sp, fg, true).apply {
        gravity = Gravity.CENTER
        val r = dpf(26f)
        background = pressable(rounded(Pal.CARD, r, dp(1), Pal.LINE), r)
        lift(dpf(2f))
        pressScale()
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = dp(46)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun Context.card(pad: Int = 16, color: Int = Pal.CARD): LinearLayout = vbox().apply {
    background = rounded(color, dpf(28f), dp(1), Pal.EDGE)
    if (Color.alpha(color) == 255) lift(dpf(5f))
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.section(title: String): TextView =
    label(title, 21f, Pal.INK, true).apply { setPadding(dp(6), dp(28), dp(6), dp(10)) }

fun Context.spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

/** A horizontally scrolling row of choice chips. */
fun Context.chips(
    options: List<String>, selected: Int, dark: Boolean = false, onPick: (Int) -> Unit,
): View {
    val row = hbox()
    val views = ArrayList<TextView>()
    fun style(i: Int, sel: Boolean) {
        val tv = views[i]
        val r = dpf(22f)
        val on = if (dark) Pal.ORANGE else Pal.BLUE
        val base: Drawable = when {
            sel -> sheen(on, r)
            dark -> rounded(0x2EFFFFFF, r, dp(1), 0x33FFFFFF)
            else -> rounded(Pal.CARD, r, dp(1), Pal.LINE)
        }
        tv.background = pressable(base, r, if (sel || dark) 0x40FFFFFF else 0x1F3A2A6B)
        tv.setTextColor(if (sel || dark) Color.WHITE else Pal.INK)
        if (!dark) tv.lift(dpf(if (sel) 3f else 1.5f), if (sel) on else Pal.SHADOW)
    }
    options.forEachIndexed { i, o ->
        val tv = label(o, 15f, Pal.INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            minHeight = dp(46)
            isClickable = true
            pressScale(0.94f)
        }
        views.add(tv)
        row.addView(tv, lp(WRAP, WRAP, l = dp(2), t = dp(3), r = dp(8), b = dp(6)))
        tv.setOnClickListener {
            views.indices.forEach { k -> style(k, k == i) }
            onPick(i)
        }
    }
    views.indices.forEach { style(it, it == selected) }
    row.clipChildren = false
    row.clipToPadding = false
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(row)
    }
}

/** Keeps a width:height ratio (for thumbnails/tiles). */
class AspectFrame(ctx: Context, private val ratio: Float) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}

class ProgressLine(ctx: Context, var fraction: Float, private val fg: Int = Pal.ORANGE, private val bgc: Int = Pal.LINE) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var fill: Shader? = null
    private var fillW = -1f
    override fun onDraw(c: Canvas) {
        val rad = height / 2f
        p.shader = null
        p.color = bgc; r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, rad, rad, p)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            val w = maxOf(height.toFloat(), width * f)
            if (w != fillW) {
                // soft glow along the bar: lighter at the start, full color at the tip
                fill = LinearGradient(0f, 0f, w, 0f, blend(fg, Color.WHITE, 0.38f), fg, Shader.TileMode.CLAMP); fillW = w
            }
            p.color = fg; p.shader = fill; r.set(0f, 0f, w, height.toFloat()); c.drawRoundRect(r, rad, rad, p)
            p.shader = null
        }
    }
}

/** Picture area: colored background + emoji, with the video thumbnail on top when available. */
fun Context.art(color: Int, emoji: String, video: Video?, ratio: Float = 0.6f, radius: Float = 18f, emojiSp: Float = 40f): AspectFrame {
    val f = AspectFrame(this, ratio)
    val lighter = blend(color, Color.WHITE, 0.42f)
    val wash = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(lighter, color)).apply { cornerRadius = dpf(radius) }
    // a soft highlight in the top-left corner so the tile looks gently lit
    val glow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(0x59FFFFFF, 0x00FFFFFF)
        setGradientCenter(0.18f, 0.12f)
        gradientRadius = dpf(120f)
        cornerRadius = dpf(radius)
    }
    f.background = LayerDrawable(arrayOf(wash, glow))
    f.outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dpf(radius))
    }
    f.clipToOutline = true
    f.addView(label(emoji, emojiSp).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
    if (video != null) {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        Thumbs.load(this, video, iv)
    }
    return f
}

fun blend(a: Int, b: Int, t: Float): Int {
    val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
    val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
    val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    return Color.rgb(r, g, bl)
}

fun Context.columns(): Int {
    val w = resources.configuration.screenWidthDp
    return when { w >= 900 -> 5; w >= 700 -> 4; w >= 520 -> 3; else -> 2 }
}

fun Context.grid(views: List<View>, cols: Int = columns()): LinearLayout {
    val g = vbox().apply { clipChildren = false; clipToPadding = false }
    views.chunked(cols).forEach { rowViews ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        rowViews.forEach {
            // tiles float a little above the page and dip when pressed
            if (it.background != null && it.elevation == 0f) it.lift(dpf(4f))
            if (it.hasOnClickListeners() && it.stateListAnimator == null) it.pressScale(0.97f)
            row.addView(it, lp(0, WRAP, 1f, dp(6), dp(6), dp(6), dp(8)))
        }
        repeat(cols - rowViews.size) { row.addView(View(this), lp(0, 1, 1f, dp(6), 0, dp(6), 0)) }
        g.addView(row, lp())
    }
    return g
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val m = (ms / 60000).toInt()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m.coerceAtLeast(1)} min"
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Activity.askText(title: String, initial: String, okLabel: String = "OK", ok: (String) -> Unit) {
    val edit = EditText(this).apply {
        setText(initial); setSelection(initial.length)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setSingleLine()
    }
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton(okLabel) { _, _ -> val t = edit.text.toString().trim(); if (t.isNotEmpty()) ok(t) }
        .setNegativeButton("Cancel", null).show()
}

fun Activity.confirm(title: String, msg: String, yes: String, ok: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton(yes) { _, _ -> ok() }
        .setNegativeButton("Cancel", null).show()
}

fun Activity.pickOne(title: String, items: List<String>, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this).setTitle(title)
        .setItems(items.toTypedArray()) { _, i -> onPick(i) }
        .setNegativeButton("Cancel", null).show()
}

fun Activity.pickMany(title: String, items: List<String>, checked: BooleanArray, okLabel: String, onDone: (List<Int>) -> Unit) {
    AlertDialog.Builder(this).setTitle(title)
        .setMultiChoiceItems(items.toTypedArray(), checked) { _, i, c -> checked[i] = c }
        .setPositiveButton(okLabel) { _, _ -> onDone(checked.indices.filter { checked[it] }) }
        .setNeutralButton("Select all", null)
        .setNegativeButton("Cancel", null)
        .create().apply {
            setOnShowListener {
                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val all = !checked.all { it }
                    for (i in checked.indices) { checked[i] = all; listView.setItemChecked(i, all) }
                }
            }
        }.show()
}
