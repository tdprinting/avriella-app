package com.avriella.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * DVD-style cover art for each video, looked up once by its title in Apple's free iTunes catalog
 * (movies get their poster; TV seasons get the season art, made into a tall cover).
 * Only the video's title is sent. Covers are kept on her phone, so this happens once per video.
 */
object Covers {
    private const val TAG = "AvriellaCovers"
    private val exec = Executors.newSingleThreadExecutor()
    private val loader = Executors.newFixedThreadPool(2)
    private val mem = LruCache<String, Bitmap>(40)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastAsk = 0L
    private val queued = HashSet<String>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "covers4").apply { mkdirs() } // "2": better matching than the first try
    fun file(ctx: Context, id: String): File = File(dir(ctx), "${id.filter { it.isLetterOrDigit() }}.jpg")
    private fun miss(ctx: Context, id: String) = File(dir(ctx), "${id.filter { it.isLetterOrDigit() }}.none")

    /** Looks up covers for every video that doesn't have one yet (in the background, gently). */
    fun fetchMissing(ctx: Context) {
        val app = ctx.applicationContext
        Library.videos.values.toList().forEach { v -> fetch(app, v) }
    }

    fun fetch(ctx: Context, v: Video, force: Boolean = false) {
        val app = ctx.applicationContext
        synchronized(queued) { if (!queued.add(v.id)) return }
        exec.execute {
            try {
                val f = file(app, v.id); val none = miss(app, v.id)
                if (force) { f.delete(); none.delete(); mem.remove(v.id) }
                // don't keep asking about a title that wasn't found (try again after a week)
                if (f.exists() || (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 86_400_000L)) {
                    if (!discFile(app, v.id).exists() && disc(app, v) != null) main.post { listeners.toList().forEach { it(v.id) } }
                    return@execute
                }
                current = v; official = null
                val bmp = try { find(v.title) } finally { current = null }
                // the store knows the movie's proper name ("A Bug's Life"): use it instead of the file's
                official?.let { name -> if (name != v.title) { Log.i(TAG, "\"${v.title}\" is \"$name\""); v.title = name; main.post { Library.save() } } }
                discFile(app, v.id).delete()
                if (bmp == null) { none.writeText(v.title); disc(app, v); return@execute }
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                none.delete()
                mem.put(v.id, bmp)
                disc(app, v)   // and the disc to go inside
                Log.i(TAG, "cover found for \"${v.title}\"")
                main.post { listeners.toList().forEach { it(v.id) } }
            } catch (e: Exception) {
                Log.i(TAG, "no cover for \"${v.title}\": ${e.message}")
            } finally { synchronized(queued) { queued.remove(v.id) } }
        }
    }

    /** Screens that show covers get told when a new one arrives. */
    val listeners = mutableListOf<(String) -> Unit>()

    /** Puts the cover on [iv]; [fallback] runs if there isn't one (yet). */
    fun load(ctx: Context, v: Video, iv: ImageView, fallback: () -> Unit) {
        iv.tag = "cover:" + v.id
        mem.get(v.id)?.let { iv.setImageBitmap(it); return }
        val f = file(ctx, v.id)
        if (!f.exists()) { fallback(); fetch(ctx, v); return }
        loader.execute {
            val b = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
            if (b != null) mem.put(v.id, b)
            main.post { if (iv.tag == "cover:" + v.id) { if (b != null) iv.setImageBitmap(b) else fallback() } }
        }
    }

    fun forget(ctx: Context, id: String) { mem.remove(id); file(ctx, id).delete(); miss(ctx, id).delete(); discFile(ctx, id).delete() }

    // ---------- the disc inside the case ----------
    fun discFile(ctx: Context, id: String): File = File(dir(ctx), "${id.filter { it.isLetterOrDigit() }}.disc.png")

    /**
     * The printed DVD: the movie's art printed edge to edge on a silver disc, its title curving round
     * the bottom, the clear plastic hub and the hole in the middle. Made on her phone, kept with the cover.
     */
    fun disc(ctx: Context, v: Video): File? = synchronized(this) {
        val out = discFile(ctx, v.id)
        if (out.exists()) return out
        val coverF = file(ctx, v.id)
        val art = (if (coverF.exists()) try { BitmapFactory.decodeFile(coverF.path) } catch (_: Throwable) { null } else null)
            ?: grab(v) ?: return null
        val s = 600; val r = s / 2f
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // silver, with the rainbow sheen at the rim
        p.shader = android.graphics.SweepGradient(r, r, intArrayOf(0xFFE8ECF4.toInt(), 0xFFB9F0FF.toInt(), 0xFFF6C8FF.toInt(), 0xFFFFF2B8.toInt(),
            0xFFE8ECF4.toInt(), 0xFFC8FFD8.toInt(), 0xFFB9D0FF.toInt(), 0xFFE8ECF4.toInt()), null)
        c.drawCircle(r, r, r * 0.995f, p)
        // the print: the cover art, cropped round (a little above centre, where the faces and logos are)
        val pr = r * 0.955f
        val sc = (pr * 2) / minOf(art.width, art.height)
        p.shader = android.graphics.BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(sc, sc); postTranslate(r - art.width * sc / 2, r - art.height * sc * 0.42f) })
        }
        c.drawCircle(r, r, pr, p)
        // darker band at the bottom so the title reads
        p.shader = android.graphics.RadialGradient(r, r, pr, intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r, r, pr, p)
        p.shader = null
        // the title round the bottom edge
        val title = clean(v.title).ifBlank { v.title }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
            textSize = s * 0.058f; setShadowLayer(s * 0.008f, 0f, s * 0.003f, 0xCC000000.toInt()); letterSpacing = 0.04f
        }
        val tr = pr - tp.textSize * 0.95f
        val arc = android.graphics.Path().apply { addArc(RectF(r - tr, r - tr, r + tr, r + tr), 160f, -140f) }
        var t = title
        while (tp.measureText(t) > Math.PI * tr * 140 / 180 * 0.95 && t.length > 4) t = t.dropLast(2).trimEnd() + "…"
        c.drawTextOnPath(t, arc, 0f, tp.textSize * 0.35f, tp)
        // clear plastic hub, its rings, and the hole
        val hub = r * 0.31f
        p.shader = android.graphics.RadialGradient(r, r, hub, intArrayOf(0xEEF4F6FA.toInt(), 0xEEC9CED8.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(r, r, hub, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.004f
        p.color = 0x66FFFFFF; c.drawCircle(r, r, hub, p); c.drawCircle(r, r, r * 0.2f, p); c.drawCircle(r, r, pr, p)
        p.color = 0x33000000; c.drawCircle(r, r, r * 0.16f, p)
        p.style = Paint.Style.FILL
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        c.drawCircle(r, r, r * 0.11f, p); p.xfermode = null
        // a glint of light
        p.shader = android.graphics.SweepGradient(r, r, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.08f, 0.16f, 0.5f, 0.58f, 0.66f))
        c.drawCircle(r, r, pr, p); p.shader = null
        return try { FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; out } catch (e: Exception) { null }
    }

    /** The disc picture, if it's been made (null = draw a plain disc). */
    fun discBitmap(ctx: Context, id: String): Bitmap? {
        val f = discFile(ctx, id)
        return if (f.exists()) try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } else null
    }

    /** The proper title the store gave for the movie being looked up. */
    @Volatile private var official: String? = null

    /** The video being looked up (covers are made one at a time). */
    @Volatile private var current: Video? = null

    /**
     * For TV seasons with only a wide title card: a sharp picture from her own episode fills the cover,
     * the show's logo goes on top (a logo on black blends straight in), and the season goes at the bottom.
     */
    private fun frameCover(v: Video, art: Bitmap, title: String, label: String?): Bitmap? {
        val frame = grab(v) ?: return null
        val w = 600; val h = 900
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // the episode picture, filling the cover (centre crop)
        val s = maxOf(w.toFloat() / frame.width, h.toFloat() / frame.height)
        val fw = frame.width * s; val fh = frame.height * s
        c.drawBitmap(frame, null, RectF((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2), p)
        // darker at the top (for the logo) and the bottom (for the season)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xCC000000.toInt(), 0x00000000, 0x00000000, 0xE6000000.toInt()),
            floatArrayOf(0f, 0.32f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        // the logo: if it sits on black, blend so only the logo shows
        val logoW = w * 0.86f; val logoH = logoW * art.height / art.width
        val dst = RectF((w - logoW) / 2, h * 0.03f, (w + logoW) / 2, h * 0.03f + logoH)
        val logo = if (darkEdges(art)) cutOut(art) else art
        p.setShadowLayer(14f, 0f, 4f, 0xAA000000.toInt())
        c.drawBitmap(logo, null, dst, p); p.clearShadowLayer()
        val parts = title.split(Regex("\\s+[-–:|]\\s+")).drop(1)
        val main = label ?: parts.firstOrNull()
        val sub = if (label != null) parts.drop(1).joinToString(" · ") else parts.drop(1).joinToString(" · ")
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 68f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); setShadowLayer(12f, 0f, 3f, 0xDD000000.toInt())
        }
        if (main != null) c.drawText(main.take(20), w / 2f, h - (if (sub.isNotBlank()) 112f else 70f), tp)
        if (sub.isNotBlank()) { tp.textSize = 40f; tp.color = 0xFFFFE27A.toInt(); c.drawText(sub.take(30), w / 2f, h - 54f, tp) }
        return out
    }

    /** A logo on black, turned into a solid logo with see-through background. */
    private fun cutOut(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]; val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val m = maxOf(r, g, b)
            val a = ((m - 40) * 255 / 120).coerceIn(0, 255) // dark = clear, bright = solid
            // edge pixels are the logo color mixed with black: undo that mix so the edge keeps its color
            val k = if (m >= 160) 1f else 160f / maxOf(m, 1)
            px[i] = if (a == 0) 0 else Color.argb(a, minOf(255, (r * k).toInt()), minOf(255, (g * k).toInt()), minOf(255, (b * k).toInt()))
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun darkEdges(b: Bitmap): Boolean {
        val pts = listOf(2 to 2, b.width - 3 to 2, 2 to b.height - 3, b.width - 3 to b.height - 3, b.width / 2 to 2)
        return pts.all { (x, y) -> val c = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1)); Color.red(c) + Color.green(c) + Color.blue(c) < 90 }
    }

    /** A sharp, bright picture from the video (skips intros and dark frames). */
    private fun grab(v: Video): Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(com.avriella.tv.data.Library.appCtx, android.net.Uri.parse(v.uri))
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val tries = if (dur > 10 * 60_000) listOf(150_000L, 400_000L, 700_000L, dur / 3, dur / 2, dur * 2 / 3) else listOf(dur / 3, dur / 2, dur / 4)
            var best: Bitmap? = null; var bestScore = -1
            for (t in tries) {
                val b = (if (android.os.Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 720)
                else r.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: continue
                val sc = brightness(b)
                if (sc > bestScore) { best?.recycle(); best = b; bestScore = sc } else b.recycle()
                if (sc > 150) break
            }
            best
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun brightness(b: Bitmap): Int {
        var sum = 0; var n = 0
        for (y in 0 until b.height step maxOf(1, b.height / 12)) for (x in 0 until b.width step maxOf(1, b.width / 12)) {
            val c = b.getPixel(x, y); val mx = maxOf(Color.red(c), Color.green(c), Color.blue(c)); val mn = minOf(Color.red(c), Color.green(c), Color.blue(c))
            // bright and colorful (characters) beats dull walls and furniture
            sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 6 + (mx - mn); n++
        }
        return if (n == 0) 0 else sum / n
    }

    // ---------------- looking it up ----------------

    private fun find(title: String): Bitmap? {
        val parts = title.split(Regex("\\s+[-–:|]\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val show = clean(parts.firstOrNull() ?: title)
        val season = Regex("(?i)\\bseason\\s*(\\d+)|\\bS(\\d{1,2})(?:E\\d+)?\\b").find(title)?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toInt() }
        if (season != null) {
            // a TV season: find that season's art
            val res = search("$show season $season", "tvShow", "tvSeason") + search(show, "tvShow", "tvSeason")
            val seasonRx = Regex("(?i)season\\s*$season\\b")
            // the season of exactly that show ("Bluey, Season 1"), not a spin-off ("Bluey Minisodes, Season 1")
            fun extra(o: JSONObject) = (words(o.optString("collectionName").replace(Regex("(?i)season\\s*\\d+"), "")) - words(show)).size
            val pick = res.filter { seasonRx.containsMatchIn(it.optString("collectionName")) && words(show).all { w -> w in words(it.optString("collectionName")) } }
                .minByOrNull { extra(it) }
                ?: res.filter { similar(it.optString("artistName"), show) || similar(it.optString("collectionName"), show) }.minByOrNull { extra(it) }
            // only an exact match from Apple; otherwise Wikipedia (season page, or the show's own picture)
            if (pick != null && extra(pick) == 0) art(pick.optString("artworkUrl100"), title, "Season $season")?.let { return it }
            wiki("$show season $season", show)?.let { art(it, title, "Season $season")?.let { b -> return b } }
            wiki("$show TV series", show)?.let { art(it, title, "Season $season")?.let { b -> return b } }
            return null
        }
        val whole = clean(title)
        // the year tells the 1951 "Alice in Wonderland" from the 2010 one
        val year = Regex("\\((19|20)\\d\\d\\)").find(title)?.value?.trim('(', ')')?.toIntOrNull()
        val movie = search(whole, "movie", "movie").filter {
            similar(it.optString("trackName"), whole) &&
                (year == null || (it.optString("releaseDate").take(4).toIntOrNull()?.let { y -> kotlin.math.abs(y - year) <= 1 } ?: true))
        }.minByOrNull { (words(it.optString("trackName")) - words(whole)).size }
        if (movie != null) art(movie.optString("artworkUrl100"), title, null)?.let {
            official = movie.optString("trackName").replace(Regex("\\s*\\((19|20)\\d\\d\\)$"), "").trim().ifEmpty { null }
            return it
        }
        wiki(if (year != null) "$whole $year film" else "$whole film", whole)?.let { art(it, title, null)?.let { b -> return b } }
        wiki("$show TV series", show)?.let { art(it, title, null)?.let { b -> return b } }
        return null
    }

    /** The main picture of the best-matching Wikipedia article (film poster, season or show art). */
    private fun wiki(query: String, name: String): String? {
        val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=5&prop=pageimages&piprop=original&pilicense=any&gsrsearch=" +
            URLEncoder.encode(query, "UTF-8")
        val pages = JSONObject(get(url)).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val list = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }.toList()
        val hit = list.firstOrNull { p ->
            val t = p.optString("title").replace(Regex("\\s*\\(.*\\)"), "")
            val src = p.optJSONObject("original")?.optString("source") ?: ""
            similar(t, name) && src.startsWith("https://") && !src.substringBefore('?').endsWith(".svg", true)
        } ?: return null
        return hit.getJSONObject("original").getString("source").substringBefore('?')
    }

    private fun get(url: String): String {
        val wait = lastAsk + 1500 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAsk = System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        return c.inputStream.use { String(it.readBytes()) }
    }
    private const val UA = "AvriellaTV/1.0 (personal family video app)"

    /** Removes file-name leftovers like "1080p", "x264", "(2016)", brackets. */
    private fun clean(t: String): String = t
        .replace(Regex("[._]"), " ")
        .replace(Regex("(?i)\\b(480p|720p|1080p|2160p|4k|hdr|x26[45]|h26[45]|hevc|web[- ]?dl|webrip|bluray|brrip|dvdrip|aac|ac3|yify|full movie)\\b"), " ")
        .replace(Regex("[\\[(][^\\])]*[\\])]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun words(s: String) = s.lowercase().replace(Regex("['’]"), "").replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length > 1 && it !in setOf("the", "a", "an", "of", "and") }.toSet()
    private fun similar(a: String, b: String): Boolean {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        return wb.all { it in wa } || wa.all { it in wb }
    }

    private fun search(term: String, media: String, entity: String): List<JSONObject> {
        if (term.isBlank()) return emptyList()
        // Apple asks for no more than about 20 lookups a minute
        val wait = lastAsk + 3500 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAsk = System.currentTimeMillis()
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&media=$media&entity=$entity&limit=10"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        val text = c.inputStream.use { String(it.readBytes()) }
        val arr = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun download(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        val bytes = c.inputStream.use { it.readBytes() }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Fetches the art big and shapes it like a DVD cover (2:3). */
    private fun art(src: String, title: String, label: String?): Bitmap? {
        if (!src.startsWith("https://")) return null
        val big = download(src.replace(Regex("/\\d+x\\d+bb\\."), "/600x900bb.")) ?: return null
        val w = 600; val h = 900
        val ratio = big.height.toFloat() / big.width
        if (ratio > 1.3f) return Bitmap.createScaledBitmap(big, w, h, true) // already a poster
        current?.let { v -> frameCover(v, big, title, label)?.let { return it } }
        // square or wide art (TV seasons): blurred art behind, the art on top, the season below
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val tiny = Bitmap.createScaledBitmap(big, 12, 18, true)
        c.drawBitmap(tiny, null, Rect(0, 0, w, h), p)
        p.color = 0x66000000; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val artH = (w * ratio).toInt()
        val top = ((h - artH) * 0.32f).toInt()
        p.setShadowLayer(18f, 0f, 8f, 0x99000000.toInt())
        p.color = Color.BLACK; c.drawRect(RectF(0f, top.toFloat(), w.toFloat(), (top + artH).toFloat()), p)
        p.clearShadowLayer()
        c.drawBitmap(big, null, Rect(0, top, w, top + artH), p)
        val text = label ?: title.split(Regex("\\s+[-–:|]\\s+")).drop(1).joinToString(" · ").ifBlank { null }
        if (text != null) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 54f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(10f, 0f, 3f, 0xCC000000.toInt())
            }
            val sub = title.split(Regex("\\s+[-–:|]\\s+")).drop(2).joinToString(" · ")
            val y = top + artH + (h - top - artH) * 0.42f
            c.drawText(text.take(22), w / 2f, y, tp)
            if (label != null && sub.isNotBlank()) {
                tp.textSize = 36f; tp.color = 0xFFFFE27A.toInt()
                c.drawText(sub.take(30), w / 2f, y + 56f, tp)
            }
        }
        // a soft shine at the top, like the plastic sleeve
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.35f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.35f, p); p.shader = null
        return out
    }
}
