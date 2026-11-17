package com.avriella.tv

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.avriella.tv.data.Baby
import com.avriella.tv.data.Library
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.askText
import com.avriella.tv.ui.card
import com.avriella.tv.ui.confirm
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.grid
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pickOne
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.pressable
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.section
import com.avriella.tv.ui.toast
import com.avriella.tv.ui.vbox
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayFmt get() = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
private val timeFmt get() = SimpleDateFormat("h:mm a", Locale.getDefault())
private val fullFmt get() = SimpleDateFormat("EEE, MMM d, yyyy 'at' h:mm a", Locale.getDefault())

/** "5 min ago", "3 hr ago", "2 days ago". */
fun agoText(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} hr ${if (m % 60 > 0) "${m % 60} min " else ""}ago"
        m < 60 * 48 -> "yesterday"
        else -> "${m / (60 * 24)} days ago"
    }
}

private fun ParentActivity.ageHero(): View {
    val a = Baby.age()
    val c = card(20)
    c.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFE3EC.toInt(), 0xFFE8E4FF.toInt())).apply { cornerRadius = dpf(28f) }
    c.addView(label("👶  ${Library.settings.childName}", 26f, Pal.INK, true))
    c.addView(label("${Baby.ageText(a)} old", 18f, Pal.PURPLE, true).apply { setPadding(0, dp(4), 0, 0) })
    c.addView(label("${"%,d".format(a.totalDays)} days  ·  ${a.totalWeeks} weeks  ·  ${a.totalMonths} months", 14f, Pal.SUB).apply { setPadding(0, dp(6), 0, 0) })
    c.addView(label("Born ${fullFmt.format(Date(Baby.info.birth))}", 13f, Pal.SUB).apply { setPadding(0, dp(2), 0, 0) })
    val next = Baby.monthDate(a.totalMonths.toInt() + 1)
    val inDays = ((next - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) + 1
    val nm = a.totalMonths + 1
    val what = if (nm % 12 == 0L) "turns ${nm / 12} 🎂" else "is $nm months old"
    c.addView(label("✨ ${Library.settings.childName} $what in $inDays day${if (inDays == 1L) "" else "s"}", 14f, Pal.INK, true).apply { setPadding(0, dp(10), 0, 0) })
    return c
}

/** The baby book: quick log buttons, today at a glance, her info, and the full history. */
class BabyActivity : ParentActivity() {
    override fun build() {
        Baby.ensure()
        titleView.text = "Baby book"
        body.addView(ageHero(), lp(t = dp(6)))

        body.addView(section("➕  Log something"))
        body.addView(grid(Baby.KINDS.map { k -> kindTile(k) }, 4))

        // today at a glance
        val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val today = Baby.entries.filter { it.time >= dayStart }
        body.addView(section("☀️  Today"))
        val t = card(16)
        if (today.isEmpty()) t.addView(label("Nothing logged yet today. Tap a button above.", 14f, Pal.SUB))
        else t.addView(label(Baby.KINDS.mapNotNull { k -> today.count { it.kind == k.id }.takeIf { it > 0 }?.let { "${k.emoji} $it ${k.label.lowercase()}" } }.joinToString("   ·   "), 15f, Pal.INK, true))
        listOf("bath", "food", "diaper", "nap").forEach { id ->
            val last = Baby.entries.firstOrNull { it.kind == id } ?: return@forEach
            val k = Baby.kind(id)
            t.addView(label("${k.emoji}  Last ${k.label.lowercase()}: ${agoText(last.time)}", 14f, Pal.SUB).apply { setPadding(0, dp(6), 0, 0) })
        }
        body.addView(t)
        body.addView(pill("📊  Stats & monthly photos", Pal.PURPLE) { startActivity(Intent(this, BabyStatsActivity::class.java)) }, lp(MATCH, WRAP, t = dp(12)))

        // her info
        body.addView(section("💗  Her info"))
        val info = card(6)
        info.addView(infoRow("Name", Library.settings.childName) {
            askText("Name", Library.settings.childName, "Save") { Library.settings.childName = it.trim(); Library.save(); rebuild() }
        })
        info.addView(infoRow("Birthday", fullFmt.format(Date(Baby.info.birth))) { pickBirthday() })
        Baby.info.fields.forEach { (k, v) ->
            info.addView(infoRow(k, v.ifBlank { "Tap to add" }) {
                askText(k, v, "Save") { Baby.info.fields[k] = it.trim(); Baby.save(); rebuild() }
            })
        }
        body.addView(info)

        // history
        body.addView(section("📖  History"))
        if (Baby.entries.isEmpty()) body.addView(card(16).apply { addView(label("Everything you log shows up here, newest first.", 14f, Pal.SUB)) })
        var lastDay = ""
        var box: LinearLayout? = null
        Baby.entries.take(150).forEach { e ->
            val d = dayFmt.format(Date(e.time))
            if (d != lastDay) {
                lastDay = d
                body.addView(label(d, 13f, Pal.SUB, true).apply { setPadding(dp(6), dp(12), 0, dp(4)) })
                box = card(4).also { body.addView(it) }
            }
            box?.addView(entryRow(e))
        }
        if (Baby.entries.size > 150) body.addView(label("Showing the latest 150.", 12f, Pal.SUB).apply { setPadding(dp(6), dp(8), 0, 0) })
    }

    private fun kindTile(k: Baby.Kind): View {
        val r = dpf(22f)
        val v = vbox().apply {
            gravity = Gravity.CENTER
            background = pressable(rounded(blendWhite(k.color), r), r)
            setPadding(dp(4), dp(12), dp(4), dp(10))
            setOnClickListener { logDialog(k) }
        }
        v.addView(label(k.emoji, 28f).apply { gravity = Gravity.CENTER })
        v.addView(label(k.label, 13f, Pal.INK, true, 1).apply { gravity = Gravity.CENTER })
        return v
    }

    private fun blendWhite(c: Int): Int {
        val f = 0.22f
        val r = (Color.red(c) * f + 255 * (1 - f)).toInt(); val g = (Color.green(c) * f + 255 * (1 - f)).toInt(); val b = (Color.blue(c) * f + 255 * (1 - f)).toInt()
        return Color.rgb(r, g, b)
    }

    private fun hintFor(id: String) = when (id) {
        "food" -> "What and how much? (e.g. 4 oz bottle, banana)"
        "diaper" -> "Wet, dirty, both…"
        "nap" -> "How long? (e.g. 1 hr 20 min)"
        "med" -> "Which medicine and how much?"
        "growth" -> "Weight and height (e.g. 18 lb, 28 in)"
        "milestone" -> "What did she do? (first steps, first word…)"
        "bath" -> "Anything to note? (optional)"
        else -> "Write a note"
    }

    /** Note + time, then save. */
    private fun logDialog(k: Baby.Kind, existing: Baby.Entry? = null) {
        var time = existing?.time ?: System.currentTimeMillis()
        val box = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        val note = EditText(this).apply {
            hint = hintFor(k.id); setText(existing?.note ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(note)
        val whenBtn = pill("", Pal.SOFT_BLUE, Pal.INK, 15f) {}
        fun paint() { whenBtn.text = "🕒  " + (if (System.currentTimeMillis() - time < 60_000) "Now" else fullFmt.format(Date(time))) + "  (change)" }
        paint()
        whenBtn.setOnClickListener { pickDateTime(time) { time = it; paint() } }
        box.addView(whenBtn, lp(MATCH, WRAP, t = dp(10)))
        AlertDialog.Builder(this)
            .setTitle("${k.emoji}  ${k.verb}")
            .setView(box)
            .setPositiveButton(if (existing == null) "Log it" else "Save") { _, _ ->
                if (existing == null) { Baby.add(k.id, time, note.text.toString(), "Phone"); toast("${k.emoji} Logged") }
                else Baby.update(existing.id, note.text.toString(), time)
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDateTime(start: Long, done: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = start }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                c.set(y, m, d, h, min, 0); done(c.timeInMillis)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis() + 86_400_000L
        }.show()
    }

    private fun pickBirthday() = pickDateTime(Baby.info.birth) { Baby.info.birth = it; Baby.save(); rebuild() }

    private fun infoRow(name: String, value: String, onClick: () -> Unit): View {
        val r = dpf(18f)
        val row = hbox().apply {
            background = pressable(rounded(Color.TRANSPARENT, r), r)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }
        row.addView(label(name, 15f, Pal.INK, true), lp(dp(120), WRAP))
        row.addView(label(value, 15f, if (value == "Tap to add") Pal.SUB else Pal.INK), lp(0, WRAP, 1f))
        return row
    }

    private fun entryRow(e: Baby.Entry): View {
        val k = Baby.kind(e.kind)
        val r = dpf(18f)
        val row = hbox().apply {
            background = pressable(rounded(Color.TRANSPARENT, r), r)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                pickOne("${k.emoji} ${k.verb} · ${timeFmt.format(Date(e.time))}", listOf("✏️  Edit", "🗑️  Delete")) { i ->
                    if (i == 0) logDialog(k, e)
                    else confirm("Delete this?", "${k.verb} at ${timeFmt.format(Date(e.time))}", "Delete") { Baby.delete(e.id); rebuild() }
                }
            }
        }
        row.addView(label(k.emoji, 22f), lp(WRAP, WRAP, r = dp(12)))
        val col = vbox()
        col.addView(label(k.verb, 15f, Pal.INK, true, 1))
        if (e.note.isNotBlank()) col.addView(label(e.note, 14f, Pal.SUB))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(label(timeFmt.format(Date(e.time)) + if (e.by == "iPhone") "\n📱" else "", 13f, Pal.SUB).apply { gravity = Gravity.END })
        return row
    }
}

/** Her stats: age in detail, the last week at a glance, and a photo for every month. */
class BabyStatsActivity : ParentActivity() {
    private val REQ_PHOTO = 51
    private var pickingMonth = 0

    override fun build() {
        Baby.ensure()
        titleView.text = "Stats & photos"
        val a = Baby.age()
        body.addView(ageHero(), lp(t = dp(6)))

        // big numbers
        val nums = listOf("${a.years}" to "years", "${a.months}" to "months", "${a.days}" to "days")
        body.addView(grid(nums.map { (n, l) -> numTile(n, l) }, 3), lp(t = dp(10)))
        body.addView(grid(listOf(numTile("%,d".format(a.totalDays), "days old"), numTile("${a.totalWeeks}", "weeks old"),
            numTile("%,d".format(a.totalDays * 24 + (System.currentTimeMillis() - Baby.info.birth) / 3_600_000 % 24), "hours old")), 3), lp(t = dp(8)))

        // last 7 days
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        val week = Baby.entries.filter { it.time >= since }
        body.addView(section("📅  Last 7 days"))
        val w = card(16)
        val counts = Baby.KINDS.map { k -> k to week.count { it.kind == k.id } }.filter { it.second > 0 }
        if (counts.isEmpty()) w.addView(label("Log baths, feedings and more in the baby book and they'll add up here.", 14f, Pal.SUB))
        val max = counts.maxOfOrNull { it.second } ?: 1
        counts.forEach { (k, n) ->
            val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(label("${k.emoji}  ${k.label}", 14f, Pal.INK, true), lp(dp(118), WRAP))
            val track = FrameLayout(this).apply { background = rounded(Pal.LINE, dpf(8f)) }
            track.addView(View(this).apply { background = rounded(k.color, dpf(8f)) }, FrameLayout.LayoutParams(0, MATCH))
            row.addView(track, lp(0, dp(14), 1f))
            track.post { track.getChildAt(0).layoutParams = FrameLayout.LayoutParams((track.width * n / max.toFloat()).toInt().coerceAtLeast(dp(8)), MATCH); track.requestLayout() }
            row.addView(label("$n  ·  ${"%.1f".format(n / 7f)}/day", 13f, Pal.SUB), lp(WRAP, WRAP, l = dp(10)))
            w.addView(row)
        }
        body.addView(w)

        // last time each thing happened
        val lasts = Baby.KINDS.mapNotNull { k -> Baby.entries.firstOrNull { it.kind == k.id }?.let { k to it } }
        if (lasts.isNotEmpty()) {
            body.addView(section("⏱️  Last time"))
            val l = card(16)
            lasts.forEach { (k, e) ->
                l.addView(label("${k.emoji}  ${k.label}: ${agoText(e.time)}" + (if (e.note.isNotBlank()) "  —  ${e.note}" else ""), 14f, Pal.INK).apply { setPadding(0, dp(4), 0, dp(4)) })
            }
            body.addView(l)
        }

        // monthly photos
        body.addView(section("📸  Watch her grow"))
        body.addView(label("Add a photo each month (you'll get a reminder on the 1st). Tap a month to add or change its picture.", 14f, Pal.SUB).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        if (!Baby.photoFile(a.totalMonths.toInt()).exists()) {
            body.addView(pill("📸  Add this month's photo (${monthName(a.totalMonths.toInt())})", Pal.ORANGE) {
                pickingMonth = a.totalMonths.toInt(); pickPhoto()
            }, lp(MATCH, WRAP, b = dp(8)))
        }
        val have = Baby.photoMonths().size
        body.addView(pill(if (have >= 2) "🎞️  Flip mode: watch her grow ($have photos)" else "🎞️  Flip mode (add 2+ photos)", if (have >= 2) Pal.PURPLE else Pal.CARD, if (have >= 2) Color.WHITE else Pal.SUB) {
            if (have == 0) toast("Add a photo first.") else startActivity(Intent(this, FlipbookActivity::class.java))
        }, lp(MATCH, WRAP, b = dp(10)))
        val months = (a.totalMonths.toInt() downTo 0).toList()
        body.addView(grid(months.map { photoTile(it) }, 3))
    }

    private fun numTile(n: String, l: String): View = card(12).apply {
        gravity = Gravity.CENTER
        addView(label(n, 24f, Pal.PURPLE, true).apply { gravity = Gravity.CENTER })
        addView(label(l, 12f, Pal.SUB).apply { gravity = Gravity.CENTER })
    }

    private fun monthName(m: Int) = when {
        m == 0 -> "Newborn"
        m % 12 == 0 -> "${m / 12} year${if (m == 12) "" else "s"} 🎂"
        else -> "$m month${if (m == 1) "" else "s"}"
    }

    private fun photoTile(m: Int): View {
        val r = dpf(20f)
        val f = Baby.photoFile(m)
        val v = vbox().apply {
            background = pressable(rounded(Pal.CARD, r), r)
            setPadding(dp(6), dp(6), dp(6), dp(8))
            setOnClickListener {
                pickingMonth = m
                if (!f.exists()) pickPhoto()
                else pickOne(monthName(m), listOf("🔍  View", "🔁  Replace", "🗑️  Remove")) { i ->
                    when (i) { 0 -> viewPhoto(m); 1 -> pickPhoto(); else -> confirm("Remove this photo?", monthName(m), "Remove") { Baby.deletePhoto(m); rebuild() } }
                }
            }
        }
        val img = object : ImageView(this) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, w) } // square
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = rounded(0xFFF4EEFF.toInt(), dpf(16f))
        }
        val bmp = if (f.exists()) decodeSmall(f.path, 420) else null
        val frame = FrameLayout(this)
        frame.addView(img, FrameLayout.LayoutParams(MATCH, WRAP))
        if (bmp != null) img.setImageBitmap(bmp)
        else frame.addView(label("＋", 34f, Pal.PURPLE, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        v.addView(frame, lp(MATCH, WRAP))
        v.addView(label(monthName(m), 13f, Pal.INK, true, 1).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        v.addView(label(SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(Baby.monthDate(m))), 11f, Pal.SUB, false, 1).apply { gravity = Gravity.CENTER })
        return v
    }

    private fun decodeSmall(path: String, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun pickPhoto() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_PHOTO) } catch (_: Exception) { toast("No photo app found.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK || uri == null) return
        val m = pickingMonth
        toast("Saving photo…")
        Thread {
            val ok = try { contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { Baby.savePhoto(m, it) } == true } catch (_: Exception) { false }
            runOnUiThread { if (ok) rebuild() else toast("Couldn't use that photo.") }
        }.start()
    }

    private fun viewPhoto(m: Int) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(this).apply {
            setImageBitmap(decodeSmall(Baby.photoFile(m).path, 1600))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { d.dismiss() }
        }
        val fr = FrameLayout(this)
        fr.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        fr.addView(label("${monthName(m)}  ·  tap to close", 15f, Color.WHITE, true).apply { setPadding(dp(18), dp(18), dp(18), dp(18)) },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        d.setContentView(fr)
        d.show()
    }
}
