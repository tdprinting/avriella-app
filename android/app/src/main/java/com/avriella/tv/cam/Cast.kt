package com.avriella.tv.cam

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.avriella.tv.data.Library
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Plays her saved videos on a TV in the house, with no extra apps or accounts:
 *  - Chromecast / Google TV / Android TV (Google's open "Cast v2" protocol, default media player), and
 *  - smart TVs that accept DLNA / UPnP (most Samsung, LG, Sony, Panasonic, Philips…).
 *
 * The TV fetches the video straight from her phone's little web server over the home Wi-Fi,
 * through a one-off secret link that only works for that video. Nothing goes to the internet.
 */
object Cast {
    private const val TAG = "AvriellaCast"
    private val rnd = SecureRandom()

    class Tv(val id: String, val name: String, val kind: String, val host: String, val port: Int, val control: String = "")

    @Volatile private var tvs: List<Tv> = emptyList()
    @Volatile private var session: Session? = null

    // ---------------- the secret video links the TV plays ----------------

    private class Link(val videoId: String, val until: Long)
    private val links = ConcurrentHashMap<String, Link>()

    /** The video a link stands for, or null if it's unknown or expired. */
    fun videoFor(token: String): String? {
        val l = links[token] ?: return null
        if (l.until < System.currentTimeMillis()) { links.remove(token); return null }
        return l.videoId
    }

    private fun linkFor(videoId: String, tvHost: String): String? {
        val now = System.currentTimeMillis()
        links.entries.removeAll { it.value.until < now }
        val token = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        links[token] = Link(videoId, now + 12 * 3600_000L)
        // her address on the same network as the TV (not the Tailscale one)
        val mine = Wake.addresses().filter { !it.startsWith("100.") }
        val prefix = tvHost.substringBeforeLast('.')
        val ip = mine.firstOrNull { it.startsWith("$prefix.") } ?: mine.firstOrNull() ?: return null
        return "http://$ip:${CamState.port}/media/$token/video.${ext(videoId)}"
    }

    private fun ext(videoId: String): String = when (mime(videoId)) {
        "video/webm" -> "webm"; "video/x-matroska" -> "mkv"; "video/quicktime" -> "mov"; else -> "mp4"
    }

    fun mime(videoId: String): String {
        val v = Library.videos[videoId] ?: return "video/mp4"
        return try { Library.appCtx.contentResolver.getType(Uri.parse(v.uri)) } catch (_: Exception) { null }
            ?.takeIf { it.startsWith("video/") } ?: "video/mp4"
    }

    // ---------------- finding TVs ----------------

    /** Looks for TVs on the Wi-Fi for a few seconds. */
    fun scan(ctx: Context, ms: Long = 4000): JSONArray {
        val found = ConcurrentHashMap<String, Tv>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("avriella:cast").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            val t1 = Thread { try { scanChromecast(ctx, ms, found) } catch (e: Exception) { Log.w(TAG, "chromecast scan", e) } }
            val t2 = Thread { try { scanDlna(ms, found) } catch (e: Exception) { Log.w(TAG, "dlna scan", e) } }
            val t3 = Thread { try { scanFire(ms, found) } catch (e: Exception) { Log.w(TAG, "fire tv scan", e) } }
            t1.start(); t2.start(); t3.start(); t1.join(ms + 3000); t2.join(ms + 3000); t3.join(ms + 3000)
        } finally { try { lock.release() } catch (_: Exception) {} }
        // TVs showing her TV page in their web browser (e.g. Fire TV's Silk browser)
        val now = System.currentTimeMillis()
        webTvs.values.filter { now - it.lastSeen < 30_000 }.forEach { found["web:${it.id}"] = Tv("web:${it.id}", it.name, "web", it.host, 0) }
        // One entry per TV. Best way in first: Chromecast, its open TV page, Fire TV, then DLNA.
        // A Fire TV keeps its own name ("Living room tv") even when we reach it through its open TV page.
        val rank = mapOf("chromecast" to 0, "web" to 1, "firetv" to 2, "dlna" to 3)
        val list = found.values.groupBy { it.host }.map { (_, g) ->
            val best = g.minByOrNull { rank[it.kind] ?: 9 }!!
            val fire = g.firstOrNull { it.kind == "firetv" }
            if (best.kind == "web" && fire != null) Tv(best.id, fire.name, "web", best.host, 0, fire.control) else best
        }.sortedBy { it.name.lowercase() }
        tvs = list
        return JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind)) }
        }
    }

    private fun scanChromecast(ctx: Context, ms: Long, found: MutableMap<String, Tv>) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val pendingResolves = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) { pendingResolves.add(s) }
        }
        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        val until = System.currentTimeMillis() + ms
        try {
            while (System.currentTimeMillis() < until) {
                val s = pendingResolves.poll(200, TimeUnit.MILLISECONDS) ?: continue
                // resolve one at a time (older Android allows only one resolve at once)
                val done = CountDownLatch(1)
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, e: Int) { done.countDown() }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        try {
                            @Suppress("DEPRECATION")
                            val host = si.host?.hostAddress
                            if (host != null && !host.contains(':')) {
                                val fn = si.attributes["fn"]?.let { String(it, Charsets.UTF_8) } ?: si.serviceName
                                val md = si.attributes["md"]?.let { String(it, Charsets.UTF_8) } ?: ""
                                // speaker-only Cast devices (Nest Mini, audio groups) can't show video
                                val ca = si.attributes["ca"]?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: 5
                                val audioOnly = (ca and 1) == 0 || md.contains("Nest Mini") || md.contains("Google Home") || md.contains("Group")
                                if (!audioOnly) found["cc:$host"] = Tv("cc:$host:${si.port}", fn, "chromecast", host, si.port)
                            }
                        } finally { done.countDown() }
                    }
                })
                done.await(2500, TimeUnit.MILLISECONDS)
            }
        } finally { try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {} }
    }

    private fun scanDlna(ms: Long, found: MutableMap<String, Tv>) {
        val sock = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(0)); soTimeout = 500 }
        val locations = LinkedHashSet<String>()
        sock.use {
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n").toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
            val until = System.currentTimeMillis() + ms - 1200
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < until) {
                try {
                    val p = DatagramPacket(buf, buf.size); sock.receive(p)
                    val text = String(p.data, 0, p.length)
                    Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it }
                } catch (_: java.net.SocketTimeoutException) {}
            }
        }
        for (loc in locations) try {
            val xml = httpGet(loc, 2500)
            val name = tag(xml, "friendlyName") ?: "TV"
            // find the AVTransport service's control address
            val svc = Regex("(?s)<service>(.*?)</service>").findAll(xml).map { it.groupValues[1] }
                .firstOrNull { (tag(it, "serviceType") ?: "").contains("AVTransport") } ?: continue
            val ctl = tag(svc, "controlURL") ?: continue
            val base = tag(xml, "URLBase")?.takeIf { it.startsWith("http") } ?: loc
            val control = URL(URL(base), ctl).toString()
            val u = URL(loc)
            found["dl:${u.host}"] = Tv("dl:${u.host}:${u.port}", unxml(name), "dlna", u.host, if (u.port > 0) u.port else 80, control)
        } catch (e: Exception) { Log.i(TAG, "skipping $loc: ${e.message}") }
    }

    /**
     * Fire TVs (and Fire TV Edition TVs) answer the DIAL search with their name. They can't take a video
     * on their own, so we open her TV page in their Silk browser and play it there.
     */
    private fun scanFire(ms: Long, found: MutableMap<String, Tv>) {
        val sock = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(0)); soTimeout = 500 }
        val replies = LinkedHashMap<String, String>()
        sock.use {
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n").toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
            val until = System.currentTimeMillis() + ms - 1200
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < until) {
                try {
                    val p = DatagramPacket(buf, buf.size); sock.receive(p)
                    val text = String(p.data, 0, p.length)
                    // Fire TVs say "quick_ssdp" and keep their description at :60000/dd.xml
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: continue
                    val host = p.address.hostAddress ?: continue
                    if (text.contains("quick_ssdp", true) || loc.endsWith("/dd.xml")) replies[host] = loc
                } catch (_: java.net.SocketTimeoutException) {}
            }
        }
        for ((host, loc) in replies) try {
            val c = URL(loc).openConnection() as HttpURLConnection
            c.connectTimeout = 2500; c.readTimeout = 2500
            val xml = c.inputStream.bufferedReader().use { it.readText() }
            val apps = c.getHeaderField("Application-URL") ?: "http://$host:8009/apps/"
            val maker = tag(xml, "manufacturer") ?: ""; val model = tag(xml, "modelName") ?: ""
            if (maker.contains("Sony", true) || model.startsWith("PS", true)) continue  // a PlayStation can't play videos sent to it
            found["fire:$host"] = Tv("fire:$host", unxml(tag(xml, "friendlyName") ?: "Fire TV"), "firetv", host, 0, apps)
        } catch (e: Exception) { Log.i(TAG, "skipping fire tv $host: ${e.message}") }
    }

    /** Her TV page's address for a TV on this network. */
    /** Fire TVs on the Wi-Fi (name and address), for the backup remote. */
    fun fireTvs(ctx: Context): List<Tv> {
        scan(ctx, 3000)
        return tvs.filter { it.kind == "firetv" || it.kind == "web" }
    }

    /** Her DVD shelf page for a TV at this address. */
    fun pageForHost(host: String, name: String): String? = pageFor(Tv("", name, "firetv", host, 0))

    private fun pageFor(tv: Tv): String? {
        val mine = Wake.addresses().filter { !it.startsWith("100.") }
        val prefix = tv.host.substringBeforeLast('.')
        val ip = mine.firstOrNull { it.startsWith("$prefix.") } ?: mine.firstOrNull() ?: return null
        return "http://$ip:${CamState.port}/tv?key=${Library.settings.camCode}&name=" + java.net.URLEncoder.encode(tv.name, "UTF-8")
    }

    /** The TV page open on this TV right now (it checks in every few seconds). */
    private fun openPage(host: String): WebTv? {
        val now = System.currentTimeMillis()
        return webTvs.values.filter { it.host == host && now - it.lastSeen < 30_000 }.maxByOrNull { it.lastSeen }
    }

    private fun waitForPage(host: String, ms: Long): WebTv? {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { openPage(host)?.let { return it }; Thread.sleep(400) }
        return null
    }

    /** Opens her TV page on a Fire TV: over ADB if it's turned on, else asking Silk through DIAL. */
    private fun openOnFire(ctx: Context, tv: Tv): WebTv? {
        val page = pageFor(tv) ?: return openPage(tv.host)
        // Even if her TV page is open, it may be hidden behind another app: always bring Silk to the front.
        if (Adb.portOpen(tv.host)) {
            val r = Adb.shell(ctx, tv.host, "am start -a android.intent.action.VIEW -d '$page' com.amazon.cloud9")
            Log.i(TAG, "adb open on ${tv.name}: ${r?.trim()}")
            if (r != null) { Thread.sleep(2500); waitForPage(tv.host, 20_000)?.let { return it } }
        } else Log.i(TAG, "${tv.name}: ADB debugging is off")
        for (body in listOf(page, "url=$page")) try {
            val c = URL(tv.control.trimEnd('/') + "/com.amazon.cloud9").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = 5000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode; c.disconnect()
            Log.i(TAG, "dial silk on ${tv.name}: $code")
            if (code !in 200..299) break
            Thread.sleep(2500)
            waitForPage(tv.host, 12_000)?.let { return it }
        } catch (e: Exception) { Log.i(TAG, "dial silk on ${tv.name}: ${e.message}"); break }
        return openPage(tv.host)
    }

    // ---------------- playing ----------------

    /** Starts [videoId] on the TV. Returns "ok" or a friendly error. */
    fun play(ctx: Context, tvId: String, videoId: String): String {
        val v = Library.videos[videoId] ?: return "That video isn't on her phone anymore."
        val tv = tvs.firstOrNull { it.id == tvId } ?: run { scan(ctx); tvs.firstOrNull { it.id == tvId } }
            ?: return "Couldn't find that TV. Is it on and on the same Wi-Fi?"
        val url = linkFor(videoId, tv.host) ?: return "Her phone isn't on Wi-Fi."
        session?.close()
        session = null
        return try {
            val s = when (tv.kind) {
                "chromecast" -> CastV2(tv)
                "firetv" -> WebSession(tv, openOnFire(ctx, tv) ?: return "Couldn't open it on ${tv.name}. On that TV, open the Silk browser and go to " +
                    (pageFor(tv)?.substringBefore("?") ?: "her phone's address with /tv on the end") + " (or turn on ADB debugging in its Developer options).", videoId)
                "web" -> WebSession(tv, webTvs[tv.id.removePrefix("web:")] ?: openPage(tv.host) ?: return "That TV page was closed. Open it again on the TV.", videoId)
                else -> Dlna(tv)
            }
            s.videoId = videoId
            s.load(url, mime(videoId), v.title)
            session = s
            Log.i(TAG, "casting \"${v.title}\" to ${tv.name}")
            "ok"
        } catch (e: Exception) {
            Log.w(TAG, "cast failed", e)
            "The TV didn't start the video (${e.message ?: "no answer"})."
        }
    }

    /** pause / resume / stop / seek (seconds) on whatever is casting. */
    fun control(action: String, value: String?): String {
        val s = session ?: return "Nothing is playing on a TV."
        return try {
            when (action) {
                "pause" -> s.pause()
                "resume" -> s.resume()
                "seek" -> s.seek(value?.toDoubleOrNull() ?: return "Where to?")
                "stop" -> { s.stop(); s.close(); session = null }
                "volume" -> s.volume((value?.toDoubleOrNull() ?: return "How loud?").coerceIn(0.0, 1.0))
                "fill" -> s.fill()
                // the TV's own volume (Fire TVs with ADB debugging on)
                "volup", "voldown", "mute" -> {
                    if (!Adb.portOpen(s.tv.host)) return "Turn on ADB debugging on ${s.tv.name} to change its own volume from here."
                    val key = when (action) { "volup" -> 24; "voldown" -> 25; else -> 164 }
                    Adb.shell(Library.appCtx, s.tv.host, "input keyevent $key") ?: return "${s.tv.name} didn't allow it. Press OK on its \"Allow debugging?\" message."
                }
                else -> return "Unknown action"
            }
            "ok"
        } catch (e: Exception) {
            Log.w(TAG, "cast control failed", e)
            "The TV didn't answer."
        }
    }

    /** The TV something is on right now (no network), or null. */
    fun current(): String? = session?.tv?.name

    fun status(): JSONObject {
        val s = session ?: return JSONObject().put("active", false)
        val st = try { s.status() } catch (_: Exception) { null }
        if (st == null || st.optString("state") == "ended") {
            if (st != null) { s.close(); session = null }
            return JSONObject().put("active", st == null).put("tv", s.tv.name).put("tvId", s.tv.id).put("title", s.title).put("state", st?.optString("state") ?: "unknown")
        }
        return st.put("active", true).put("tv", s.tv.name).put("tvId", s.tv.id).put("kind", s.tv.kind).put("title", s.title).put("video", s.videoId)
    }

    private abstract class Session(val tv: Tv) {
        var title = ""
        var videoId = ""
        open fun volume(level: Double) { throw IllegalStateException("unsupported") }
        open fun fill() {}
        abstract fun load(url: String, mime: String, title: String)
        abstract fun pause()
        abstract fun resume()
        abstract fun seek(sec: Double)
        abstract fun stop()
        /** state (playing / paused / buffering / idle / ended), pos and dur in seconds */
        abstract fun status(): JSONObject
        open fun close() {}
    }

    // ---------------- Chromecast (Cast v2) ----------------

    private class CastV2(tv: Tv) : Session(tv) {
        private val sock: SSLSocket
        private val out: java.io.OutputStream
        private val lock = Any()
        @Volatile private var closed = false
        @Volatile private var transport: String? = null
        @Volatile private var mediaSession = 0
        @Volatile private var last = JSONObject()
        @Volatile private var loadError: String? = null
        private var reqId = 1
        private val gotTransport = CountDownLatch(1)
        private val gotMedia = CountDownLatch(1)

        init {
            // Cast devices use their own self-signed certificates; this link never leaves the home Wi-Fi.
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, rnd) }
            val raw = java.net.Socket(); raw.connect(InetSocketAddress(tv.host, tv.port), 5000)
            sock = ssl.socketFactory.createSocket(raw, tv.host, tv.port, true) as SSLSocket
            sock.soTimeout = 0
            sock.startHandshake()
            out = sock.outputStream
            Thread({ readLoop(BufferedInputStream(sock.inputStream)) }, "cast-read").apply { isDaemon = true; start() }
            Thread({
                try { while (!closed) { send(NS_HEART, "receiver-0", JSONObject().put("type", "PING")); Thread.sleep(5000) } } catch (_: Exception) {}
            }, "cast-ping").apply { isDaemon = true; start() }
            send(NS_CONN, "receiver-0", JSONObject().put("type", "CONNECT"))
        }

        override fun load(url: String, mime: String, title: String) {
            this.title = title
            send(NS_RECV, "receiver-0", JSONObject().put("type", "LAUNCH").put("appId", APP).put("requestId", next()))
            if (!gotTransport.await(12, TimeUnit.SECONDS)) throw IllegalStateException("the TV didn't open its player")
            val t = transport!!
            send(NS_CONN, t, JSONObject().put("type", "CONNECT"))
            val media = JSONObject().put("contentId", url).put("contentUrl", url).put("streamType", "BUFFERED").put("contentType", mime)
                .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
            send(NS_MEDIA, t, JSONObject().put("type", "LOAD").put("requestId", next()).put("media", media).put("autoplay", true).put("currentTime", 0))
            if (!gotMedia.await(12, TimeUnit.SECONDS)) throw IllegalStateException("the TV didn't load the video")
            loadError?.let { throw IllegalStateException(it) }
        }

        private fun mediaCmd(type: String, extra: JSONObject.() -> Unit = {}) {
            val t = transport ?: throw IllegalStateException("not connected")
            send(NS_MEDIA, t, JSONObject().put("type", type).put("requestId", next()).put("mediaSessionId", mediaSession).apply(extra))
        }
        override fun pause() = mediaCmd("PAUSE")
        override fun resume() = mediaCmd("PLAY")
        override fun seek(sec: Double) = mediaCmd("SEEK") { put("currentTime", sec) }
        override fun volume(level: Double) = send(NS_RECV, "receiver-0", JSONObject().put("type", "SET_VOLUME").put("requestId", next()).put("volume", JSONObject().put("level", level)))
        override fun stop() {
            try { mediaCmd("STOP") } catch (_: Exception) {}
        }

        override fun status(): JSONObject {
            if (closed) return JSONObject().put("state", "ended")
            try { mediaCmd("GET_STATUS") } catch (_: Exception) {}
            Thread.sleep(250)
            val st = last
            val ps = st.optString("playerState", "BUFFERING")
            val state = when (ps) {
                "PLAYING" -> "playing"; "PAUSED" -> "paused"; "BUFFERING" -> "buffering"
                "IDLE" -> if (st.optString("idleReason") in listOf("FINISHED", "CANCELLED", "INTERRUPTED", "ERROR")) "ended" else "idle"
                else -> "buffering"
            }
            return JSONObject().put("state", state).put("pos", st.optDouble("currentTime", 0.0))
                .put("dur", st.optJSONObject("media")?.optDouble("duration", 0.0) ?: dur).also { if (it.optDouble("dur") > 0) dur = it.optDouble("dur") }
        }
        private var dur = 0.0

        override fun close() {
            closed = true
            try { sock.close() } catch (_: Exception) {}
        }

        private fun next() = synchronized(lock) { ++reqId }

        private fun send(ns: String, dest: String, payload: JSONObject) {
            val msg = ByteArrayOutputStream()
            fun varint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { msg.write(x.toInt()); return }; msg.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
            fun str(field: Int, s: String) { val b = s.toByteArray(); varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); msg.write(b) }
            varint((1 shl 3).toLong()); varint(0)          // protocol_version = CASTV2_1_0
            str(2, "sender-0"); str(3, dest); str(4, ns)
            varint((5 shl 3).toLong()); varint(0)          // payload_type = STRING
            str(6, payload.toString())
            val body = msg.toByteArray()
            synchronized(lock) {
                out.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
                out.write(body); out.flush()
            }
        }

        private fun readLoop(inp: InputStream) {
            try {
                while (!closed) {
                    val head = readN(inp, 4)
                    val len = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
                    if (len !in 0..(4 shl 20)) throw IllegalStateException("bad frame")
                    val body = readN(inp, len)
                    var i = 0; var ns = ""; var payload = ""; var src = ""
                    fun varint(): Long { var r = 0L; var s = 0; while (true) { val b = body[i++].toInt() and 0xFF; r = r or ((b and 0x7F).toLong() shl s); if (b and 0x80 == 0) return r; s += 7 } }
                    while (i < body.size) {
                        val key = varint().toInt(); val f = key ushr 3
                        when (key and 7) {
                            0 -> varint()
                            2 -> { val n = varint().toInt(); val s = String(body, i, n, Charsets.UTF_8); i += n
                                when (f) { 2 -> src = s; 4 -> ns = s; 6 -> payload = s } }
                            1 -> i += 8
                            5 -> i += 4
                            else -> throw IllegalStateException("bad field")
                        }
                    }
                    if (payload.isEmpty() || !payload.startsWith("{")) continue
                    val j = JSONObject(payload)
                    when (j.optString("type")) {
                        "PING" -> send(NS_HEART, src.ifEmpty { "receiver-0" }, JSONObject().put("type", "PONG"))
                        "RECEIVER_STATUS" -> {
                            val apps = j.optJSONObject("status")?.optJSONArray("applications")
                            var mine: String? = null
                            if (apps != null) for (k in 0 until apps.length()) {
                                val a = apps.getJSONObject(k); if (a.optString("appId") == APP) mine = a.optString("transportId")
                            }
                            if (mine != null) { transport = mine; gotTransport.countDown() }
                            else if (transport != null && apps != null) closed = true // someone opened another app on the TV
                        }
                        "MEDIA_STATUS" -> {
                            val arr = j.optJSONArray("status")
                            if (arr != null && arr.length() > 0) {
                                val st = arr.getJSONObject(0)
                                mediaSession = st.optInt("mediaSessionId", mediaSession)
                                last = st
                            }
                            gotMedia.countDown()
                        }
                        "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> { loadError = "the TV couldn't play this video file"; gotMedia.countDown() }
                        "CLOSE" -> if (src == transport) closed = true
                    }
                }
            } catch (_: Exception) {
            } finally { closed = true; gotTransport.countDown(); gotMedia.countDown() }
        }

        private fun readN(i: InputStream, n: Int): ByteArray {
            val b = ByteArray(n); var got = 0
            while (got < n) { val r = i.read(b, got, n - got); if (r < 0) throw java.io.EOFException(); got += r }
            return b
        }

        companion object {
            const val APP = "CC1AD845" // Google's default media player on the TV
            const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
            const val NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat"
            const val NS_RECV = "urn:x-cast:com.google.cast.receiver"
            const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        }
    }

    // ---------------- a TV's web browser showing her TV page (/tv) ----------------

    /** A TV page that's open somewhere. It checks in every few seconds and picks up commands. */
    class WebTv(val id: String, @Volatile var name: String, @Volatile var host: String) {
        @Volatile var lastSeen = System.currentTimeMillis()
        @Volatile var state = JSONObject()
        val commands = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
    }
    private val webTvs = ConcurrentHashMap<String, WebTv>()

    /**
     * Called by the TV page: reports what it's doing and waits (up to [waitMs]) for a command.
     * Returns the command, or an empty object.
     */
    fun webPoll(id: String, name: String, host: String, state: JSONObject, waitMs: Long): JSONObject {
        val safe = id.filter { it.isLetterOrDigit() }.take(24)
        if (safe.isEmpty()) return JSONObject()
        if (webTvs.size > 20) webTvs.entries.removeAll { System.currentTimeMillis() - it.value.lastSeen > 120_000 }
        val tv = webTvs.getOrPut(safe) { WebTv(safe, name, host) }
        tv.name = name.take(40).ifBlank { "TV" }; tv.host = host
        tv.lastSeen = System.currentTimeMillis(); tv.state = state
        if (waitMs <= 0) return JSONObject() // just a progress report: leave commands for the waiting ask
        return tv.commands.poll(waitMs, TimeUnit.MILLISECONDS) ?: JSONObject()
    }

    /** A playable link for the TV page itself (same address it was opened on). */
    fun webLink(videoId: String): String? {
        if (!Library.videos.containsKey(videoId)) return null
        val token = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        links[token] = Link(videoId, System.currentTimeMillis() + 12 * 3600_000L)
        return "/media/$token/video.${ext(videoId)}"
    }

    private class WebSession(tv: Tv, private val web: WebTv, private val vidId: String) : Session(tv) {
        private var startedAt = 0L
        private fun cmd(c: String, extra: JSONObject.() -> Unit = {}) { web.commands.clear(); web.commands.add(JSONObject().put("cmd", c).apply(extra)) }
        override fun load(url: String, mime: String, title: String) {
            this.title = title; startedAt = System.currentTimeMillis()
            cmd("play") { put("video", vidId); put("title", title) }
        }
        override fun pause() = cmd("pause")
        override fun resume() = cmd("resume")
        override fun seek(sec: Double) = cmd("seek") { put("to", sec) }
        override fun stop() = cmd("stop")
        override fun volume(level: Double) = cmd("volume") { put("level", level) }
        override fun fill() = cmd("fill")
        override fun status(): JSONObject {
            if (System.currentTimeMillis() - web.lastSeen > 45_000) return JSONObject().put("state", "ended")
            val s = web.state
            // the page needs a moment to pick up a new video
            if (s.optString("video") != vidId) return JSONObject().put("state", if (System.currentTimeMillis() - startedAt < 30_000) "buffering" else "ended")
            return JSONObject().put("state", s.optString("state", "buffering")).put("pos", s.optDouble("pos", 0.0)).put("dur", s.optDouble("dur", 0.0))
                .put("vol", s.optDouble("vol", 1.0)).put("fill", s.optBoolean("fill")).put("muted", s.optBoolean("muted"))
        }
    }

    // ---------------- DLNA / UPnP smart TVs ----------------

    private class Dlna(tv: Tv) : Session(tv) {
        private var dur = 0.0

        override fun load(url: String, mime: String, title: String) {
            this.title = title
            try { soap("Stop", "") } catch (_: Exception) {}
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>${xml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:*\">${xml(url)}</res></item></DIDL-Lite>"
            soap("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(didl)}</CurrentURIMetaData>")
            soap("Play", "<Speed>1</Speed>")
        }
        override fun pause() { soap("Pause", "") }
        override fun resume() { soap("Play", "<Speed>1</Speed>") }
        override fun seek(sec: Double) { soap("Seek", "<Unit>REL_TIME</Unit><Target>${hms(sec)}</Target>") }
        override fun stop() { soap("Stop", "") }

        override fun status(): JSONObject {
            val t = soap("GetTransportInfo", "")
            val p = soap("GetPositionInfo", "")
            val state = when (tag(t, "CurrentTransportState")) {
                "PLAYING" -> "playing"; "PAUSED_PLAYBACK" -> "paused"; "TRANSITIONING" -> "buffering"
                "STOPPED", "NO_MEDIA_PRESENT" -> "ended"; else -> "buffering"
            }
            secs(tag(p, "TrackDuration")).let { if (it > 0) dur = it }
            return JSONObject().put("state", state).put("pos", secs(tag(p, "RelTime"))).put("dur", dur)
        }

        private fun soap(action: String, args: String): String {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$SVC\">" +
                "<InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
            val c = URL(tv.control).openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 8000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$SVC#$action\"")
            val bytes = body.toByteArray()
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
            if (code >= 400) throw IllegalStateException("TV said no to $action")
            return text
        }

        companion object { const val SVC = "urn:schemas-upnp-org:service:AVTransport:1" }
    }

    // ---------------- little helpers ----------------

    private fun httpGet(url: String, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        return c.inputStream.use { String(it.readBytes()) }
    }
    private fun tag(xml: String, name: String): String? =
        Regex("(?s)<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun hms(sec: Double): String { val s = sec.toLong().coerceAtLeast(0); return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
    private fun secs(t: String?): Double {
        if (t.isNullOrEmpty() || t.startsWith("NOT")) return 0.0
        return try { t.split(':').fold(0.0) { a, p -> a * 60 + p.toDouble() } } catch (_: Exception) { 0.0 }
    }
}
