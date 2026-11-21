package com.avriella.tv.cam

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * A tiny ADB client, just enough to ask a Fire TV on the home Wi-Fi to open a web page.
 * Works on Fire TVs where "ADB debugging" is turned on (Settings → My Fire TV → Developer options).
 * The first time, the TV asks "Allow debugging?"; tick "Always allow from this computer" and press OK.
 */
object Adb {
    private const val TAG = "AvriellaAdb"
    private const val CNXN = 0x4e584e43; private const val AUTH = 0x48545541; private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f; private const val CLSE = 0x45534c43; private const val WRTE = 0x45545257

    fun portOpen(host: String): Boolean = try { Socket().use { it.connect(InetSocketAddress(host, 5555), 1200); true } } catch (_: Exception) { false }

    /** Runs a shell command on the TV. Returns its output, or null if the TV wouldn't let us in. */
    fun shell(ctx: Context, host: String, cmd: String, approveWaitMs: Int = 30_000): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 5555), 3000)
            s.soTimeout = 8000
            val inp = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
            send(out, CNXN, 0x01000000, 256 * 1024, "host::\u0000".toByteArray())
            var m = read(inp)
            var sentKey = false; var signed = false
            while (m.cmd == AUTH) {
                if (!signed) { send(out, AUTH, 2, 0, sign(ctx, m.data)); signed = true }
                else if (!sentKey) {
                    // not known yet: offer our key, the TV asks the grown-up to allow it
                    send(out, AUTH, 3, 0, publicKey(ctx)); sentKey = true
                    s.soTimeout = approveWaitMs
                } else return null
                m = read(inp)
            }
            if (m.cmd != CNXN) return null
            s.soTimeout = 8000
            send(out, OPEN, 1, 0, "shell:$cmd\u0000".toByteArray())
            val text = StringBuilder()
            while (true) {
                val r = read(inp)
                when (r.cmd) {
                    OKAY -> {}
                    WRTE -> { text.append(String(r.data)); send(out, OKAY, 1, r.arg0, ByteArray(0)) }
                    CLSE -> { send(out, CLSE, 1, r.arg0, ByteArray(0)); return text.toString() }
                    else -> return text.toString()
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    } catch (e: Exception) { Log.i(TAG, "adb $host: ${e.message}"); null }

    // ---------- a shell kept open on each TV, so remote button presses land straight away ----------

    private class Live(val host: String, val s: Socket, val out: OutputStream, val inp: DataInputStream) {
        @Volatile var alive = true
        @Volatile var lastUsed = System.currentTimeMillis()
        @Volatile var remote = 0
        val okay = java.util.concurrent.Semaphore(0)
        val opened = java.util.concurrent.CountDownLatch(1)

        fun start(): Boolean {
            s.soTimeout = 0
            Thread {
                try {
                    while (alive) {
                        val m = read(inp)
                        when (m.cmd) {
                            OKAY -> if (remote == 0) { remote = m.arg0; opened.countDown() } else okay.release()
                            WRTE -> send(out, OKAY, 1, m.arg0, ByteArray(0))   // the shell's echo: just say thanks
                            CLSE -> { alive = false }
                        }
                    }
                } catch (_: Exception) { alive = false } finally { opened.countDown(); close() }
            }.apply { isDaemon = true }.start()
            send(out, OPEN, 1, 0, "shell:\u0000".toByteArray())
            opened.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (remote == 0) { alive = false; close() }
            return alive
        }

        fun run(cmd: String): Boolean = synchronized(this) {
            if (!alive) return false
            okay.drainPermits()
            return try {
                send(out, WRTE, 1, remote, (cmd + "\n").toByteArray())
                lastUsed = System.currentTimeMillis()
                okay.tryAcquire(4, java.util.concurrent.TimeUnit.SECONDS).also { if (!it) { alive = false; close() } }
            } catch (_: Exception) { alive = false; close(); false }
        }

        fun close() { try { s.close() } catch (_: Exception) {} }
    }

    private val lives = java.util.concurrent.ConcurrentHashMap<String, Live>()

    /**
     * Runs a quick command on the TV through a shell that stays open (no handshake each time).
     * Returns "ok", or why it couldn't ("off" = ADB debugging is off, "denied" = nobody pressed OK on the TV).
     */
    fun quick(ctx: Context, host: String, cmd: String): String {
        val now = System.currentTimeMillis()
        lives.values.filter { now - it.lastUsed > 10 * 60_000 }.forEach { it.alive = false; it.close(); lives.remove(it.host) }
        for (attempt in 0..1) {
            var l = lives[host]
            if (l == null || !l.alive) {
                if (!portOpen(host)) return "off"
                l = connect(ctx, host) ?: return "denied"
                lives[host] = l
            }
            if (l.run(cmd)) return "ok"
            lives.remove(host)
        }
        return "denied"
    }

    private fun connect(ctx: Context, host: String): Live? = try {
        val s = Socket(); s.connect(InetSocketAddress(host, 5555), 3000); s.tcpNoDelay = true
        s.soTimeout = 8000
        val inp = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
        send(out, CNXN, 0x01000000, 256 * 1024, "host::\u0000".toByteArray())
        var m = read(inp)
        var sentKey = false; var signed = false; var ok = true
        while (m.cmd == AUTH) {
            if (!signed) { send(out, AUTH, 2, 0, sign(ctx, m.data)); signed = true }
            else if (!sentKey) { send(out, AUTH, 3, 0, publicKey(ctx)); sentKey = true; s.soTimeout = 30_000 }
            else { ok = false; break }
            m = read(inp)
        }
        if (!ok || m.cmd != CNXN) { s.close(); null }
        else Live(host, s, out, inp).takeIf { it.start() }
    } catch (e: Exception) { Log.i(TAG, "adb live $host: ${e.message}"); null }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xFF }).putInt(cmd xor -1).put(data)
        synchronized(out) { out.write(b.array()); out.flush() }
    }

    private fun read(inp: DataInputStream): Msg {
        val h = ByteArray(24); inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len); inp.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    // ---------- her phone's own key (made once, kept on her phone) ----------
    private fun keys(ctx: Context): Pair<RSAPrivateKey, RSAPublicKey> = synchronized(this) {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "key"); val pub = File(dir, "key.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (!priv.exists() || !pub.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)) }.generateKeyPair()
            priv.writeBytes(kp.private.encoded); pub.writeBytes(kp.public.encoded)
        }
        Pair(kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())) as RSAPrivateKey,
            kf.generatePublic(X509EncodedKeySpec(pub.readBytes())) as RSAPublicKey)
    }

    /** ADB signs the TV's 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    private fun sign(ctx: Context, token: ByteArray): ByteArray {
        val (priv, _) = keys(ctx)
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val k = 256
        val em = ByteArray(k)
        em[0] = 0; em[1] = 1
        val tLen = digestInfo.size + token.size
        for (i in 2 until k - tLen - 1) em[i] = 0xFF.toByte()
        em[k - tLen - 1] = 0
        System.arraycopy(digestInfo, 0, em, k - tLen, digestInfo.size)
        System.arraycopy(token, 0, em, k - token.size, token.size)
        val sig = BigInteger(1, em).modPow(priv.privateExponent, priv.modulus).toByteArray()
        return when {
            sig.size == k -> sig
            sig.size > k -> sig.copyOfRange(sig.size - k, sig.size)
            else -> ByteArray(k - sig.size) + sig
        }
    }

    /** The public key in Android's own format, base64, with a name the TV shows. */
    private fun publicKey(ctx: Context): ByteArray {
        val (_, pub) = keys(ctx)
        val n = pub.modulus; val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv)
        fun putBig(x: BigInteger) { var v = x; repeat(words) { b.putInt(v.mod(r32).toInt()); v = v.shiftRight(32) } }
        putBig(n); putBig(rr)
        b.putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Avriella-TV@her-phone\u0000").toByteArray()
    }
}
