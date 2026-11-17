package com.avriella.tv.cam

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
 * on cell data, anywhere — no router setup, no VPN.
 *
 * Everything is end-to-end encrypted (AES-256-GCM) with a secret that only her phone and your
 * paired iPhones know (it's handed over once, on your home Wi-Fi). The relay only ever sees
 * scrambled bytes on a random-looking channel name.
 */
class RelayClient {
    private class Broker(val host: String, val port: Int)
    private val brokers = listOf(Broker("broker.emqx.io", 8883), Broker("broker.hivemq.com", 8883))

    @Volatile private var running = false
    private val pool = Executors.newCachedThreadPool()
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 400
    }
    private val uploads = ConcurrentHashMap<String, Array<ByteArray?>>()
    private val rnd = SecureRandom()

    fun start() {
        if (running) return
        running = true
        brokers.forEach { b -> Thread({ loop(b) }, "relay-" + b.host).apply { isDaemon = true; start() } }
    }

    fun stop() { running = false; socks.values.forEach { try { it.close() } catch (_: Exception) {} } }

    private val socks = ConcurrentHashMap<String, java.net.Socket>()

    private fun loop(b: Broker) {
        var backoff = 2000L
        while (running) {
            try {
                session(b)
                backoff = 2000L
            } catch (e: Exception) {
                if (running) Log.i(TAG, "relay ${b.host} disconnected: ${e.message}")
            }
            if (!running) break
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    // ---------------- MQTT 3.1.1 (just the parts we need) ----------------

    private fun session(b: Broker) {
        val secret = Library.settings.relaySecret
        if (secret.isEmpty()) { Thread.sleep(10_000); return }
        val key = keyFor(secret)
        val topic = topicFor(secret)
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(b.host, b.port), 15_000)
        val sock = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, b.host, b.port, true) as javax.net.ssl.SSLSocket
        sock.startHandshake()
        socks[b.host] = sock
        sock.soTimeout = 75_000
        val out = sock.getOutputStream()
        val inp = BufferedInputStream(sock.getInputStream())
        val writeLock = Any()
        fun send(pkt: ByteArray) = synchronized(writeLock) { out.write(pkt); out.flush() }

        val cid = "avr-" + hex(ByteArray(6).also { rnd.nextBytes(it) })
        send(packet(0x10, str("MQTT") + byteArrayOf(4, 0x02, 0, 60) + str(cid)))
        val (t0, _) = read(inp)
        if (t0 shr 4 != 2) throw IllegalStateException("no CONNACK")
        send(packet(0x82, byteArrayOf(0, 1) + str("avriella/$topic/q") + byteArrayOf(0)))
        Log.i(TAG, "relay connected to ${b.host}")
        CamState.relay = true

        // keep-alive pings
        val pinger = Thread {
            try { while (running && !sock.isClosed) { Thread.sleep(45_000); send(byteArrayOf(0xC0.toByte(), 0)) } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        try {
            while (running) {
                val (type, body) = read(inp)
                if (type shr 4 != 3) continue // only PUBLISH matters
                val tlen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                var off = 2 + tlen
                if ((type shr 1) and 3 > 0) off += 2 // QoS>0 packet id (we subscribe QoS 0, but be safe)
                val payload = body.copyOfRange(off, body.size)
                val pub: (String, ByteArray) -> Unit = { t, p -> send(publish(t, p)) }
                pool.execute { try { onRequest(payload, key, topic, pub) } catch (e: Exception) { Log.w(TAG, "relay request failed", e) } }
            }
        } finally {
            socks.remove(b.host)
            pinger.interrupt()
            try { sock.close() } catch (_: Exception) {}
            if (socks.isEmpty()) CamState.relay = false
        }
    }

    private fun publish(topic: String, payload: ByteArray) = packet(0x30, str(topic) + payload)

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
        if (Math.abs(System.currentTimeMillis() - j.optLong("ts")) > 10 * 60_000L) return // stale / replayed
        val seq = j.optInt("seq", 0); val n = j.optInt("n", 1).coerceIn(1, 600)
        synchronized(seen) { val k = "$id:$seq"; if (seen.containsKey(k)) return; seen[k] = System.currentTimeMillis() }

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
        for (s in 0 until total) {
            val piece = data.copyOfRange(s * chunk, minOf(data.size, (s + 1) * chunk))
            val r = JSONObject().put("id", id).put("s", status).put("t", type).put("seq", s).put("n", total)
                .put("b", Base64.encodeToString(piece, Base64.NO_WRAP))
            publish("avriella/$topic/a/$cid", encrypt(r.toString().toByteArray(), key))
        }
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
