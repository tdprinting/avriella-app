package com.avriella.tv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The baby book: a log of baths, feedings, diapers, naps…, her info (birthday, doctor, allergies…)
 * and one photo per month to watch her grow. Saved on this phone in babylog.json + growth/ photos.
 */
object Baby {
    class Kind(val id: String, val emoji: String, val label: String, val verb: String, val color: Int)

    val KINDS = listOf(
        Kind("bath", "🛁", "Bath", "Had a bath", 0xFF4FA3E0.toInt()),
        Kind("food", "🍼", "Ate", "Ate", 0xFFF2994A.toInt()),
        Kind("diaper", "🧷", "Diaper", "Diaper change", 0xFF9B7EDE.toInt()),
        Kind("nap", "😴", "Nap", "Napped", 0xFF5C6BC0.toInt()),
        Kind("med", "💊", "Medicine", "Medicine", 0xFFEB5757.toInt()),
        Kind("growth", "📏", "Measured", "Measured", 0xFF27AE60.toInt()),
        Kind("milestone", "⭐", "Milestone", "Milestone", 0xFFF2C94C.toInt()),
        Kind("note", "📝", "Note", "Note", 0xFF828282.toInt()),
    )
    fun kind(id: String) = KINDS.firstOrNull { it.id == id } ?: KINDS.last()

    class Entry(val id: String, var kind: String, var time: Long, var note: String, var by: String = "") {
        fun json() = JSONObject().put("id", id).put("kind", kind).put("time", time).put("note", note).put("by", by)
    }

    /** Her info card. Every field is free text except the birthday. */
    class Info {
        var birth: Long = LocalDateTime.of(2026, 2, 7, 8, 40).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var fields = linkedMapOf(
            "Full name" to "", "Birth weight" to "", "Birth length" to "", "Born at" to "",
            "Blood type" to "", "Doctor" to "", "Allergies" to "", "Notes" to "")
    }

    val entries = mutableListOf<Entry>()
    val info = Info()
    private val file get() = File(Library.appCtx.filesDir, "babylog.json")
    val photoDir get() = File(Library.appCtx.filesDir, "growth").apply { mkdirs() }
    private var loaded = false

    @Synchronized fun ensure() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            val j = JSONObject(file.readText())
            j.optJSONObject("info")?.let { i ->
                info.birth = i.optLong("birth", info.birth)
                i.optJSONObject("fields")?.let { f -> f.keys().forEach { k -> info.fields[k] = f.optString(k) } }
            }
            val a = j.optJSONArray("entries") ?: JSONArray()
            for (n in 0 until a.length()) a.optJSONObject(n)?.let {
                entries += Entry(it.optString("id"), it.optString("kind"), it.optLong("time"), it.optString("note"), it.optString("by"))
            }
            entries.sortByDescending { it.time }
        } catch (e: Exception) { android.util.Log.w("AvriellaBaby", "couldn't read the log", e) }
    }

    @Synchronized fun save() {
        val f = JSONObject(); info.fields.forEach { (k, v) -> f.put(k, v) }
        val a = JSONArray(); entries.forEach { a.put(it.json()) }
        val j = JSONObject().put("info", JSONObject().put("birth", info.birth).put("fields", f)).put("entries", a)
        val tmp = File(file.path + ".tmp"); tmp.writeText(j.toString()); tmp.renameTo(file)
    }

    @Synchronized fun add(kind: String, time: Long = System.currentTimeMillis(), note: String = "", by: String = ""): Entry {
        ensure()
        val e = Entry(Library.newId(), kind, time, note.trim(), by)
        entries += e; entries.sortByDescending { it.time }; save()
        return e
    }

    @Synchronized fun update(id: String, note: String?, time: Long?) {
        ensure()
        entries.firstOrNull { it.id == id }?.let { e -> note?.let { e.note = it.trim() }; time?.let { e.time = it } }
        entries.sortByDescending { it.time }; save()
    }

    @Synchronized fun delete(id: String) { ensure(); entries.removeAll { it.id == id }; save() }

    // ---------- age ----------
    class Age(val years: Int, val months: Int, val days: Int, val totalDays: Long, val totalWeeks: Long, val totalMonths: Long)

    fun age(now: Long = System.currentTimeMillis()): Age {
        val z = ZoneId.systemDefault()
        val b = Instant.ofEpochMilli(info.birth).atZone(z).toLocalDateTime()
        val n = Instant.ofEpochMilli(now).atZone(z).toLocalDateTime()
        if (n.isBefore(b)) return Age(0, 0, 0, 0, 0, 0)
        val totalMonths = ChronoUnit.MONTHS.between(b, n)
        val afterMonths = b.plusMonths(totalMonths)
        val days = ChronoUnit.DAYS.between(afterMonths, n).toInt()
        val totalDays = ChronoUnit.DAYS.between(b, n)
        return Age((totalMonths / 12).toInt(), (totalMonths % 12).toInt(), days, totalDays, totalDays / 7, totalMonths)
    }

    fun ageText(a: Age = age()): String {
        val parts = ArrayList<String>()
        if (a.years > 0) parts += "${a.years} year${if (a.years == 1) "" else "s"}"
        if (a.months > 0 || a.years > 0) parts += "${a.months} month${if (a.months == 1) "" else "s"}"
        parts += "${a.days} day${if (a.days == 1) "" else "s"}"
        return parts.joinToString(", ")
    }

    /** The date she turns [month] months (month 0 = the day she was born). */
    fun monthDate(month: Int): Long {
        val z = ZoneId.systemDefault()
        return Instant.ofEpochMilli(info.birth).atZone(z).plusMonths(month.toLong()).toInstant().toEpochMilli()
    }

    // ---------- growth photos ----------
    fun photoFile(month: Int) = File(photoDir, "m$month.jpg")
    fun photoMonths(): List<Int> = (0..age().totalMonths.toInt() + 1).filter { photoFile(it).exists() }

    /** Saves a photo for [month], shrunk to a sensible size and turned the right way up. */
    fun savePhoto(month: Int, bytes: ByteArray): Boolean {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            val rot = try {
                when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            val tmp = File(photoDir, "m$month.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(photoFile(month))
        } catch (e: Exception) { android.util.Log.w("AvriellaBaby", "couldn't save photo", e); false }
    }

    fun deletePhoto(month: Int) { photoFile(month).delete() }

    // ---------- for the iPhone ----------
    @Synchronized fun json(limit: Int = 300): String {
        ensure()
        val a = age()
        val f = JSONObject(); info.fields.forEach { (k, v) -> f.put(k, v) }
        val kinds = JSONArray(); KINDS.forEach { kinds.put(JSONObject().put("id", it.id).put("emoji", it.emoji).put("label", it.label)) }
        val list = JSONArray(); entries.take(limit).forEach { list.put(it.json()) }
        val photos = JSONArray(); photoMonths().forEach { m -> photos.put(JSONObject().put("month", m).put("t", photoFile(m).lastModified())) }
        return JSONObject()
            .put("name", Library.settings.childName).put("birth", info.birth).put("fields", f)
            .put("age", JSONObject().put("years", a.years).put("months", a.months).put("days", a.days)
                .put("totalDays", a.totalDays).put("totalWeeks", a.totalWeeks).put("totalMonths", a.totalMonths).put("text", ageText(a)))
            .put("kinds", kinds).put("entries", list).put("photos", photos).toString()
    }
}
