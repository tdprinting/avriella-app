package com.avriella.tv.cam

import android.content.Context
import com.avriella.tv.data.Library
import org.json.JSONArray
import org.json.JSONObject

/**
 * A backup remote for the Fire TVs in the house: arrows, OK, back, home, play/pause, volume, power,
 * typing and opening apps. Her phone talks to each TV over ADB, so "ADB debugging" has to be on
 * (Settings → My Fire TV → Developer options), and the first time the TV asks "Allow debugging?".
 */
object FireRemote {
    class Tv(val name: String, val host: String)

    @Volatile private var known: List<Tv> = emptyList()
    @Volatile private var scannedAt = 0L

    private val KEYS = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
        "back" to 4, "home" to 3, "menu" to 82,
        "playpause" to 85, "rew" to 89, "ff" to 90, "next" to 87, "prev" to 88,
        "volup" to 24, "voldown" to 25, "mute" to 164,
        "power" to 26, "sleep" to 223, "wake" to 224, "del" to 67, "search" to 84,
    )

    /** Streaming apps we know, shown when they're on that TV. */
    private val APPS = listOf(
        "Netflix" to "com.netflix.ninja",
        "YouTube" to "com.amazon.firetv.youtube",
        "YouTube Kids" to "com.amazon.firetv.youtube.kids",
        "Prime Video" to "com.amazon.avod",
        "Disney+" to "com.disney.disneyplus",
        "Hulu" to "com.hulu.plus",
        "Max" to "com.wbd.stream",
        "Max" to "com.hbo.hbonow",
        "Peacock" to "com.peacocktv.peacockandroid",
        "Paramount+" to "com.cbs.ott",
        "Apple TV" to "com.apple.atve.amazon.appletv",
        "Pluto TV" to "tv.pluto.android",
        "Tubi" to "com.tubitv",
        "PBS Kids" to "org.pbskids.video",
        "Plex" to "com.plexapp.android",
        "Spotify" to "com.spotify.tv.android",
        "Silk" to "com.amazon.cloud9",
    )
    private val appsCache = java.util.concurrent.ConcurrentHashMap<String, JSONArray>()

    fun tvs(ctx: Context, fresh: Boolean = false): JSONArray {
        if (known.isEmpty()) known = remembered(ctx)
        if (fresh || known.isEmpty()) rescan(ctx)
        else if (System.currentTimeMillis() - scannedAt > 5 * 60_000) Thread { rescan(ctx) }.start()   // answer now, refresh behind
        return JSONArray().apply { known.forEach { put(JSONObject().put("name", it.name).put("host", it.host)) } }
    }

    @Synchronized private fun rescan(ctx: Context) {
        val found = Cast.fireTvs(ctx).distinctBy { it.host }.map { Tv(it.name, it.host) }
        scannedAt = System.currentTimeMillis()
        if (found.isEmpty()) return
        known = found.sortedBy { it.name.lowercase() }
        val arr = JSONArray().apply { known.forEach { put(JSONObject().put("name", it.name).put("host", it.host)) } }
        ctx.getSharedPreferences("fire_remote", Context.MODE_PRIVATE).edit().putString("tvs", arr.toString()).apply()
    }

    // the TVs from last time, so the remote is ready the moment it opens
    private fun remembered(ctx: Context): List<Tv> = try {
        val a = JSONArray(ctx.getSharedPreferences("fire_remote", Context.MODE_PRIVATE).getString("tvs", "[]"))
        (0 until a.length()).map { Tv(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("host")) }
    } catch (_: Exception) { emptyList() }

    private fun tv(host: String): Tv? {
        known.firstOrNull { it.host == host }?.let { return it }
        if (known.isEmpty()) known = remembered(Library.appCtx)
        return known.firstOrNull { it.host == host }
    }

    /** Opens the connection to the TV ahead of the first press. */
    fun warm(ctx: Context, host: String): String {
        val t = tv(host) ?: return "Couldn't find that TV."
        fastDevice(ctx, host)   // find the fast way now, so the first press is quick too
        return why(t, Adb.quick(ctx, host, ":"))
    }

    private fun why(t: Tv, r: String) = when (r) {
        "ok" -> "ok"
        "off" -> "ADB debugging is off on ${t.name}. On that TV: Settings → My Fire TV → Developer options → ADB debugging → On."
        else -> "${t.name} didn't let her phone in. Look at the TV: tick \"Always allow\" and press OK on \"Allow debugging?\"."
    }

    /** Presses one or more buttons ("up", "ok", "volup"…). */
    fun key(ctx: Context, host: String, keys: String): String {
        val t = tv(host) ?: return "Couldn't find that TV. Pull down to look again."
        val names = keys.split(',').map { it.trim() }.filter { KEYS.containsKey(it) }
        if (names.isEmpty()) return "Unknown button."
        // Fast way: write the key straight into the TV's own keyboard device (well under a millisecond).
        // "input keyevent" starts a Java program each time: 1-2 seconds on Android 9 Fire TVs.
        val fast = fastDevice(ctx, host)
        if (fast != null && names.all { RAW.containsKey(it) }) {
            val (dev, wide) = fast
            val bytes = StringBuilder()
            names.forEach { n -> val c = RAW.getValue(n); bytes.append(ev(1, c, 1, wide)).append(ev(0, 0, 0, wide)).append(ev(1, c, 0, wide)).append(ev(0, 0, 0, wide)) }
            val r = Adb.quick(ctx, host, "print -n '$bytes' >$dev")
            if (r == "ok") return "ok"
        }
        return why(t, Adb.quick(ctx, host, "input keyevent " + names.joinToString(" ") { KEYS.getValue(it).toString() }))
    }

    /** Linux key codes the Fire TV's "amzkeyboard" device turns into these buttons (from its amzkeyboard.kl). */
    private val RAW = mapOf(
        "up" to 103, "down" to 108, "left" to 105, "right" to 106, "ok" to 353,
        "back" to 158, "home" to 172, "menu" to 139,
        "playpause" to 164, "rew" to 168, "ff" to 208, "next" to 163, "prev" to 165,
        "volup" to 115, "voldown" to 114, "mute" to 113, "power" to 116,
        "del" to 14, "sleep" to 142, "wake" to 143,
    )

    /** Letters, numbers and a few signs on the TV's keyboard device: key code, and whether Shift is held. */
    private val CHARS: Map<Char, Pair<Int, Boolean>> by lazy {
        val m = HashMap<Char, Pair<Int, Boolean>>()
        "qwertyuiop".forEachIndexed { i, c -> m[c] = Pair(16 + i, false) }
        "asdfghjkl".forEachIndexed { i, c -> m[c] = Pair(30 + i, false) }
        "zxcvbnm".forEachIndexed { i, c -> m[c] = Pair(44 + i, false) }
        "1234567890".forEachIndexed { i, c -> m[c] = Pair(2 + i, false) }
        m.keys.filter { it.isLetter() }.toList().forEach { c -> m[c.uppercaseChar()] = Pair(m.getValue(c).first, true) }
        mapOf(' ' to 57, '.' to 52, ',' to 51, '-' to 12, '/' to 53, '\'' to 40, ';' to 39, '=' to 13).forEach { (c, k) -> m[c] = Pair(k, false) }
        mapOf('!' to 2, '@' to 3, '#' to 4, '&' to 8, '?' to 53, '_' to 12, ':' to 39, '"' to 40, '+' to 13).forEach { (c, k) -> m[c] = Pair(k, true) }
        m
    }

    private fun tap(sb: StringBuilder, code: Int, wide: Boolean) {
        sb.append(ev(1, code, 1, wide)).append(ev(0, 0, 0, wide)).append(ev(1, code, 0, wide)).append(ev(0, 0, 0, wide))
    }

    /** Opens a page of the TV's settings, the quick menu, or puts the TV to sleep. */
    fun settings(ctx: Context, host: String, which: String): String {
        val t = tv(host) ?: return "Couldn't find that TV."
        val v2 = "-p com.amazon.tv.settings.v2"
        val cmd = when (which) {
            "quick" -> {
                // the Fire TV quick menu: hold Home for a second
                val fast = fastDevice(ctx, host)
                if (fast != null) { val (dev, w) = fast
                    "print -n '${ev(1, 172, 1, w)}${ev(0, 0, 0, w)}' >$dev; sleep 1; print -n '${ev(1, 172, 0, w)}${ev(0, 0, 0, w)}' >$dev"
                } else "input keyevent --longpress 3"
            }
            "sleep" -> return key(ctx, host, "sleep")
            else -> {
                val target = when (which) {
                    "picture" -> listOf("-a com.amazon.tv.settings.action.PICTURE_SETTINGS", "-a com.amazon.device.settings.action.DISPLAY_AND_SOUNDS $v2")
                    "display" -> listOf("-a com.amazon.device.settings.action.DISPLAY_AND_SOUNDS $v2")
                    "network" -> listOf("-a android.settings.WIFI_SETTINGS")
                    "remotes" -> listOf("-a com.amazon.device.settings.action.CONTROLLERS $v2")
                    "apps" -> listOf("-a android.settings.APPLICATION_SETTINGS")
                    "captions" -> listOf("-a com.amazon.device.settings.action.ACCESSIBILITY_CLOSED_CAPTION $v2")
                    "about" -> listOf("-a android.settings.DEVICE_INFO_SETTINGS")
                    "all" -> emptyList()
                    else -> return "Unknown setting."
                }
                // try each page in turn; the first one this Fire TV has opens (am prints "Error" when it doesn't)
                (target + "-a android.settings.SETTINGS").joinToString(" || ") { "! am start $it 2>&1 | grep -q Error" }
            }
        }
        return why(t, Adb.quick(ctx, host, cmd))
    }

    /** One input_event (time 0, type, code, value) as \xHH escapes for the shell's print: 16 bytes on 32-bit TVs, 24 on 64-bit. */
    private fun ev(type: Int, code: Int, value: Int, wide: Boolean): String {
        val b = java.nio.ByteBuffer.allocate(if (wide) 24 else 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (wide) b.putLong(0).putLong(0) else b.putInt(0).putInt(0)
        b.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        return b.array().joinToString("") { "\\x%02x".format(it.toInt() and 0xFF) }
    }

    // which /dev/input device takes the fast way on each TV ("" = none, use the slow way)
    private val fastDev = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun fastDevice(ctx: Context, host: String): Pair<String, Boolean>? {
        fastDev[host]?.let { v -> return if (v.isEmpty()) null else Pair(v.substringBefore('|'), v.endsWith("|64")) }
        // (the device names in /sys aren't readable over ADB on Fire TVs; getevent can list them)
        val out = Adb.shell(ctx, host, "d=\$(getevent -p 2>/dev/null | grep -B1 '\"amzkeyboard\"' | grep -o '/dev/input/event[0-9]*' | head -1); [ -n \"\$d\" ] && [ -w \"\$d\" ] && echo \$d; echo end")
            ?: return null   // couldn't ask: try again next time
        val dev = out.lines().map { it.trim() }.firstOrNull { it.startsWith("/dev/input/event") } ?: ""
        if (dev.isEmpty()) { fastDev[host] = ""; android.util.Log.i("FireRemote", "no fast keys on $host"); return null }
        val wide = Adb.shell(ctx, host, "getprop ro.product.cpu.abi")?.trim()?.let { it.startsWith("arm64") || it.startsWith("x86_64") } ?: return null
        fastDev[host] = dev + if (wide) "|64" else "|32"
        android.util.Log.i("FireRemote", "fast keys on $host via $dev (${if (wide) 64 else 32}-bit)")
        return Pair(dev, wide)
    }

    /** Types into whatever box is open on the TV (search, sign-in…). */
    fun text(ctx: Context, host: String, text: String): String {
        val t = tv(host) ?: return "Couldn't find that TV."
        // fast way: type it on the TV's keyboard device, a few letters per line (the shell's line has a size limit)
        val fast = fastDevice(ctx, host)
        val typed = text.take(200).filter { CHARS.containsKey(it) }
        if (fast != null && typed.isNotEmpty()) {
            val (dev, w) = fast
            var ok = true
            typed.chunked(8).forEach { part ->
                val sb = StringBuilder()
                part.forEach { c ->
                    val (code, shift) = CHARS.getValue(c)
                    if (shift) sb.append(ev(1, 42, 1, w)).append(ev(0, 0, 0, w))
                    tap(sb, code, w)
                    if (shift) sb.append(ev(1, 42, 0, w)).append(ev(0, 0, 0, w))
                }
                if (ok && Adb.quick(ctx, host, "print -n '$sb' >$dev") != "ok") ok = false
            }
            if (ok) return "ok"
        }
        val safe = text.filter { it.isLetterOrDigit() || it in " .,-_@!?" }.take(200).replace(" ", "%s")
        if (safe.isEmpty()) return "Type some letters first."
        return why(t, Adb.quick(ctx, host, "input text '$safe'"))
    }

    /** The streaming apps on that TV. */
    fun apps(ctx: Context, host: String): JSONArray {
        val t = tv(host) ?: return JSONArray()
        appsCache[host]?.let { return it }
        val out = Adb.shell(ctx, host, "pm list packages", approveWaitMs = 30_000) ?: return JSONArray()
        val have = out.lines().map { it.removePrefix("package:").trim() }.toSet()
        val list = JSONArray()
        val seen = HashSet<String>()
        APPS.forEach { (name, pkg) -> if (pkg in have && seen.add(name)) list.put(JSONObject().put("name", name).put("pkg", pkg)) }
        if (list.length() > 0) appsCache[t.host] = list
        return list
    }

    fun launch(ctx: Context, host: String, pkg: String): String {
        val t = tv(host) ?: return "Couldn't find that TV."
        if (APPS.none { it.second == pkg }) return "Unknown app."
        // Fire TV apps start from the TV launcher; a few only have the phone-style one
        return why(t, Adb.quick(ctx, host,
            "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1 | grep -q 'No activities' && monkey -p $pkg -c android.intent.category.LAUNCHER 1"))
    }

    /** Opens her DVD shelf (the TV page) in Silk on that TV. */
    fun shelf(ctx: Context, host: String): String {
        val t = tv(host) ?: return "Couldn't find that TV."
        val page = Cast.pageForHost(host, t.name) ?: return "Her phone isn't on the Wi-Fi."
        return why(t, Adb.quick(ctx, host, "am start -a android.intent.action.VIEW -d '$page' com.amazon.cloud9"))
    }
}
