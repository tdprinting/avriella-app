package com.avriella.tv.cam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import android.util.Log
import com.avriella.tv.data.Library
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocketFactory

/**
 * "From anywhere" mode, with no extra apps or accounts.
 *
 * Her phone keeps a small outgoing connection to free public message relays (MQTT brokers).
 * The iPhone app sends its requests there and her phone answers them, so it works from work,
 * on cell data, anywhere â€” no router setup, no VPN.
 *
 * Everything is end-to-end encrypted (AES-256-GCM) with a secret that only her phone and your
 * paired iPhones know (it's handed over once, on your home Wi-Fi). The relay only ever sees
 * scrambled bytes on a random-looking channel name.
 */
class RelayClient(private val ctx: Context) {
    private class Broker(val host: String, val port: Int)
    private val brokers = listOf(Broker("broker.emqx.io", 8883), Broker("broker.hivemq.com", 8883))

    @Volatile private var running = false
    /** Bumped on every start/stop, so an old loop never keeps running next to a new one. */
    @Volatile private var gen = 0
    private val pool = Executors.newCachedThreadPool()
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 400
    }
    private val uploads = ConcurrentHashMap<String, Array<ByteArray?>>()
    private val rnd = SecureRandom()
    /** Recent answers, so a request the iPhone repeats (its answer got lost) is answered again right away. */
    private class Sent(val at: Long, val topic: String, val packets: List<ByteArray>, var resentAt: Long = 0)
    private val answers = object : LinkedHashMap<String, Sent>(64, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Sent>?) = size > 40
    }
    private val wake = Object()
    private var net: ConnectivityManager.NetworkCallback? = null
    @Volatile var connectedAt = 0L; private set
    @Volatile var downSince = System.currentTimeMillis(); private set

    fun start() {
        if (running) return
        running = true
        val g = ++gen
        instance = this
        brokers.forEach { b -> Thread({ loop(b, g) }, "relay-" + b.host).apply { isDaemon = true; start() } }
        watchNetwork()
    }

    fun stop() {
        running = false; gen++
        if (instance === this) instance = null
        try { net?.let { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        net = null
        dropAll()
        synchronized(wake) { wake.notifyAll() }
    }

    /** Drops every relay connection and reconnects at once (network changed, or the watchdog found it stuck). */
    fun kick(why: String) {
        if (!running) return
        Log.i(TAG, "reconnecting the relay now: $why")
        kicks++
        dropAll()
        synchronized(wake) { wake.notifyAll() }
    }
    @Volatile private var kicks = 0

    private fun dropAll() { socks.values.forEach { try { it.close() } catch (_: Exception) {} } }

    private val socks = ConcurrentHashMap<String, java.net.Socket>()

    /** Wi-Fi drops, comes back, or the router restarts: reconnect straight away instead of waiting out a timeout. */
    private fun watchNetwork() {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var current: Network? = cm.activeNetwork
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    if (n != current) { current = n; kick("network came back") }
                }
                override fun onLost(n: Network) {
                    if (n == current) { current = null; dropAll() }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            net = cb
        } catch (e: Exception) { Log.w(TAG, "can't watch the network", e) }
    }

    private fun loop(b: Broker, g: Int) {
        var backoff = 1000L
        while (running && g == gen) {
            val began = System.currentTimeMillis()
            try {
                session(b, g)
            } catch (e: Throwable) { // never let this thread die (it's her phone's only way to be reached from away)
                if (running) Log.i(TAG, "relay ${b.host} disconnected: ${e.message}")
            }
            if (!running || g != gen) break
            // stayed up a while: that was a normal drop, come back fast
            if (System.currentTimeMillis() - began > 60_000L) backoff = 1000L
            val k = kicks
            synchronized(wake) { try { if (k == kicks) wake.wait(backoff + rnd.nextInt(800)) } catch (_: InterruptedException) {} }
            backoff = if (k != kicks) 1000L else (backoff * 2).coerceAtMost(30_000L)
        }
    }

    // ---------------- MQTT 3.1.1 (just the parts we need) ----------------

    /** Sends to every relay that's connected right now (used when an answer has to get through). */
    private val senders = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    private fun session(b: Broker, g: Int) {
        val secret = Library.settings.relaySecret
        if (secret.isEmpty()) { synchronized(wake) { wake.wait(10_000) }; return }
        val key = keyFor(secret)
        val topic = topicFor(secret)
        val raw = java.net.Socket()
        socks[b.host] = raw // so a network change can cut even a connection that's still being made
        val sock: javax.net.ssl.SSLSocket
        try {
            raw.connect(InetSocketAddress(b.host, b.port), 15_000)
            raw.soTimeout = 20_000
            sock = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, b.host, b.port, true) as javax.net.ssl.SSLSocket
            sock.startHandshake()
            // the relay must prove it really is that relay (certificate checked against the host name)
            val hv = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
            if (!hv.verify(b.host, sock.session)) throw javax.net.ssl.SSLPeerUnverifiedException("certificate isn't for ${b.host}")
        } catch (e: Throwable) { socks.remove(b.host); try { raw.close() } catch (_: Exception) {}; throw e }
        socks[b.host] = sock
        if (g != gen) { socks.remove(b.host); sock.close(); return }
        // Pings every 20 s; if nothing at all comes back for 45 s the line is dead (e.g. the router
        // rebooted) and we reconnect, instead of sitting on a silent connection for minutes.
        sock.soTimeout = 45_000
        sock.keepAlive = true
        val out = sock.getOutputStream()
        val inp = BufferedInputStream(sock.getInputStream())
        val writeLock = Any()
        fun send(pkt: ByteArray) = synchronized(writeLock) { out.write(pkt); out.flush() }

        val cid = "avr-" + hex(ByteArray(6).also { rnd.nextBytes(it) })
        send(packet(0x10, str("MQTT") + byteArrayOf(4, 0x02, 0, 30) + str(cid)))
        val (t0, ack) = read(inp)
        if (t0 shr 4 != 2 || (ack.size >= 2 && ack[1].toInt() != 0)) throw IllegalStateException("relay refused the connection")
        send(packet(0x82, byteArrayOf(0, 1) + str("avriella/$topic/q") + byteArrayOf(0)))
        val (t1, _) = read(inp)
        if (t1 shr 4 != 9) throw IllegalStateException("no SUBACK")
        Log.i(TAG, "relay connected to ${b.host}")
        val pub: (String, ByteArray) -> Unit = { t, p -> send(pubPacket(t, p)) }
        senders[b.host] = { p -> send(p) }
        CamState.relay = true
        connectedAt = System.currentTimeMillis()

        // keep-alive pings
        val pinger = Thread {
            try { while (running && !sock.isClosed) { Thread.sleep(20_000); send(byteArrayOf(0xC0.toByte(), 0)) } }
            catch (_: Exception) { try { sock.close() } catch (_: Exception) {} }
        }.apply { isDaemon = true; start() }

        try {
            while (running && g == gen) {
                val (type, body) = read(inp)
                if (type shr 4 != 3) continue // only PUBLISH matters
                val tlen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                var off = 2 + tlen
                if ((type shr 1) and 3 > 0) off += 2 // QoS>0 packet id (we subscribe QoS 0, but be safe)
                val payload = body.copyOfRange(off, body.size)
                pool.execute { try { onRequest(payload, key, topic, pub) } catch (e: Throwable) { Log.w(TAG, "relay request failed", e) } }
            }
        } finally {
            socks.remove(b.host)
            senders.remove(b.host)
            pinger.interrupt()
            try { sock.close() } catch (_: Exception) {}
            if (senders.isEmpty()) { CamState.relay = false; downSince = System.currentTimeMillis() }
        }
    }

    private fun pubPacket(topic: String, payload: ByteArray) = packet(0x30, str(topic) + payload)

    private fun packet(type: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(type)
        var len = body.size
        do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; o.write(d) } while (len > 0)
        o.write(body)
        return o.toByteArray()
    }

    private fun str(s: String): ByteArray { val b = s.toByteArray(); return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b }

    private fun read(i: InputStream): Pair<Int, ByteArray> {
        val type = i.read(); if (type < 0) throw java.io.EOFException()
        var mult = 1; var len = 0
        while (true) { val d = i.read(); if (d < 0) throw java.io.EOFException(); len += (d and 0x7F) * mult; if (d and 0x80 == 0) break; mult *= 128 }
        val body = ByteArray(len); var got = 0
        while (got < len) { val r = i.read(body, got, len - got); if (r < 0) throw java.io.EOFException(); got += r }
        return type to body
    }

    // ---------------- requests ----------------

    private fun onRequest(payload: ByteArray, key: SecretKeySpec, topic: String, publish: (String, ByteArray) -> Unit) {
        val j = JSONObject(String(decrypt(payload, key) ?: return, Charsets.UTF_8))
        val id = j.optString("id"); val cid = j.optString("cid")
        if (id.isEmpty() || !cid.matches(Regex("[A-Za-z0-9_-]{4,40}"))) return
        if (j.has("s") || !j.has("p")) return // that's an answer, not a request (someone bouncing our own messages back)
        if (Math.abs(System.currentTimeMillis() - j.optLong("ts")) > 3 * 60_000L) return // stale / replayed
        val seq = j.optInt("seq", 0); val n = j.optInt("n", 1).coerceIn(1, 600)
        synchronized(seen) {
            val k = "$id:$seq"
            if (seen.containsKey(k)) {
                // The iPhone asked again: its answer got lost on the way. Send it again, through every relay.
                val a = synchronized(answers) { answers[id] }
                val now = System.currentTimeMillis()
                if (a != null && now - a.at > 2500 && now - a.resentAt > 2500) {
                    a.resentAt = now
                    pool.execute { senders.values.forEach { s -> a.packets.forEach { p -> try { s(pubPacket(a.topic, p)) } catch (_: Exception) {} } } }
                }
                return
            }
            seen[k] = System.currentTimeMillis()
        }

        // big uploads (photos) arrive in pieces
        var body: ByteArray? = null
        if (j.has("b")) {
            val part = Base64.decode(j.getString("b"), Base64.DEFAULT)
            if (n == 1) body = part else {
                val parts = uploads.getOrPut(id) { arrayOfNulls(n) }
                if (seq in parts.indices) parts[seq] = part
                if (parts.any { it == null }) return
                uploads.remove(id)
                body = ByteArrayOutputStream().apply { parts.forEach { write(it!!) } }.toByteArray()
            }
        }
        val path = j.optString("p")
        if (!path.startsWith("/api/") || path.startsWith("/api/stream") || path.startsWith("/api/tapo/stream")) return
        val (status, type, data) = local(j.optString("m", "GET"), path, body)

        val chunk = 45_000
        val total = ((data.size + chunk - 1) / chunk).coerceAtLeast(1)
        val at = "avriella/$topic/a/$cid"
        val packets = ArrayList<ByteArray>(total)
        for (s in 0 until total) {
            val piece = data.copyOfRange(s * chunk, minOf(data.size, (s + 1) * chunk))
            val r = JSONObject().put("id", id).put("s", status).put("t", type).put("seq", s).put("n", total)
                .put("b", Base64.encodeToString(piece, Base64.NO_WRAP))
            val p = encrypt(r.toString().toByteArray(), key)
            packets.add(p)
            try { publish(at, p) } catch (e: Exception) {
                // the relay it came in on just dropped: send it through the other one instead
                senders.values.firstOrNull()?.let { s2 -> try { s2(pubPacket(at, p)) } catch (_: Exception) {} }
            }
        }
        if (data.size <= 400_000) synchronized(answers) { answers[id] = Sent(System.currentTimeMillis(), at, packets) }
    }

    /** Runs the request against her phone's own web server (so it behaves exactly like at home). */
    private fun local(method: String, path: String, body: ByteArray?): Triple<Int, String, ByteArray> {
        return try {
            val c = URL("http://127.0.0.1:${CamState.port}$path").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 20_000
            c.requestMethod = if (method == "POST") "POST" else "GET"
            if (body != null && method == "POST") { c.doOutput = true; c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            Triple(code, c.contentType ?: "application/octet-stream", bytes)
        } catch (e: Exception) {
            Triple(502, "application/json", "{\"error\":\"Her phone couldn't do that.\"}".toByteArray())
        }
    }

    // ---------------- crypto ----------------

    private fun encrypt(plain: ByteArray, key: SecretKeySpec): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray, key: SecretKeySpec): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        c.doFinal(data, 12, data.size - 12)
    } catch (_: Exception) { null }

    companion object {
        private const val TAG = "AvriellaRelay"
        /** The running relay (for the watchdog). */
        @Volatile var instance: RelayClient? = null
        fun sha(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        fun keyFor(secret: String) = SecretKeySpec(sha("avriella-key|$secret"), "AES")
        fun topicFor(secret: String) = hex(sha("avriella-topic|$secret")).take(32)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun newSecret(): String {
            val b = ByteArray(24); SecureRandom().nextBytes(b)
            return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}
