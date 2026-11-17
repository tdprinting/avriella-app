package com.avriella.tv.data

import android.content.Context
import android.util.Log
import com.avriella.tv.ui.Pal
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Video(
    val id: String,
    val uri: String,
    var title: String,
    var durationMs: Long = 0,
    var positionMs: Long = 0,
    var lastPlayed: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** A "Show" (category like "Bluey") or a "Playlist" (custom ordered mix). Both are ordered lists of videos. */
class Shelf(
    val id: String,
    var name: String,
    var kind: String,
    var color: Int,
    var emoji: String,
    val videoIds: MutableList<String> = mutableListOf(),
) {
    val isPlaylist get() = kind == Library.KIND_PLAYLIST
}

class Settings {
    var childName = "Avriella"
    var autoplay = true
    var loop = true
    var resume = true
    var sleepDefaultMin = 0
    var relockSec = 30
    var lockHint = true
    var brightness = 0
    /** Turn the video picture so it stays level while she flips the phone. */
    var levelVideo = false
    var camEnabled = false
    var camCode = ""
    /** Off: iPhones on the same Wi-Fi / hotspot connect without a code. */
    var camRequireCode = false
    var lastVideoId: String? = null
    var lastShelfId: String? = null
}

object Library {
    const val ALL = "__all__"
    const val KIND_SHOW = "show"
    const val KIND_PLAYLIST = "playlist"
    private const val TAG = "Library"

    lateinit var appCtx: Context
        private set
    private lateinit var file: File

    val videos = LinkedHashMap<String, Video>()
    val shelves = mutableListOf<Shelf>()
    val settings = Settings()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        file = File(appCtx.filesDir, "library.json")
        load()
        if (settings.camCode.isEmpty()) {
            settings.camCode = (100000..999999).random().toString()
            save()
        }
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---------- videos ----------
    fun findByUri(uri: String) = videos.values.firstOrNull { it.uri == uri }

    fun addVideo(uri: String, title: String, durationMs: Long = 0): Video {
        findByUri(uri)?.let { return it }
        val v = Video(newId(), uri, cleanTitle(title), durationMs)
        videos[v.id] = v
        return v
    }

    fun deleteVideo(id: String) {
        videos.remove(id)
        shelves.forEach { it.videoIds.remove(id) }
        File(appCtx.filesDir, "thumbs/$id.jpg").delete()
        if (settings.lastVideoId == id) settings.lastVideoId = null
    }

    fun cleanTitle(name: String): String {
        var t = name
        val dot = t.lastIndexOf('.')
        if (dot > 0 && t.length - dot <= 5) t = t.substring(0, dot)
        return t.replace('_', ' ').trim().ifEmpty { "Video" }
    }

    // ---------- shelves ----------
    fun shows() = shelves.filter { it.kind == KIND_SHOW }
    fun playlists() = shelves.filter { it.kind == KIND_PLAYLIST }
    fun shelf(id: String?) = shelves.firstOrNull { it.id == id }

    fun createShelf(name: String, kind: String): Shelf {
        val s = Shelf(
            newId(), name, kind,
            Pal.TILE[shelves.size % Pal.TILE.size],
            if (kind == KIND_PLAYLIST) "🎵" else "📺",
        )
        shelves.add(s)
        return s
    }

    fun deleteShelf(id: String) {
        shelves.removeAll { it.id == id }
        if (settings.lastShelfId == id) settings.lastShelfId = null
    }

    fun shelfName(id: String?): String = when (id) {
        null, ALL -> "All videos"
        else -> shelf(id)?.name ?: "All videos"
    }

    fun videosIn(shelfId: String?): List<Video> =
        if (shelfId == null || shelfId == ALL) videos.values.sortedWith(compareBy(natural) { it.title })
        else shelf(shelfId)?.videoIds?.mapNotNull { videos[it] } ?: emptyList()

    /** Which video in this list should play when a parent taps "Play"? Picks up where she left off. */
    fun resumeIndex(queue: List<Video>): Int {
        if (queue.isEmpty()) return 0
        val last = queue.maxByOrNull { it.lastPlayed } ?: return 0
        if (last.lastPlayed == 0L) return 0
        val i = queue.indexOf(last)
        val nearlyDone = last.durationMs > 0 && last.positionMs > last.durationMs - 30_000
        return if (nearlyDone) (i + 1) % queue.size else i
    }

    /** "Bluey - Season 1 - First Half", "Bluey - Season 2 ..." -> "Bluey" */
    fun guessName(titles: List<String>, fallback: String): String {
        if (titles.isEmpty()) return fallback
        var prefix = titles[0]
        for (t in titles) {
            var i = 0
            while (i < prefix.length && i < t.length && prefix[i].equals(t[i], true)) i++
            prefix = prefix.substring(0, i)
        }
        prefix = prefix.trim().trimEnd('-', '_', '.', ',', ':', '(', '[').trim()
        if (titles.size == 1) prefix = titles[0].substringBefore(" - ").trim()
        return if (prefix.length >= 2) prefix else fallback
    }

    val natural: Comparator<String> = Comparator { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val n = x.trimStart('0').length.compareTo(y.trimStart('0').length)
                if (n != 0) n else x.trimStart('0').compareTo(y.trimStart('0'))
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size.compareTo(rb.size)
    }

    // ---------- persistence ----------
    private fun load() {
        val bak = File(file.path + ".bak")
        // If the main file is missing or damaged, fall back to the last good copy (never start empty by accident).
        for (f in listOf(file, bak)) {
            if (!f.exists()) continue
            if (loadFrom(f)) { if (f === bak) Log.w(TAG, "library restored from backup"); return }
            videos.clear(); shelves.clear()
        }
    }

    private fun loadFrom(f: File): Boolean {
        try {
            val o = JSONObject(f.readText())
            o.optJSONArray("videos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    val v = Video(
                        j.getString("id"), j.getString("uri"), j.optString("title", "Video"),
                        j.optLong("dur"), j.optLong("pos"), j.optLong("last"), j.optLong("added"),
                    )
                    videos[v.id] = v
                }
            }
            o.optJSONArray("shelves")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    val ids = mutableListOf<String>()
                    j.optJSONArray("ids")?.let { a -> for (k in 0 until a.length()) ids.add(a.getString(k)) }
                    shelves.add(
                        Shelf(
                            j.getString("id"), j.getString("name"), j.optString("kind", KIND_SHOW),
                            j.optInt("color", Pal.TILE[0]), j.optString("emoji", "📺"),
                            ids.filter { videos.containsKey(it) }.toMutableList(),
                        )
                    )
                }
            }
            o.optJSONObject("settings")?.let { s ->
                settings.childName = s.optString("childName", settings.childName)
                settings.autoplay = s.optBoolean("autoplay", true)
                settings.loop = s.optBoolean("loop", true)
                settings.resume = s.optBoolean("resume", true)
                settings.sleepDefaultMin = s.optInt("sleepDefault", 0)
                settings.relockSec = s.optInt("relockSec", 30)
                settings.lockHint = s.optBoolean("lockHint", true)
                settings.brightness = s.optInt("brightness", 0)
                settings.levelVideo = s.optBoolean("levelVideo", false)
                settings.camEnabled = s.optBoolean("camEnabled", false)
                settings.camCode = s.optString("camCode", "")
                settings.camRequireCode = s.optBoolean("camRequireCode", false)
                settings.lastVideoId = s.optString("lastVideo").ifEmpty { null }
                settings.lastShelfId = s.optString("lastShelf").ifEmpty { null }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Could not read library " + f.name, e)
            return false
        }
    }

    @Synchronized
    fun save() {
        try {
            val o = JSONObject()
            o.put("videos", JSONArray().apply {
                videos.values.forEach { v ->
                    put(JSONObject().apply {
                        put("id", v.id); put("uri", v.uri); put("title", v.title)
                        put("dur", v.durationMs); put("pos", v.positionMs)
                        put("last", v.lastPlayed); put("added", v.addedAt)
                    })
                }
            })
            o.put("shelves", JSONArray().apply {
                shelves.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id); put("name", s.name); put("kind", s.kind)
                        put("color", s.color); put("emoji", s.emoji)
                        put("ids", JSONArray(s.videoIds))
                    })
                }
            })
            o.put("settings", JSONObject().apply {
                put("childName", settings.childName); put("autoplay", settings.autoplay)
                put("loop", settings.loop); put("resume", settings.resume)
                put("sleepDefault", settings.sleepDefaultMin); put("relockSec", settings.relockSec)
                put("lockHint", settings.lockHint); put("brightness", settings.brightness); put("levelVideo", settings.levelVideo)
                put("camEnabled", settings.camEnabled); put("camCode", settings.camCode); put("camRequireCode", settings.camRequireCode)
                put("lastVideo", settings.lastVideoId ?: ""); put("lastShelf", settings.lastShelfId ?: "")
            })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            // keep the previous good copy, unless this save would throw away every video
            if (file.exists() && (videos.isNotEmpty() || !File(file.path + ".bak").exists())) file.copyTo(File(file.path + ".bak"), true)
            if (!tmp.renameTo(file)) {
                file.delete(); tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save library", e)
        }
    }
}
