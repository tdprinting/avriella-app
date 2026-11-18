package com.avriella.tv.cam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
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
        prefs().edit().putString("cams", a.toString()).commit()
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
        val pass = j.optString("pass").trim().ifEmpty { old?.pass ?: "" }
        if (user.isEmpty() || pass.isEmpty()) return "error:Type the Camera Account name and password you made in the Tapo app."
        val c = old ?: Cam(UUID.randomUUID().toString().replace("-", "").take(10), name, ip, user, pass).also { cams.add(it) }
        c.name = name; c.ip = ip; c.user = user; c.pass = pass
        write(cams)
        // the login may have changed: drop any open video so the next viewer reconnects with it
        stopFeeds(c.id)
        return "id:" + c.id
    }

    /** Tries the camera's login right away, so the parent hears about a wrong password while the form is still open. */
    fun test(id: String?): JSONObject {
        val c = get(id) ?: return JSONObject().put("error", "No such camera")
        return try {
            Rtsp(c.ip, 554, "stream2", c.user, c.pass).check()
            JSONObject().put("ok", true)
        } catch (e: Rtsp.AuthFailed) {
            JSONObject().put("error", "The camera at ${c.ip} didn't accept that Camera Account name or password. Tap the eye to check what you typed. Letters are case-sensitive.").put("auth", true)
        } catch (e: java.net.ConnectException) {
            JSONObject().put("error", "Nothing at ${c.ip} is accepting camera logins yet. Make the Camera Account in the Tapo app for this camera, then try again.")
        } catch (e: Exception) {
            JSONObject().put("error", "Couldn't reach the camera at ${c.ip} (${e.message ?: e.javaClass.simpleName}).")
        }
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
        @Volatile private var client: Rtsp? = null
        private var worker: Thread? = null
        private var lastFrame = 0L
        /** When the camera last refused the login: no more tries for a while (a Tapo locks out repeated bad logins). */
        @Volatile private var refusedAt = 0L
        private val gap = if (hq) 50L else 120L
        private val quality = if (hq) 78 else 60
        private val stopLater = Runnable { stop() }
        private val jpeg = YuvJpeg()

        fun acquire() = synchronized(this) {
            viewers++
            h.removeCallbacks(stopLater)
            h.post { start() }
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

        private fun start() {
            if (worker != null || viewers == 0) return
            if (refusedAt > 0 && SystemClock.uptimeMillis() - refusedAt < 10 * 60_000) return
            val t = Thread({ loop() }, "tapo-rtsp-" + cam.id)
            worker = t
            t.start()
        }

        /** Keeps the camera's video coming while anyone is watching; reconnects after hiccups. */
        private fun loop() {
            val me = Thread.currentThread()
            while (worker === me && viewers > 0) {
                var wait = 3000L
                try {
                    session()
                } catch (e: Rtsp.AuthFailed) {
                    error = "${cam.name} didn't accept the Camera Account name or password. Retype them exactly as you made them in the Tapo app (they're case-sensitive)."
                    Log.w(TAG, "${cam.ip} login refused")
                    refusedAt = SystemClock.uptimeMillis()
                    break // stop until the login is saved again (or 10 minutes pass): retries would keep the camera locked
                } catch (e: Exception) {
                    if (worker !== me) break
                    Log.w(TAG, "${cam.ip} video", e)
                    if (seq == 0L || error != null) error = when (e) {
                        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
                            "${cam.name} isn't answering at ${cam.ip}. Make sure it's on and on the Wi-Fi."
                        else -> "${cam.name}'s video stopped (${e.message ?: e.javaClass.simpleName}). Trying again…"
                    }
                }
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
            synchronized(this) { if (worker === me) worker = null }
        }

        private fun session() {
            val r = Rtsp(cam.ip, 554, if (hq) "stream1" else "stream2", cam.user, cam.pass)
            client = r
            var codec: android.media.MediaCodec? = null
            var mime = "video/avc"
            val params = ArrayList<ByteArray>()
            var gotKey = false
            val info = android.media.MediaCodec.BufferInfo()
            var pts = 0L
            r.onFormat = { m, ps -> mime = m; params.clear(); params.addAll(ps) }
            fun isParam(t: Int) = if (mime == "video/hevc") t in 32..34 else t == 7 || t == 8
            fun isKey(t: Int) = if (mime == "video/hevc") t in 16..21 else t == 5
            fun drain(c: android.media.MediaCodec) {
                while (true) {
                    val i = c.dequeueOutputBuffer(info, 0)
                    if (i < 0) { if (i == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) return else continue }
                    try {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastFrame >= gap) {
                            val img = c.getOutputImage(i)
                            if (img != null) {
                                lastFrame = now
                                try {
                                    val jpg = jpeg.make(img, quality)
                                    error = null
                                    synchronized(lock) { latest = jpg; seq++; lock.notifyAll() }
                                } finally { img.close() }
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "jpeg", e) }
                    c.releaseOutputBuffer(i, false)
                }
            }
            r.onFrame = frame@{ au, types ->
                if (codec == null) {
                    // in-band parameter sets win over the description's
                    if (types.any { isParam(it) }) { params.clear(); splitNals(au).filter { isParam(nalType(it, mime)) }.forEach { params += it } }
                    if (!types.any { isKey(it) }) return@frame
                    if (params.isEmpty()) return@frame
                    val f = android.media.MediaFormat.createVideoFormat(mime, if (hq) 1920 else 640, if (hq) 1080 else 360)
                    f.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    fun annexB(l: List<ByteArray>): java.nio.ByteBuffer {
                        val csd = java.io.ByteArrayOutputStream()
                        l.forEach { csd.write(byteArrayOf(0, 0, 0, 1)); csd.write(it) }
                        return java.nio.ByteBuffer.wrap(csd.toByteArray())
                    }
                    if (mime == "video/hevc") f.setByteBuffer("csd-0", annexB(params))
                    else {
                        val sps = params.filter { nalType(it, mime) == 7 }; val pps = params.filter { nalType(it, mime) == 8 }
                        if (sps.isEmpty() || pps.isEmpty()) return@frame
                        f.setByteBuffer("csd-0", annexB(sps.take(1))); f.setByteBuffer("csd-1", annexB(pps.take(1)))
                    }
                    val c = android.media.MediaCodec.createDecoderByType(mime)
                    c.configure(f, null, null, 0)
                    c.start()
                    codec = c
                    Log.i(TAG, "${cam.ip} decoding $mime")
                }
                val c = codec!!
                if (!gotKey) { if (types.any { isKey(it) }) gotKey = true else return@frame }
                drain(c)
                val i = c.dequeueInputBuffer(20_000)
                if (i >= 0) {
                    val ib = c.getInputBuffer(i)!!
                    ib.clear()
                    if (au.size <= ib.capacity()) {
                        ib.put(au)
                        pts += 66_000
                        c.queueInputBuffer(i, 0, au.size, pts, 0)
                    } else c.queueInputBuffer(i, 0, 0, pts, 0)
                }
                drain(c)
            }
            try { r.run() } finally {
                client = null
                r.close()
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
            }
        }

        private fun stop() {
            worker = null
            client?.close()
        }

        fun shutdown() { h.removeCallbacksAndMessages(null); h.post { stop(); thread.quitSafely() } }
    }

    /** Splits an Annex-B frame (00 00 00 01 start codes) into its NAL units. */
    internal fun splitNals(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) {
                if (start >= 0) out += b.copyOfRange(start, i)
                start = i + 4; i += 4
            } else i++
        }
        if (start in 0 until b.size) out += b.copyOfRange(start, b.size)
        return out
    }

    internal fun nalType(n: ByteArray, mime: String) =
        if (n.isEmpty()) -1 else if (mime == "video/hevc") (n[0].toInt() shr 1) and 0x3f else n[0].toInt() and 0x1f
}

/**
 * Turns a camera/decoder frame (YUV_420_888) into a JPEG, reusing its buffers between frames.
 * Frames wider than [maxW] are shrunk by half first (every other pixel): a 3 MP Tapo picture becomes
 * 1152x648, about a quarter of the data, and encodes four times faster.
 */
class YuvJpeg(private val maxW: Int = 1600) {
    private var nv = ByteArray(0)
    private var row = ByteArray(0)
    private var uArr = ByteArray(0)
    private var vArr = ByteArray(0)

    fun make(img: Image, quality: Int): ByteArray {
        val s = if (img.width > maxW) 2 else 1
        val w = (img.width / s) and 1.inv(); val h = (img.height / s) and 1.inv()
        if (nv.size != w * h * 3 / 2) nv = ByteArray(w * h * 3 / 2)
        val out = nv
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ys = yP.rowStride
        if (s == 1) {
            for (r in 0 until h) { yb.position(r * ys); yb.get(out, r * w, w) }
        } else {
            val need = w * 2
            if (row.size < need) row = ByteArray(need)
            for (r in 0 until h) {
                yb.position(r * 2 * ys); yb.get(row, 0, minOf(need, yb.remaining()))
                val o = r * w
                for (c in 0 until w) out[o + c] = row[c * 2]
            }
        }
        val ub = uP.buffer; val vb = vP.buffer
        ub.position(0); vb.position(0)
        val un = ub.remaining(); val vn = vb.remaining()
        if (uArr.size < un) uArr = ByteArray(un)
        if (vArr.size < vn) vArr = ByteArray(vn)
        ub.get(uArr, 0, un); vb.get(vArr, 0, vn)
        val urs = uP.rowStride * s; val ups = uP.pixelStride * s
        val vrs = vP.rowStride * s; val vps = vP.pixelStride * s
        var o = w * h
        for (r in 0 until h / 2) {
            val vr = r * vrs; val ur = r * urs
            for (c in 0 until w / 2) {
                val vi = vr + c * vps; val ui = ur + c * ups
                out[o++] = if (vi < vn) vArr[vi] else 0x80.toByte()
                out[o++] = if (ui < un) uArr[ui] else 0x80.toByte()
            }
        }
        val bos = ByteArrayOutputStream(w * h / 4)
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, bos)
        return bos.toByteArray()
    }
}
