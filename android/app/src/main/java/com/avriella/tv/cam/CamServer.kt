package com.avriella.tv.cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.view.Display
import android.view.Surface
import com.avriella.tv.data.Library
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

object CamState {
    @Volatile var nowPlaying = ""
    @Volatile var mode = "menu"
    @Volatile var running = false
    @Volatile var port = 8080
    @Volatile var viewers = 0

    /** IPv4 addresses the iPhones can use. Wi-Fi first; Tailscale (100.x) works from away. */
    fun addresses(): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (a.isLoopbackAddress || h.contains(':')) continue
                    val rank = when {
                        ni.name.startsWith("wlan") -> 0
                        h.startsWith("100.") -> 1
                        ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") -> 9
                        else -> 5
                    }
                    if (rank < 9) out += rank to h
                }
            }
        } catch (_: Exception) {}
        return out.sortedBy { it.first }.map { it.second }.distinct()
    }
}

/**
 * Tiny web server for the baby cam (home Wi-Fi only).
 *  /                 the iPhone viewer web app (from /docs in the repo)
 *  /api/info?key=    what she's doing, battery, camera rotation
 *  /api/stream?key=  live MJPEG video
 *  /api/snap?key=    one JPEG frame
 */
class CamServer(private val ctx: Context, private val cam: FrontCamera) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start(): Int {
        for (port in listOf(8080, 8081, 8088, 8090)) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                server = s
                running = true
                CamState.port = port
                Thread({ acceptLoop(s) }, "babycam-http").start()
                return port
            } catch (_: Exception) {}
        }
        return -1
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            pool.execute { handle(c) }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val line = readLine(input) ?: return
            while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break }
            val parts = line.split(" ")
            if (parts.size < 2) return
            val full = parts[1]
            val path = full.substringBefore('?')
            val query = parseQuery(full.substringAfter('?', ""))
            val out = sock.getOutputStream()
            val authed = query["key"] == Library.settings.camCode
            if (authed) when (query["cam"]) { "back" -> cam.select(true); "front" -> cam.select(false) }
            when (path) {
                "/", "/index.html" -> asset(out, "web/index.html", "text/html; charset=utf-8")
                "/manifest.json" -> asset(out, "web/manifest.json", "application/manifest+json")
                "/icon.png", "/apple-touch-icon.png", "/icon-192.png", "/icon-512.png" -> {
                    val name = if (path.startsWith("/icon-")) path.drop(1) else "icon.png"
                    val png = try { ctx.assets.open("web/$name").use { it.readBytes() } } catch (_: Exception) { icon() }
                    send(out, 200, "image/png", png)
                }
                // Lets iPhones on the same Wi-Fi recognise this phone when they search for it (no secrets here).
                "/api/hello" -> send(out, 200, "application/json",
                    JSONObject().put("app", "avriella-tv").put("name", Library.settings.childName).put("port", CamState.port).toString().toByteArray())
                "/api/info" -> if (authed) send(out, 200, "application/json", info().toByteArray()) else denied(out)
                "/api/snap" -> if (authed) snap(out) else denied(out)
                "/api/stream" -> if (authed) stream(sock, out) else denied(out)
                else -> send(out, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun denied(out: OutputStream) {
        Thread.sleep(1200) // slows down code guessing
        send(out, 401, "application/json", "{\"error\":\"wrong code\"}".toByteArray())
    }

    private fun info(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rot = when (dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        return JSONObject().apply {
            put("name", Library.settings.childName)
            put("mode", CamState.mode)
            put("nowPlaying", CamState.nowPlaying)
            put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charging", bm.isCharging)
            put("rotation", if (cam.useBack) (cam.sensorOrientation - rot + 360) % 360 else (cam.sensorOrientation + rot) % 360)
            put("camera", if (cam.useBack) "back" else "front")
            put("viewers", CamState.viewers)
            put("error", cam.error ?: "")
        }.toString()
    }

    private fun snap(out: OutputStream) {
        cam.acquire()
        try {
            val jpg = cam.waitFrame(cam.seq, 4000)
            if (jpg == null) send(out, 503, "application/json", "{\"error\":\"${cam.error ?: "camera starting"}\"}".toByteArray())
            else send(out, 200, "image/jpeg", jpg)
        } finally { cam.release() }
    }

    private fun stream(sock: Socket, out: OutputStream) {
        cam.acquire()
        try {
            sock.soTimeout = 0
            val b = "babycamframe"
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$b\r\n" +
                    "Cache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
            )
            var last = 0L
            while (running && !sock.isClosed) {
                val jpg = cam.waitFrame(last, 5000) ?: continue
                last = cam.seq
                out.write("--$b\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\n\r\n".toByteArray())
                out.write(jpg)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally { cam.release() }
    }

    private fun asset(out: OutputStream, name: String, type: String) {
        val bytes = try { ctx.assets.open(name).use { it.readBytes() } } catch (_: Exception) { null }
        if (bytes == null) send(out, 404, "text/plain", "Missing $name".toByteArray()) else send(out, 200, type, bytes)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('='); val v = it.substringAfter('=')
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private var iconCache: ByteArray? = null
    private fun icon(): ByteArray {
        iconCache?.let { return it }
        val s = 180f
        val bmp = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFF7EC8F7.toInt())
        p.color = 0xFFFF8B3D.toInt(); c.drawRoundRect(RectF(s * 0.18f, s * 0.3f, s * 0.82f, s * 0.78f), 20f, 20f, p)
        p.color = 0xFFFFF6EA.toInt(); c.drawRoundRect(RectF(s * 0.24f, s * 0.36f, s * 0.76f, s * 0.72f), 12f, 12f, p)
        p.color = 0xFFF2557A.toInt()
        val hp = Path().apply {
            moveTo(s * 0.5f, s * 0.66f)
            cubicTo(s * 0.33f, s * 0.56f, s * 0.36f, s * 0.42f, s * 0.5f, s * 0.48f)
            cubicTo(s * 0.64f, s * 0.42f, s * 0.67f, s * 0.56f, s * 0.5f, s * 0.66f)
        }
        c.drawPath(hp, p)
        p.color = Color.parseColor("#2B2845"); p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(s * 0.42f, s * 0.3f, s * 0.34f, s * 0.16f, p); c.drawLine(s * 0.58f, s * 0.3f, s * 0.66f, s * 0.16f, p)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        iconCache = bos.toByteArray()
        return iconCache!!
    }
}
