package com.avriella.tv.cam

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Answers "where is avriella.local?" on the local network (multicast DNS, the same thing AirPlay
 * and printers use). iPhones look .local names up automatically, so the Baby Cam app can always
 * open http://avriella.local:8080 — no IP addresses to type, even when the Wi-Fi hands out a new one
 * or you're on the iPhone's hotspot in the car.
 */
class MdnsResponder(private val ctx: Context, private val host: String = "avriella") {
    private var sock: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    private val group = InetAddress.getByName("224.0.0.251")
    private val fqdn = "$host.local"

    fun start() {
        if (running) return
        running = true
        Thread({ run() }, "mdns").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { sock?.close() } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
    }

    private fun run() {
        try {
            lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("avriella:mdns").apply { setReferenceCounted(false); acquire() }
            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(5353))
            s.timeToLive = 255
            joinAll(s)
            sock = s
            announce(s)
            val buf = ByteArray(9000)
            var lastJoin = System.currentTimeMillis()
            s.soTimeout = 5000
            while (running) {
                // Networks come and go (Wi-Fi ↔ hotspot); re-join every so often.
                if (System.currentTimeMillis() - lastJoin > 30_000) { joinAll(s); announce(s); lastJoin = System.currentTimeMillis() }
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                try { handle(s, p) } catch (e: Exception) { Log.w(TAG, "bad mdns packet", e) }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "mdns stopped", e)
        }
    }

    private fun joinAll(s: MulticastSocket) {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                if (ni.inetAddresses.toList().none { it is Inet4Address }) continue
                try { s.joinGroup(InetSocketAddress(group, 5353), ni) } catch (_: Exception) { /* already joined */ }
            }
        } catch (_: Exception) {}
    }

    /** Our IPv4 address on the same network as [peer] (falls back to the Wi-Fi address). */
    private fun addressFor(peer: InetAddress?): Inet4Address? {
        val all = ArrayList<Pair<Inet4Address, Short>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                if (ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni")) continue
                for (ia in ni.interfaceAddresses) { val a = ia.address; if (a is Inet4Address) all += a to ia.networkPrefixLength }
            }
        } catch (_: Exception) {}
        if (peer is Inet4Address) {
            val pb = peer.address
            all.firstOrNull { (a, len) -> sameNet(a.address, pb, len.toInt()) }?.let { return it.first }
        }
        return all.firstOrNull()?.first
    }

    private fun sameNet(a: ByteArray, b: ByteArray, len: Int): Boolean {
        var bits = len
        for (i in 0 until 4) {
            val m = if (bits >= 8) 0xFF else if (bits <= 0) 0 else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and m) != (b[i].toInt() and m)) return false
            bits -= 8
        }
        return true
    }

    private fun announce(s: MulticastSocket) {
        val ip = addressFor(null) ?: return
        val r = answer(0, null, ip)
        try { s.send(DatagramPacket(r, r.size, group, 5353)) } catch (_: Exception) {}
    }

    private fun handle(s: MulticastSocket, p: DatagramPacket) {
        val d = p.data; val n = p.length
        if (n < 12) return
        val flags = u16(d, 2)
        if (flags and 0x8000 != 0) return // a response, not a question
        val qd = u16(d, 4)
        var off = 12
        repeat(qd) {
            val (name, next) = readName(d, off, n) ?: return
            if (next + 4 > n) return
            val qtype = u16(d, next); val qclass = u16(d, next + 2)
            val qEnd = next + 4
            if (name.equals(fqdn, ignoreCase = true) && (qtype == 1 || qtype == 255)) {
                val ip = addressFor(p.address) ?: return
                val unicastWanted = qclass and 0x8000 != 0
                if (p.port != 5353) {
                    // "legacy" one-shot query: reply straight back with the question included
                    val r = answer(u16(d, 0), d.copyOfRange(off, qEnd), ip)
                    s.send(DatagramPacket(r, r.size, p.address, p.port))
                } else {
                    val r = answer(0, null, ip)
                    if (unicastWanted) s.send(DatagramPacket(r, r.size, p.address, 5353))
                    s.send(DatagramPacket(r, r.size, group, 5353))
                }
                return
            }
            off = qEnd
        }
    }

    private fun answer(id: Int, question: ByteArray?, ip: Inet4Address): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun w16(v: Int) { o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }
        w16(id); w16(0x8400); w16(if (question != null) 1 else 0); w16(1); w16(0); w16(0)
        if (question != null) o.write(question)
        for (label in fqdn.split('.')) { val b = label.toByteArray(); o.write(b.size); o.write(b) }
        o.write(0)
        w16(1)                                   // type A
        w16(if (question != null) 1 else 0x8001) // class IN (+ cache-flush on multicast)
        w16(0); w16(120)                         // TTL 120 s
        w16(4); o.write(ip.address)
        return o.toByteArray()
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    /** Reads a DNS name (with compression pointers). Returns the name and the offset after it. */
    private fun readName(d: ByteArray, start: Int, n: Int): Pair<String, Int>? {
        val parts = ArrayList<String>()
        var i = start; var end = -1; var hops = 0
        while (i < n) {
            val len = d[i].toInt() and 0xFF
            if (len == 0) { if (end < 0) end = i + 1; return parts.joinToString(".") to end }
            if (len and 0xC0 == 0xC0) {
                if (i + 1 >= n || ++hops > 10) return null
                if (end < 0) end = i + 2
                i = ((len and 0x3F) shl 8) or (d[i + 1].toInt() and 0xFF)
                continue
            }
            if (i + 1 + len > n) return null
            parts += String(d, i + 1, len, Charsets.UTF_8)
            i += 1 + len
        }
        return null
    }

    companion object { private const val TAG = "AvriellaMdns" }
}
