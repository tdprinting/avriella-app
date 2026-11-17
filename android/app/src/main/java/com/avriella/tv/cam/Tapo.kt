package com.avriella.tv.cam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.avriella.tv.data.Library
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Tapo Wi-Fi cameras (like the C210) shown inside the parent app.
 *
 * Each camera offers its own video over RTSP once a "Camera Account" is made for it in the Tapo app.
 * Her phone (always on the home Wi-Fi) opens that video only while a parent is watching, turns the
 * frames into JPEGs and serves them to the iPhone exactly like her own baby cam.
 * The camera login stays on her phone; it's never sent back to the iPhones.
 */
object Tapo {
    private const val TAG = "Tapo"
    class Cam(val id: String, var name: String, var ip: String, var user: String, var pass: String)

    private fun prefs() = Library.appCtx.getSharedPreferences("tapo", Context.MODE_PRIVATE)

    @Synchronized fun list(): List<Cam> = try {
        val a = JSONArray(prefs().getString("cams", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Cam(it.getString("id"), it.optString("name"), it.optString("ip"), it.optString("user"), it.optString("pass"))
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized private fun write(cams: List<Cam>) {
        val a = JSONArray()
        cams.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("ip", it.ip).put("user", it.user).put("pass", it.pass)) }
        prefs().edit().putString("cams", a.toString()).apply()
    }

    fun get(id: String?): Cam? = list().firstOrNull { it.id == id }

    /** For the iPhones: everything except the password. */
    fun json(): JSONObject {
        val a = JSONArray()
        list().forEach { c ->
            val f = feeds[c.id + ":1"] ?: feeds[c.id + ":0"]
            a.put(JSONObject().put("id", c.id).put("name", c.name).put("ip", c.ip).put("user", c.user)
                .put("hasLogin", c.user.isNotEmpty() && c.pass.isNotEmpty()).put("error", f?.error ?: ""))
        }
        return JSONObject().put("cams", a)
    }

    /** Add or update a camera. A blank password on an existing camera keeps the old one. */
    fun save(j: JSONObject): String {
        val ip = j.optString("ip").trim()
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) return "error:That address doesn't look right (like 192.168.4.49)."
        val name = j.optString("name").trim().ifEmpty { "Camera" }.take(30)
        val cams = list().toMutableList()
        val old = cams.firstOrNull { it.id == j.optString("id") }
        val user = j.optString("user").trim()
        val pass = j.optString("pass").ifEmpty { old?.pass ?: "" }
        if (user.isEmpty() || pass.isEmpty()) return "error:Type the Camera Account name and password you made in the Tapo app."
        val c = old ?: Cam(UUID.randomUUID().toString().replace("-", "").take(10), name, ip, user, pass).also { cams.add(it) }
        c.name = name; c.ip = ip; c.user = user; c.pass = pass
        write(cams)
        // the login may have changed: drop any open video so the next viewer reconnects with it
        stopFeeds(c.id)
        return "id:" + c.id
    }

    fun delete(id: String) { stopFeeds(id); write(list().filter { it.id != id }) }

    // ---------- finding cameras on the home Wi-Fi ----------
    /** Looks for Tapo cameras on her phone's network: they answer on port 8800 (and 554 once the Camera Account is set up). */
    fun scan(): JSONObject {
        val mine = myIps()
        val found = JSONArray()
        val pool = Executors.newFixedThreadPool(64)
        try {
            val jobs = mutableListOf<Pair<String, Future<Pair<Boolean, Boolean>>>>()
            mine.map { it.substringBeforeLast('.') }.distinct().forEach { net ->
                for (i in 1..254) {
                    val ip = "$net.$i"
                    if (ip in mine) continue
                    jobs += ip to pool.submit<Pair<Boolean, Boolean>> { open(ip, 8800) to open(ip, 554) }
                }
            }
            val saved = list().map { it.ip }.toSet()
            jobs.forEach { (ip, f) ->
                val (tapo, rtsp) = try { f.get(12, TimeUnit.SECONDS) } catch (_: Exception) { false to false }
                if (tapo || rtsp) found.put(JSONObject().put("ip", ip).put("rtsp", rtsp).put("saved", ip in saved))
            }
        } finally { pool.shutdownNow() }
        return JSONObject().put("found", found)
    }

    private fun open(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 900); true }
    } catch (_: Exception) { false }

    private fun myIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    // ---------- live video ----------
    private val feeds = HashMap<String, Feed>()

    /** hq = the camera's full-quality stream (home Wi-Fi); otherwise its small stream (for the internet relay). */
    @Synchronized fun feed(id: String?, hq: Boolean): Feed? {
        val c = get(id) ?: return null
        return feeds.getOrPut(c.id + ":" + (if (hq) 1 else 0)) { Feed(c, hq) }
    }

    @Synchronized private fun stopFeeds(id: String) {
        listOf("$id:0", "$id:1").forEach { k -> feeds.remove(k)?.shutdown() }
    }

    class Feed(private val cam: Cam, private val hq: Boolean) {
        private val lock = Object()
        @Volatile var latest: ByteArray? = null
            private set
        @Volatile var seq = 0L
            private set
        @Volatile var error: String? = null
            private set
        private var viewers = 0
        private val thread = HandlerThread("tapo-" + cam.id).apply { start() }
        private val h = Handler(thread.looper)
        private var mp: MediaPlayer? = null
        private var reader: ImageReader? = null
        private var lastFrame = 0L
        private val gap = if (hq) 66L else 120L
        private val quality = if (hq) 80 else 60
        private val stopLater = Runnable { stop() }
        private val retry = Runnable { if (viewers > 0) start() }
        private val jpeg = YuvJpeg()

        fun acquire() = synchronized(this) {
            viewers++
            h.removeCallbacks(stopLater)
            if (mp == null) h.post { start() }
        }

        fun release() = synchronized(this) {
            viewers = (viewers - 1).coerceAtLeast(0)
            if (viewers == 0) h.postDelayed(stopLater, 10_000)
        }

        fun waitFrame(after: Long, timeoutMs: Long): ByteArray? {
            val end = SystemClock.uptimeMillis() + timeoutMs
            synchronized(lock) {
                while (seq <= after) {
                    val left = end - SystemClock.uptimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
            }
            return if (seq > after) latest else null
        }

        private fun url(): String {
            // letters and numbers go in as they are; anything else is %-escaped so it can't break the address
            fun esc(s: String) = s.map { ch -> if (ch.isLetterOrDigit() || ch in "-._~") ch.toString() else "%" + "%02X".format(ch.code and 0xFF) }.joinToString("")
            return "rtsp://${esc(cam.user)}:${esc(cam.pass)}@${cam.ip}:554/${if (hq) "stream1" else "stream2"}"
        }

        private fun newReader(w: Int, ht: Int): ImageReader {
            val r = ImageReader.newInstance(w, ht, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (e: Exception) { Log.w(TAG, "frame", e); null } ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrame >= gap) {
                        lastFrame = now
                        val jpg = jpeg.make(img, quality)
                        error = null
                        synchronized(lock) { latest = jpg; seq++; lock.notifyAll() }
                    }
                } catch (e: Exception) { Log.w(TAG, "jpeg", e) } finally { img.close() }
            }, h)
            reader = r
            return r
        }

        private fun start() {
            if (mp != null || viewers == 0) return
            try {
                val r = newReader(if (hq) 1920 else 640, if (hq) 1080 else 360)
                val m = MediaPlayer()
                mp = m
                m.setVolume(0f, 0f) // her room's sound stays on the camera
                m.setDataSource(url())
                m.setSurface(r.surface)
                m.setOnVideoSizeChangedListener { _, w, ht ->
                    val cur = reader
                    if (w > 0 && ht > 0 && cur != null && (w != cur.width || ht != cur.height)) {
                        val nr = newReader(w, ht)
                        try { m.setSurface(nr.surface) } catch (e: Exception) { Log.w(TAG, "surface", e) }
                        h.postDelayed({ try { cur.close() } catch (_: Exception) {} }, 600)
                    }
                }
                m.setOnPreparedListener { try { it.start() } catch (_: Exception) {} }
                m.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "${cam.ip} error $what/$extra")
                    error = "Can't open ${cam.name}. Check that its Camera Account name and password are right and that it's on the Wi-Fi."
                    stop(); h.removeCallbacks(retry); h.postDelayed(retry, 6000)
                    true
                }
                m.setOnCompletionListener { stop(); h.postDelayed(retry, 1500) }
                m.prepareAsync()
                // no picture after a while: try again from scratch
                h.postDelayed({ if (mp === m && seq == 0L && viewers > 0) { error = error ?: "${cam.name} isn't answering yet…"; stop(); start() } }, 15_000)
            } catch (e: Exception) {
                Log.w(TAG, "start", e)
                error = "Can't open ${cam.name}: ${e.message}"
                stop(); h.postDelayed(retry, 6000)
            }
        }

        private fun stop() {
            val m = mp; mp = null
            try { m?.reset() } catch (_: Exception) {}
            try { m?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            reader = null
        }

        fun shutdown() { h.removeCallbacksAndMessages(null); h.post { stop(); thread.quitSafely() } }
    }
}

/** Turns a camera/decoder frame (YUV_420_888) into a JPEG, reusing its buffers between frames. */
class YuvJpeg {
    private var nv = ByteArray(0)
    private var uArr = ByteArray(0)
    private var vArr = ByteArray(0)

    fun make(img: Image, quality: Int): ByteArray {
        val w = img.width and 1.inv(); val h = img.height and 1.inv()
        if (nv.size != w * h * 3 / 2) nv = ByteArray(w * h * 3 / 2)
        val out = nv
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ys = yP.rowStride
        for (row in 0 until h) { yb.position(row * ys); yb.get(out, row * w, w) }
        val ub = uP.buffer; val vb = vP.buffer
        ub.position(0); vb.position(0)
        val un = ub.remaining(); val vn = vb.remaining()
        if (uArr.size < un) uArr = ByteArray(un)
        if (vArr.size < vn) vArr = ByteArray(vn)
        ub.get(uArr, 0, un); vb.get(vArr, 0, vn)
        val urs = uP.rowStride; val ups = uP.pixelStride
        val vrs = vP.rowStride; val vps = vP.pixelStride
        var o = w * h
        for (row in 0 until h / 2) {
            val vr = row * vrs; val ur = row * urs
            for (col in 0 until w / 2) {
                val vi = vr + col * vps; val ui = ur + col * ups
                out[o++] = if (vi < vn) vArr[vi] else 0x80.toByte()
                out[o++] = if (ui < un) uArr[ui] else 0x80.toByte()
            }
        }
        val bos = ByteArrayOutputStream(w * h / 4)
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, bos)
        return bos.toByteArray()
    }
}
