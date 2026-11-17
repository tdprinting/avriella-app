package com.avriella.tv.cam

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * A small RTSP client for IP cameras (Tapo C210 and friends).
 *
 * Asks for the video over the same TCP connection (no extra ports for the router to block), logs in with
 * Digest or Basic auth, and hands back whole H.264 / H.265 frames ready for a hardware decoder.
 * Written by hand because Android's built-in player fails Tapo's login.
 */
class Rtsp(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val user: String,
    private val pass: String,
) {
    class AuthFailed : Exception("login refused")

    /** Called once the video kind is known: "video/avc" or "video/hevc", plus any parameter sets from the description. */
    var onFormat: (mime: String, params: List<ByteArray>) -> Unit = { _, _ -> }
    /** One whole frame (all its NAL units, each with a 00 00 00 01 start code). */
    var onFrame: (au: ByteArray, nalTypes: IntArray) -> Unit = { _, _ -> }

    private var sock: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var out: OutputStream
    private var cseq = 0
    private var session: String? = null
    private var authHeader: ((method: String, uri: String) -> String)? = null
    @Volatile private var closed = false
    private var hevc = false
    private var pt = -1

    private val baseUrl = "rtsp://$host:$port/$path"

    fun close() { closed = true; try { sock?.close() } catch (_: Exception) {} }

    /** Only checks the login (for the "Save" button). Throws AuthFailed or a network error. */
    fun check() {
        try { connect(); val r = describe(); if (r.code != 200) throw Exception("camera said ${r.code}") } finally { close() }
    }

    private fun describe(): Resp {
        var r = request("DESCRIBE", baseUrl, "Accept: application/sdp\r\n")
        if (r.code == 401) {
            authHeader = makeAuth(r.headersAll("www-authenticate"))
            val ch = r.headersAll("www-authenticate")
            r = request("DESCRIBE", baseUrl, "Accept: application/sdp\r\n")
            if (r.code == 401 && ch.any { it.startsWith("Basic", true) }) {
                Log.i(TAG, "digest refused, trying basic")
                authHeader = makeAuth(ch.filter { it.startsWith("Basic", true) })
                r = request("DESCRIBE", baseUrl, "Accept: application/sdp\r\n")
            }
            if (r.code == 401) throw AuthFailed()
        }
        return r
    }

    private fun connect() {
        val s = Socket()
        sock = s
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 15_000
        s.tcpNoDelay = true
        input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
        out = s.getOutputStream()
    }

    /** Connects, logs in and plays; returns only when the connection ends (throws on errors). */
    fun run() {
        connect()
        // 1) description of the streams (first try without a login, to learn how the camera wants it)
        var r = describe()
        if (r.code != 200) throw Exception("camera said ${r.code} to DESCRIBE")
        val base = (r.header("content-base") ?: r.header("content-location") ?: baseUrl)
        val sdp = String(r.body)
        val control = parseSdp(sdp)
        val track = when {
            control == null || control == "*" -> base
            control.startsWith("rtsp://") -> control
            else -> base.trimEnd('/') + "/" + control
        }

        // 2) ask for the video inside this same TCP connection
        r = request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        if (r.code != 200) throw Exception("camera said ${r.code} to SETUP")
        session = r.header("session")?.substringBefore(';')?.trim()

        // 3) play
        r = request("PLAY", base, "Range: npt=0.000-\r\n")
        if (r.code != 200) throw Exception("camera said ${r.code} to PLAY")

        readMedia()
    }

    // ---------- RTSP messages ----------
    private class Resp(val code: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(n: String) = headers.firstOrNull { it.first == n }?.second
        fun headersAll(n: String) = headers.filter { it.first == n }.map { it.second }
    }

    private fun send(method: String, uri: String, extra: String) {
        cseq++
        val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: AvriellaTV\r\n")
        authHeader?.let { sb.append("Authorization: ").append(it(method, uri)).append("\r\n") }
        session?.let { sb.append("Session: ").append(it).append("\r\n") }
        sb.append(extra).append("\r\n")
        synchronized(this) { out.write(sb.toString().toByteArray()); out.flush() }
    }

    private fun request(method: String, uri: String, extra: String): Resp {
        send(method, uri, extra)
        while (true) {
            val first = input.readUnsignedByte()
            if (first == '$'.code) { skipInterleaved(); continue }
            val r = readResponse(first)
            return r
        }
    }

    private fun readLine(firstByte: Int = -1): String {
        val sb = StringBuilder()
        if (firstByte >= 0) sb.append(firstByte.toChar())
        while (true) {
            val c = input.readUnsignedByte()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) throw Exception("header too long")
        }
        return sb.toString()
    }

    private fun readResponse(firstByte: Int): Resp {
        val status = readLine(firstByte)
        if (!status.startsWith("RTSP/")) throw Exception("not RTSP: ${status.take(40)}")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val hs = ArrayList<Pair<String, String>>()
        while (true) {
            val l = readLine()
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) hs += l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }
        val len = hs.firstOrNull { it.first == "content-length" }?.second?.toIntOrNull() ?: 0
        val body = ByteArray(len.coerceIn(0, 1 shl 20))
        input.readFully(body)
        return Resp(code, hs, body)
    }

    private fun skipInterleaved() {
        input.readUnsignedByte()
        val n = input.readUnsignedShort()
        input.skipBytes(n)
    }

    // ---------- login ----------
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun param(h: String, k: String): String? =
        Regex("""(?i)\b$k\s*=\s*(?:"([^"]*)"|([^,\s]+))""").find(h)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private fun makeAuth(challenges: List<String>): (String, String) -> String {
        val digest = challenges.firstOrNull { it.startsWith("Digest", true) }
        if (digest != null) {
            val realm = param(digest, "realm") ?: ""
            val nonce = param(digest, "nonce") ?: ""
            val opaque = param(digest, "opaque")
            val qop = param(digest, "qop")?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            var nc = 0
            return { method, uri ->
                val ha1 = md5("$user:$realm:$pass")
                val ha2 = md5("$method:$uri")
                val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
                if (qop != null) {
                    nc++
                    val ncs = "%08x".format(nc)
                    val cnonce = md5(System.nanoTime().toString()).take(16)
                    sb.append(", response=\"${md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2")}\", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
                } else sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
                opaque?.let { sb.append(", opaque=\"$it\"") }
                sb.toString()
            }
        }
        val basic = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        return { _, _ -> basic }
    }

    // ---------- stream description ----------
    /** Finds the video track; returns its control URL (or null). */
    private fun parseSdp(sdp: String): String? {
        var inVideo = false
        var control: String? = null
        val params = ArrayList<ByteArray>()
        for (raw in sdp.lines()) {
            val l = raw.trim()
            if (l.startsWith("m=")) {
                if (inVideo) break
                inVideo = l.startsWith("m=video")
                if (inVideo) pt = l.split(' ').getOrNull(3)?.toIntOrNull() ?: -1
                continue
            }
            if (!inVideo) continue
            when {
                l.startsWith("a=control:") -> control = l.substring(10).trim()
                l.startsWith("a=rtpmap:") -> hevc = l.contains("H265", true) || l.contains("HEVC", true)
                l.startsWith("a=fmtp:") -> {
                    fun b64(k: String) = param(l, k)
                    b64("sprop-parameter-sets")?.split(',')?.forEach { p -> dec(p)?.let { params += it } }
                    listOf("sprop-vps", "sprop-sps", "sprop-pps").forEach { k -> b64(k)?.let { dec(it)?.let { b -> params += b } } }
                }
            }
        }
        onFormat(if (hevc) "video/hevc" else "video/avc", params)
        return control
    }

    private fun dec(s: String): ByteArray? = try { Base64.decode(s.trim(), Base64.DEFAULT).takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    // ---------- video packets ----------
    private val au = java.io.ByteArrayOutputStream(256 * 1024)
    private val types = ArrayList<Int>()
    private val fu = java.io.ByteArrayOutputStream(256 * 1024)
    private var fuOn = false
    private var buf = ByteArray(65536)

    private fun nal(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        au.write(START, 0, 4); au.write(b, off, len)
        types += if (hevc) (b[off].toInt() shr 1) and 0x3f else b[off].toInt() and 0x1f
    }

    private fun flushAu() {
        if (au.size() > 0) {
            val t = types.toIntArray()
            val bytes = au.toByteArray()
            au.reset(); types.clear()
            onFrame(bytes, t)
        }
    }

    private fun readMedia() {
        var lastPing = System.currentTimeMillis()
        while (!closed) {
            val first = input.readUnsignedByte()
            if (first != '$'.code) { readResponse(first); continue } // answer to a keep-alive
            val ch = input.readUnsignedByte()
            val n = input.readUnsignedShort()
            if (buf.size < n) buf = ByteArray(n)
            input.readFully(buf, 0, n)
            if (ch == 0 && n > 12) rtp(buf, n)
            val now = System.currentTimeMillis()
            if (now - lastPing > 25_000) { lastPing = now; send("GET_PARAMETER", baseUrl, "") }
        }
    }

    private fun rtp(p: ByteArray, n: Int) {
        val v0 = p[0].toInt() and 0xff
        val marker = (p[1].toInt() and 0x80) != 0
        val type = p[1].toInt() and 0x7f
        if (pt >= 0 && type != pt) return
        var off = 12 + (v0 and 0x0f) * 4
        if ((v0 and 0x10) != 0 && off + 4 <= n) off += 4 + (((p[off + 2].toInt() and 0xff) shl 8) or (p[off + 3].toInt() and 0xff)) * 4
        var end = n
        if ((v0 and 0x20) != 0) end -= p[n - 1].toInt() and 0xff
        if (off >= end) return
        if (hevc) h265(p, off, end) else h264(p, off, end)
        if (marker) flushAu()
    }

    private fun h264(p: ByteArray, off: Int, end: Int) {
        val t = p[off].toInt() and 0x1f
        when (t) {
            in 1..23 -> nal(p, off, end - off)
            24 -> { // STAP-A: several small NAL units
                var i = off + 1
                while (i + 2 <= end) {
                    val sz = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff); i += 2
                    if (i + sz > end) break
                    nal(p, i, sz); i += sz
                }
            }
            28 -> { // FU-A: one NAL unit in pieces
                if (off + 2 > end) return
                val h = p[off + 1].toInt() and 0xff
                if (h and 0x80 != 0) { fu.reset(); fu.write((p[off].toInt() and 0xe0) or (h and 0x1f)); fuOn = true }
                if (!fuOn) return
                fu.write(p, off + 2, end - off - 2)
                if (h and 0x40 != 0) { val a = fu.toByteArray(); nal(a, 0, a.size); fuOn = false }
            }
        }
    }

    private fun h265(p: ByteArray, off: Int, end: Int) {
        if (off + 2 > end) return
        val t = (p[off].toInt() shr 1) and 0x3f
        when (t) {
            48 -> { // aggregation packet
                var i = off + 2
                while (i + 2 <= end) {
                    val sz = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff); i += 2
                    if (i + sz > end) break
                    nal(p, i, sz); i += sz
                }
            }
            49 -> { // fragmentation unit
                if (off + 3 > end) return
                val h = p[off + 2].toInt() and 0xff
                if (h and 0x80 != 0) {
                    fu.reset()
                    fu.write((p[off].toInt() and 0x81) or ((h and 0x3f) shl 1)); fu.write(p[off + 1].toInt() and 0xff); fuOn = true
                }
                if (!fuOn) return
                fu.write(p, off + 3, end - off - 3)
                if (h and 0x40 != 0) { val a = fu.toByteArray(); nal(a, 0, a.size); fuOn = false }
            }
            else -> nal(p, off, end - off)
        }
    }

    companion object {
        private val START = byteArrayOf(0, 0, 0, 1)
        private const val TAG = "Rtsp"
        fun log(msg: String) = Log.i(TAG, msg)
    }
}
