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
        val codes = keys.split(',').mapNotNull { KEYS[it.trim()] }
        if (codes.isEmpty()) return "Unknown button."
        return why(t, Adb.quick(ctx, host, "input keyevent " + codes.joinToString(" ")))
    }

    /** Types into whatever box is open on the TV (search, sign-in…). */
    fun text(ctx: Context, host: String, text: String): String {
        val t = tv(host) ?: return "Couldn't find that TV."
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
