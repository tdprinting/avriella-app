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
    /** Connected to the "from anywhere" relay. */
    @Volatile var relay = false
    @Volatile var talking = false
    @Volatile var inCall = false
    /** null = not on a kid screen; true = touch is locked; false = she can touch. */
    @Volatile var touchLocked: Boolean? = null
    @Volatile var running = false
    @Volatile var port = 8080
    @Volatile var viewers = 0
    @Volatile var videoId: String? = null
    @Volatile var shelfId: String? = null
    @Volatile var posMs = 0L
    @Volatile var durMs = 0L
    @Volatile var playing = false
    @Volatile var sleepAt = 0L
    @Volatile var stopAfter = false
    @Volatile var soundId: String? = null

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
            var contentLength = 0
            var range: String? = null
            while (true) {
                val h = readLine(input) ?: break; if (h.isEmpty()) break
                if (h.startsWith("content-length:", ignoreCase = true)) contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
            }
            val parts = line.split(" ")
            if (parts.size < 2) return
            val full = parts[1]
            val path = full.substringBefore('?')
            val query = parseQuery(full.substringAfter('?', ""))
            val out = sock.getOutputStream()
            val ip = sock.inetAddress?.hostAddress ?: ""
            val given = query["key"]
            val authed = (!given.isNullOrEmpty() && Guard.check(ip, given, Library.settings.camCode)) ||
                (!Library.settings.camRequireCode && isLocal(sock.inetAddress))
            if (authed) when (query["cam"]) { "back" -> cam.select(true); "front" -> cam.select(false) }
            // A TV playing one of her videos: only with the one-off secret link, and only from the home network.
            if (path.startsWith("/media/")) {
                val token = path.removePrefix("/media/").substringBefore('/')
                val vid = Cast.videoFor(token)
                if (vid == null || !isLocal(sock.inetAddress)) send(out, 404, "text/plain", "Not found".toByteArray())
                else {
                    // smooth playback: big send buffer, full-size packets, no Wi-Fi power saving while a TV is watching
                    try { sock.sendBufferSize = 2 shl 20; sock.tcpNoDelay = false; sock.soTimeout = 60_000 } catch (_: Exception) {}
                    Streaming.begin(ctx)
                    try { media(java.io.BufferedOutputStream(out, 512 * 1024), vid, parts[0] == "HEAD", range) } finally { Streaming.end() }
                }
                return
            }
            when (path) {
                "/", "/index.html" -> asset(out, "web/index.html", "text/html; charset=utf-8")
                // ---- play her videos on a TV in the house ----
                "/api/cast/scan" -> if (authed) send(out, 200, "application/json", JSONObject().put("tvs", Cast.scan(ctx)).toString().toByteArray()) else denied(out)
                "/api/cast/play" -> if (authed) {
                    val res = Cast.play(ctx, query["tv"] ?: "", query["video"] ?: "")
                    send(out, 200, "application/json", (if (res == "ok") JSONObject().put("ok", true) else JSONObject().put("error", res)).toString().toByteArray())
                } else denied(out)
                "/api/cast/control" -> if (authed) {
                    val res = Cast.control(query["action"] ?: "", query["value"])
                    send(out, 200, "application/json", (if (res == "ok") JSONObject().put("ok", true) else JSONObject().put("error", res)).toString().toByteArray())
                } else denied(out)
                // her TV page, for TVs with a web browser (Fire TV's Silk browser and others)
                "/tv", "/tv/", "/tv.html" -> asset(out, "web/tv.html", "text/html; charset=utf-8")
                "/api/tv/poll" -> if (authed && isLocal(sock.inetAddress)) {
                    val st = JSONObject().put("state", query["state"] ?: "idle").put("video", query["video"] ?: "")
                        .put("pos", query["pos"]?.toDoubleOrNull() ?: 0.0).put("dur", query["dur"]?.toDoubleOrNull() ?: 0.0)
                        .put("vol", query["vol"]?.toDoubleOrNull() ?: 1.0).put("fill", query["fill"] == "1").put("muted", query["muted"] == "1")
                    val wait = (query["wait"]?.toLongOrNull() ?: 0L).coerceIn(0, 20_000)
                    val c = Cast.webPoll(query["id"] ?: "", query["name"] ?: "TV", ip, st, wait)
                    send(out, 200, "application/json", c.toString().toByteArray())
                } else denied(out)
                "/api/tv/link" -> if (authed && isLocal(sock.inetAddress)) {
                    val u = Cast.webLink(query["video"] ?: "")
                    send(out, 200, "application/json", (if (u != null) JSONObject().put("url", u) else JSONObject().put("error", "That video isn't on her phone anymore.")).toString().toByteArray())
                } else denied(out)
                "/api/cast/status" -> if (authed) send(out, 200, "application/json", Cast.status().toString().toByteArray()) else denied(out)
                // ---- backup remote for the Fire TVs ----
                "/api/cast/rtvs" -> if (authed) send(out, 200, "application/json", JSONObject().put("tvs", FireRemote.tvs(ctx, query["fresh"] == "1")).toString().toByteArray()) else denied(out)
                "/api/cast/rapps" -> if (authed) send(out, 200, "application/json", JSONObject().put("apps", FireRemote.apps(ctx, query["host"] ?: "")).toString().toByteArray()) else denied(out)
                "/api/cast/rkey", "/api/cast/rtext", "/api/cast/rlaunch", "/api/cast/rshelf" -> if (authed) {
                    val h = query["host"] ?: ""
                    val res = when (path) {
                        "/api/cast/rkey" -> FireRemote.key(ctx, h, query["key"] ?: "")
                        "/api/cast/rtext" -> FireRemote.text(ctx, h, query["text"] ?: "")
                        "/api/cast/rlaunch" -> FireRemote.launch(ctx, h, query["pkg"] ?: "")
                        else -> FireRemote.shelf(ctx, h)
                    }
                    send(out, 200, "application/json", (if (res == "ok") JSONObject().put("ok", true) else JSONObject().put("error", res)).toString().toByteArray())
                } else denied(out)
                "/manifest.json" -> asset(out, "web/manifest.json", "application/manifest+json")
                "/icon.png", "/apple-touch-icon.png", "/icon-192.png", "/icon-512.png" -> {
                    val name = if (path.startsWith("/icon-")) path.drop(1) else "icon.png"
                    val png = try { ctx.assets.open("web/$name").use { it.readBytes() } } catch (_: Exception) { icon() }
                    send(out, 200, "image/png", png)
                }
                // Lets iPhones on the same Wi-Fi recognise this phone when they search for it (no secrets here).
                "/api/hello" -> send(out, 200, "application/json",
                    JSONObject().put("app", "avriella-tv").put("name", Library.settings.childName).put("port", CamState.port).toString().toByteArray())
                // Pairing for "from anywhere" mode: only handed out on your own network.
                "/api/pair" -> if (authed && isLocal(sock.inetAddress) && Library.settings.relaySecret.isNotEmpty())
                    send(out, 200, "application/json", JSONObject().put("secret", Library.settings.relaySecret).toString().toByteArray())
                    else denied(out)
                "/api/info" -> if (authed) send(out, 200, "application/json", info().toByteArray()) else denied(out)
                "/api/snap" -> if (authed) snap(out) else denied(out)
                "/api/library" -> if (authed) {
                    val json = Remote.onMain { libraryJson() } ?: "{\"error\":\"busy\"}"
                    send(out, 200, "application/json", json.toByteArray())
                } else denied(out)
                "/api/thumb" -> if (authed) thumb(out, query["id"] ?: "") else denied(out)
                "/api/cover" -> if (authed) {
                    val f = com.avriella.tv.data.Covers.file(ctx, query["id"] ?: "")
                    if (f.exists()) send(out, 200, "image/jpeg", f.readBytes())
                    else {
                        Library.videos[query["id"] ?: ""]?.let { com.avriella.tv.data.Covers.fetch(ctx, it) }
                        send(out, 404, "text/plain", "no cover yet".toByteArray())
                    }
                } else denied(out)
                "/api/disc" -> if (authed) {
                    val f = Library.videos[query["id"] ?: ""]?.let { com.avriella.tv.data.Covers.disc(ctx, it) }
                    if (f != null && f.exists()) send(out, 200, "image/png", f.readBytes())
                    else send(out, 404, "text/plain", "no disc".toByteArray())
                } else denied(out)
                "/api/control" -> if (authed) {
                    val res = Remote.send(Remote.Cmd(query["action"] ?: "", query["video"], query["shelf"], query["value"]))
                    android.util.Log.i("AvriellaRemote", "${sock.inetAddress?.hostAddress} ${query["action"]} ${query["value"] ?: query["video"] ?: ""} -> $res")
                    val body = if (res == "ok") JSONObject().put("ok", true) else JSONObject().put("error", res)
                    send(out, 200, "application/json", body.toString().toByteArray())
                } else denied(out)
                "/api/stream" -> if (authed) stream(sock, out, isLocal(sock.inetAddress)) else denied(out)
                // ---- Tapo Wi-Fi cameras around the house ----
                "/api/tapo" -> if (authed) send(out, 200, "application/json", Tapo.json().toString().toByteArray()) else denied(out)
                "/api/tapo/scan" -> if (authed) send(out, 200, "application/json", Tapo.scan().toString().toByteArray()) else denied(out)
                "/api/tapo/save" -> if (authed) {
                    val body = if (parts[0] == "POST" && contentLength in 1..20_000) {
                        val b = ByteArray(contentLength); var got = 0
                        while (got < contentLength) { val r = input.read(b, got, contentLength - got); if (r < 0) break; got += r }
                        String(b, 0, got, Charsets.UTF_8)
                    } else ""
                    val res = try { Tapo.save(JSONObject(body)) } catch (_: Exception) { "error:Couldn't read that." }
                    send(out, 200, "application/json", (if (res.startsWith("id:")) JSONObject().put("ok", true).put("id", res.drop(3)) else JSONObject().put("error", res.removePrefix("error:"))).toString().toByteArray())
                } else denied(out)
                "/api/tapo/delete" -> if (authed) { Tapo.delete(query["id"] ?: ""); send(out, 200, "application/json", "{\"ok\":true}".toByteArray()) } else denied(out)
                "/api/tapo/test" -> if (authed) send(out, 200, "application/json", Tapo.test(query["id"]).toString().toByteArray()) else denied(out)
                "/api/tapo/snap" -> if (authed) tapoSnap(out, query["id"]) else denied(out)
                "/api/tapo/stream" -> if (authed) tapoStream(sock, out, query["id"], isLocal(sock.inetAddress)) else denied(out)
                "/api/torch" -> if (authed) {
                    val want = when (query["on"]) { "1", "true" -> true; "0", "false" -> false; else -> !Torch.on }
                    val err = Remote.onMain { Torch.set(ctx, want) ?: "" } ?: "busy"
                    send(out, 200, "application/json", (if (err.isEmpty()) JSONObject().put("ok", true).put("torch", Torch.on) else JSONObject().put("error", err).put("torch", Torch.on)).toString().toByteArray())
                } else denied(out)
                // ---- walkie-talkie: a parent's voice message, played out loud ----
                "/api/talk" -> if (authed) {
                    val ok = parts[0] == "POST" && contentLength in 100..(8 * 1024 * 1024) && run {
                        val body = ByteArray(contentLength); var got = 0
                        while (got < contentLength) { val r = input.read(body, got, contentLength - got); if (r < 0) break; got += r }
                        got == contentLength && Talk.play(body)
                    }
                    send(out, 200, "application/json", (if (ok) "{\"ok\":true}" else "{\"error\":\"Couldn't play that.\"}").toByteArray())
                } else denied(out)
                // ---- video call (WebRTC; the phones then talk to each other directly) ----
                "/api/call/start" -> if (authed) {
                    val body = if (parts[0] == "POST" && contentLength in 1..200_000) {
                        val b = ByteArray(contentLength); var got = 0
                        while (got < contentLength) { val r = input.read(b, got, contentLength - got); if (r < 0) break; got += r }
                        String(b, 0, got, Charsets.UTF_8)
                    } else ""
                    val res = if (body.isEmpty()) "Missing call details." else Call.start(body)
                    send(out, 200, "application/json", (if (res.startsWith("id:")) JSONObject().put("ok", true).put("id", res.drop(3)) else JSONObject().put("error", res)).toString().toByteArray())
                } else denied(out)
                "/api/call/state" -> if (authed) {
                    val j = JSONObject().put("state", if (query["id"] == Call.id) Call.state else "ended").put("answer", if (query["id"] == Call.id) Call.answer else "")
                    send(out, 200, "application/json", j.toString().toByteArray())
                } else denied(out)
                "/api/call/end" -> if (authed) {
                    if (query["id"] == null || query["id"] == Call.id) Call.end()
                    send(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                } else denied(out)
                // ---- baby book: log, her info, monthly growth photos ----
                "/api/baby" -> if (authed) send(out, 200, "application/json", com.avriella.tv.data.Baby.json().toByteArray()) else denied(out)
                "/api/baby/log" -> if (authed) {
                    val kind = query["kind"] ?: "note"
                    val time = query["time"]?.toLongOrNull() ?: System.currentTimeMillis()
                    val e = com.avriella.tv.data.Baby.add(kind, time, query["note"] ?: "", query["by"] ?: "iPhone")
                    send(out, 200, "application/json", JSONObject().put("ok", true).put("id", e.id).toString().toByteArray())
                } else denied(out)
                "/api/baby/edit" -> if (authed) {
                    com.avriella.tv.data.Baby.update(query["id"] ?: "", query["note"], query["time"]?.toLongOrNull())
                    send(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                } else denied(out)
                "/api/baby/delete" -> if (authed) {
                    com.avriella.tv.data.Baby.delete(query["id"] ?: "")
                    send(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                } else denied(out)
                "/api/baby/info" -> if (authed) {
                    val b = com.avriella.tv.data.Baby
                    synchronized(b) {
                        b.ensure()
                        query["field"]?.let { f -> if (f.isNotBlank()) b.info.fields[f.take(40)] = (query["value"] ?: "").take(500) }
                        query["birth"]?.toLongOrNull()?.let { b.info.birth = it }
                        b.save()
                    }
                    send(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                } else denied(out)
                "/api/baby/photo" -> if (authed) {
                    val m = query["month"]?.toIntOrNull()?.coerceIn(0, 240)
                    val f = m?.let { com.avriella.tv.data.Baby.photoFile(it) }
                    when {
                        m == null -> send(out, 404, "text/plain", "Not found".toByteArray())
                        parts[0] == "POST" && contentLength in 1..(25 * 1024 * 1024) -> {
                            val body = ByteArray(contentLength); var got = 0
                            while (got < contentLength) { val r = input.read(body, got, contentLength - got); if (r < 0) break; got += r }
                            val ok = got == contentLength && com.avriella.tv.data.Baby.savePhoto(m, body)
                            send(out, 200, "application/json", (if (ok) "{\"ok\":true}" else "{\"error\":\"Couldn't save that photo.\"}").toByteArray())
                        }
                        query["delete"] == "1" -> { com.avriella.tv.data.Baby.deletePhoto(m); send(out, 200, "application/json", "{\"ok\":true}".toByteArray()) }
                        f != null && f.exists() -> send(out, 200, "image/jpeg", f.readBytes())
                        else -> send(out, 404, "text/plain", "Not found".toByteArray())
                    }
                } else denied(out)
                else -> send(out, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (e: Exception) {
            android.util.Log.w("AvriellaCam", "request failed", e)
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** Same Wi-Fi, iPhone hotspot, this phone, or Tailscale (already signed in to your account). */
    private fun isLocal(a: java.net.InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        val b = a.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
    }

    private fun denied(out: OutputStream) {
        android.util.Log.i("AvriellaRemote", "refused a request: wrong or missing code")
        // Slows down code guessing: one refusal at a time for the whole phone, so firing many
        // guesses at once doesn't help. Extra guesses while one is being refused are just dropped.
        if (!Guard.slow.tryLock()) return
        try { Thread.sleep(1200) } finally { Guard.slow.unlock() }
        send(out, 401, "application/json", "{\"error\":\"wrong code\"}".toByteArray())
    }

    /**
     * Protects the 6-digit code from guessing: after 8 wrong codes from one address in 10 minutes,
     * that address is shut out for 15 minutes (even if it then gets the code right).
     */
    object Guard {
        val slow = java.util.concurrent.locks.ReentrantLock()
        private class Tries(var n: Int, var since: Long, var lockedUntil: Long = 0)
        private val tries = HashMap<String, Tries>()

        fun check(ip: String, given: String, code: String): Boolean {
            val now = System.currentTimeMillis()
            synchronized(tries) {
                val t = tries[ip]
                if (t != null && t.lockedUntil > now) return false
            }
            // compare in constant time, so response timing gives nothing away
            val ok = code.isNotEmpty() && java.security.MessageDigest.isEqual(given.toByteArray(), code.toByteArray())
            // requests from the internet relay arrive from her phone itself and were already proven
            // genuine by their encryption key, so they never lock anyone out
            if (ip == "127.0.0.1" || ip == "::1") return ok
            synchronized(tries) {
                if (ok) { tries.remove(ip); return true }
                if (tries.size > 500) tries.entries.removeAll { it.value.lockedUntil < now && now - it.value.since > 600_000L }
                val t = tries.getOrPut(ip) { Tries(0, now) }
                if (now - t.since > 600_000L) { t.n = 0; t.since = now }
                if (++t.n >= 8) {
                    t.lockedUntil = now + 15 * 60_000L; t.n = 0; t.since = now
                    android.util.Log.w("AvriellaRemote", "too many wrong codes from $ip: shut out for 15 minutes")
                }
            }
            return false
        }
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
            put("videoId", CamState.videoId ?: "")
            put("shelfId", CamState.shelfId ?: "")
            put("pos", CamState.posMs)
            put("dur", CamState.durMs)
            put("playing", CamState.playing)
            put("volume", Remote.volume())
            put("volumeMax", Remote.volumeMax())
            put("brightness", Library.settings.brightness)
            put("level", Library.settings.levelVideo)
            put("sleepLeftSec", if (CamState.sleepAt > 0) ((CamState.sleepAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0) else -1)
            put("stopAfter", CamState.stopAfter)
            put("soundId", CamState.soundId ?: "")
            put("viewers", CamState.viewers)
            put("error", cam.error ?: "")
            put("awake", Wake.screenOn)
            put("torch", Torch.on)
        put("fullLock", com.avriella.tv.kid.KidLock.isFullLock(Library.appCtx))
            put("touch", when (CamState.touchLocked) { true -> "locked"; false -> "unlocked"; null -> "" })
            put("canWake", Wake.canLaunch())
            put("canScreenOff", Wake.canScreenOff())
            put("addrs", org.json.JSONArray(Wake.addresses().filter { !it.startsWith("127.") }))
            put("tailscale", Wake.tailscale() ?: "")
            put("relay", CamState.relay)
            // false = Android's battery saver may cut her phone off the internet while it sleeps
            put("batteryOk", (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName))
            put("talking", CamState.talking)
            put("call", Call.state)
        }.toString()
    }

    /** Everything saved on her phone: shows, playlists and videos (built on the main thread). */
    private fun libraryJson(): String {
        val o = JSONObject()
        o.put("shelves", org.json.JSONArray().apply {
            Library.shelves.forEach { s ->
                put(JSONObject().put("id", s.id).put("name", s.name).put("emoji", s.emoji)
                    .put("kind", s.kind).put("ids", org.json.JSONArray(s.videoIds)))
            }
        })
        o.put("sounds", org.json.JSONArray().apply {
            com.avriella.tv.sleep.SLEEP_SOUNDS.forEach { s -> put(JSONObject().put("id", s.id).put("name", s.name).put("emoji", s.emoji)) }
        })
        o.put("videos", org.json.JSONArray().apply {
            Library.videosIn(Library.ALL).forEach { v ->
                put(JSONObject().put("id", v.id).put("title", v.title).put("dur", v.durationMs).put("pos", v.positionMs))
            }
        })
        return o.toString()
    }

    private fun thumb(out: OutputStream, id: String) {
        val safe = id.filter { it.isLetterOrDigit() }
        val f = java.io.File(ctx.filesDir, "thumbs/$safe.jpg")
        if (safe.isEmpty() || !f.exists()) send(out, 404, "text/plain", "no thumb".toByteArray())
        else send(out, 200, "image/jpeg", f.readBytes())
    }

    private fun snap(out: OutputStream) {
        cam.acquire()
        try {
            val jpg = cam.waitFrame(cam.seq, 4000)
            if (jpg == null) send(out, 503, "application/json", "{\"error\":\"${cam.error ?: "camera starting"}\"}".toByteArray())
            else send(out, 200, "image/jpeg", small(jpg))
        } finally { cam.release() }
    }

    /** Snapshots go over the internet relay: if someone at home has the camera in sharp mode, shrink the frame first. */
    private fun small(jpg: ByteArray): ByteArray {
        if (jpg.size < 70_000) return jpg
        return try {
            val bm = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
            val bos = java.io.ByteArrayOutputStream()
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 62, bos); bm.recycle()
            bos.toByteArray()
        } catch (_: Exception) { jpg }
    }

    private fun tapoSnap(out: OutputStream, id: String?) {
        val f = Tapo.feed(id, false) ?: return send(out, 404, "application/json", "{\"error\":\"No such camera\"}".toByteArray())
        f.acquire()
        try {
            val jpg = f.waitFrame(if (f.latest != null) f.seq - 1 else 0, 8000)
            if (jpg == null) send(out, 503, "application/json", JSONObject().put("error", f.error ?: "Camera starting…").toString().toByteArray())
            else send(out, 200, "image/jpeg", jpg)
        } finally { f.release() }
    }

    private fun tapoStream(sock: Socket, out: OutputStream, id: String?, hq: Boolean) {
        val f = Tapo.feed(id, hq) ?: return send(out, 404, "application/json", "{\"error\":\"No such camera\"}".toByteArray())
        f.acquire()
        try {
            sock.soTimeout = 0
            val b = "babycamframe"
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$b\r\n" +
                "Cache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
            var last = 0L
            while (running && !sock.isClosed) {
                val jpg = f.waitFrame(last, 5000) ?: continue
                last = f.seq
                out.write("--$b\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\n\r\n".toByteArray())
                out.write(jpg); out.write("\r\n".toByteArray()); out.flush()
            }
        } catch (_: Exception) {
        } finally { f.release() }
    }

    private fun stream(sock: Socket, out: OutputStream, hq: Boolean) {
        cam.acquire(hq)
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
        } finally { cam.release(hq) }
    }

    private fun asset(out: OutputStream, name: String, type: String) {
        val bytes = try { ctx.assets.open(name).use { it.readBytes() } } catch (_: Exception) { null }
        if (bytes == null) send(out, 404, "text/plain", "Missing $name".toByteArray()) else send(out, 200, type, bytes)
    }

    /** Streams a video file to a TV, with byte ranges so it can jump around and buffer. */
    private fun media(out: OutputStream, videoId: String, head: Boolean, range: String?) {
        val v = Library.videos[videoId] ?: return send(out, 404, "text/plain", "Not found".toByteArray())
        val pfd = try { ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(v.uri), "r") } catch (_: Exception) { null }
            ?: return send(out, 404, "text/plain", "Not found".toByteArray())
        pfd.use { fd ->
            val size = fd.statSize
            var start = 0L; var end = size - 1
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            val partial = m != null && size > 0
            if (partial) {
                val a = m!!.groupValues[1]; val b = m.groupValues[2]
                if (a.isEmpty() && b.isNotEmpty()) { start = (size - b.toLong()).coerceAtLeast(0) }
                else { start = a.toLongOrNull() ?: 0; if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
                if (start >= size || start > end) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
                }
            }
            val mime = Cast.mime(videoId)
            val h = StringBuilder()
            h.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            h.append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\n")
            if (size > 0) h.append("Content-Length: ${end - start + 1}\r\n")
            if (partial) h.append("Content-Range: bytes $start-$end/$size\r\n")
            // what DLNA TVs (Samsung, LG…) expect to see before they'll play a stream
            h.append("transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            h.append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
            out.write(h.toString().toByteArray())
            if (head) { out.flush(); return }
            java.io.FileInputStream(fd.fileDescriptor).use { fin ->
                val ch = fin.channel
                if (start > 0) ch.position(start)
                val buf = ByteArray(256 * 1024)
                var left = if (size > 0) end - start + 1 else Long.MAX_VALUE
                while (left > 0) {
                    val r = fin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    out.write(buf, 0, r); left -= r
                }
            }
            out.flush()
        }
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

/**
 * While a TV is streaming one of her videos: keep her phone's Wi-Fi at full speed (no power-saving
 * naps that make the picture stutter) and the CPU awake even with her screen off.
 */
object Streaming {
    private var count = 0
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null
    private var cpu: android.os.PowerManager.WakeLock? = null
    private val stopLater = android.os.Handler(android.os.Looper.getMainLooper())
    private val release = Runnable { synchronized(this) { if (count == 0) { try { wifi?.release() } catch (_: Exception) {}; try { cpu?.release() } catch (_: Exception) {} } } }

    fun begin(ctx: android.content.Context) = synchronized(this) {
        count++
        stopLater.removeCallbacks(release)
        try {
            if (wifi == null) {
                val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(mode, "avriella:tv-stream").apply { setReferenceCounted(false) }
                cpu = (ctx.applicationContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "avriella:tv-stream").apply { setReferenceCounted(false) }
            }
            if (wifi?.isHeld != true) wifi?.acquire()
            if (cpu?.isHeld != true) cpu?.acquire(4 * 3600_000L)
        } catch (_: Exception) {}
    }

    // the TV asks for the next piece every so often: keep the locks a little while between pieces
    fun end() = synchronized(this) { count = maxOf(0, count - 1); if (count == 0) stopLater.postDelayed(release, 90_000) }
}
