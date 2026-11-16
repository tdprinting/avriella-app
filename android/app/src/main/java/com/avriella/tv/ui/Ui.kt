package com.avriella.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    const val BG = 0xFFFFF6EA.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val INK = 0xFF2B2845.toInt()
    const val SUB = 0xFF77738C.toInt()
    const val LINE = 0xFFEDE4D6.toInt()
    const val BLUE = 0xFF3D7BF5.toInt()
    const val ORANGE = 0xFFFF8B3D.toInt()
    const val GREEN = 0xFF1FA35A.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val PURPLE = 0xFF8B5CF6.toInt()
    const val SOFT_BLUE = 0xFFE6EFFF.toInt()
    const val SOFT_ORANGE = 0xFFFFEBDD.toInt()
    const val SOFT_GREEN = 0xFFDFF5E8.toInt()
    val TILE = intArrayOf(
        0xFF5B9DF9.toInt(), 0xFFFF9F5A.toInt(), 0xFF5CC98A.toInt(), 0xFFF77FB3.toInt(),
        0xFFA98BFA.toInt(), 0xFFF5BE2E.toInt(), 0xFF3FC4C0.toInt(), 0xFFFF7A7A.toInt(),
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

fun pressable(bg: Drawable, radius: Float, rippleColor: Int = 0x22000000): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), bg, rounded(Color.BLACK, radius))

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

fun Context.label(text: CharSequence, sp: Float = 16f, color: Int = Pal.INK, bold: Boolean = false, lines: Int = 0): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

fun Context.pill(text: CharSequence, bg: Int, fg: Int = Color.WHITE, sp: Float = 16f, onClick: () -> Unit): TextView =
    label(text, sp, fg, true).apply {
        gravity = Gravity.CENTER
        val r = dpf(26f)
        background = pressable(rounded(bg, r), r)
        setPadding(dp(18), dp(11), dp(18), dp(11))
        minHeight = dp(48)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.outlinePill(text: CharSequence, fg: Int = Pal.INK, sp: Float = 15f, onClick: () -> Unit): TextView =
    label(text, sp, fg, true).apply {
        gravity = Gravity.CENTER
        val r = dpf(26f)
        background = pressable(rounded(Pal.CARD, r, dp(2), Pal.LINE), r)
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
    background = rounded(color, dpf(22f))
    elevation = dpf(1.5f)
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.section(title: String): TextView =
    label(title, 20f, Pal.INK, true).apply { setPadding(dp(4), dp(22), dp(4), dp(10)) }

fun Context.spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

/** A horizontally scrolling row of choice chips. */
fun Context.chips(
    options: List<String>, selected: Int, dark: Boolean = false, onPick: (Int) -> Unit,
): View {
    val row = hbox()
    val views = ArrayList<TextView>()
    fun style(i: Int, sel: Boolean) {
        val tv = views[i]
        val r = dpf(20f)
        val on = if (dark) Pal.ORANGE else Pal.BLUE
        val off = if (dark) 0x33FFFFFF else Pal.CARD
        tv.background = pressable(rounded(if (sel) on else off, r, if (sel || dark) 0 else dp(2), Pal.LINE), r)
        tv.setTextColor(if (sel || dark) Color.WHITE else Pal.INK)
    }
    options.forEachIndexed { i, o ->
        val tv = label(o, 15f, Pal.INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            minHeight = dp(44)
            isClickable = true
        }
        views.add(tv)
        row.addView(tv, lp(WRAP, WRAP, r = dp(8)))
        tv.setOnClickListener {
            views.indices.forEach { k -> style(k, k == i) }
            onPick(i)
        }
    }
    views.indices.forEach { style(it, it == selected) }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
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
    override fun onDraw(c: Canvas) {
        val rad = height / 2f
        p.color = bgc; r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, rad, rad, p)
        if (fraction > 0f) {
            p.color = fg; r.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat()); c.drawRoundRect(r, rad, rad, p)
        }
    }
}

/** Picture area: colored background + emoji, with the video thumbnail on top when available. */
fun Context.art(color: Int, emoji: String, video: Video?, ratio: Float = 0.6f, radius: Float = 18f, emojiSp: Float = 40f): AspectFrame {
    val f = AspectFrame(this, ratio)
    val lighter = blend(color, Color.WHITE, 0.35f)
    f.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(lighter, color)).apply { cornerRadius = dpf(radius) }
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
    val g = vbox()
    views.chunked(cols).forEach { rowViews ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowViews.forEach { row.addView(it, lp(0, WRAP, 1f, dp(5), dp(5), dp(5), dp(5))) }
        repeat(cols - rowViews.size) { row.addView(View(this), lp(0, 1, 1f, dp(5), 0, dp(5), 0)) }
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
