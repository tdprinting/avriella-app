package com.avriella.tv

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Switch
import com.avriella.tv.data.Library
import com.avriella.tv.data.Shelf
import com.avriella.tv.data.Video
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.askText
import com.avriella.tv.ui.card
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.formatDuration
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pickMany
import com.avriella.tv.ui.pickOne
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.pressable
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.section
import com.avriella.tv.ui.toast
import com.avriella.tv.ui.vbox

/** Grown-up management: add videos, make shows & playlists, settings. */
class ManageActivity : ParentActivity() {
    private val REQ_FILES = 11
    private val REQ_TREE = 12
    private val REQ_PERM = 13
    private val main = Handler(Looper.getMainLooper())

    override fun build() {
        titleView.text = "Manage"

        body.addView(section("➕  Add videos"))
        val add = card(16)
        add.addView(label("Videos stay on this phone and play with no internet.", 14f, Pal.SUB).apply { setPadding(0, 0, 0, dp(10)) })
        add.addView(pill("🔎  Find videos on this phone", Pal.BLUE) { scanPhone() }, lp(MATCH, WRAP))
        add.addView(pill("📁  Add a whole folder as a show", Pal.ORANGE) { pickFolder() }, lp(MATCH, WRAP, t = dp(8)))
        add.addView(pill("🎬  Pick video files", Pal.PURPLE) { pickFiles() }, lp(MATCH, WRAP, t = dp(8)))
        body.addView(add)

        body.addView(section("📺  Shows"))
        Library.shows().forEach { body.addView(shelfRow(it), lp(t = dp(6))) }
        body.addView(pill("＋  New show", Pal.CARD, Pal.INK) {
            askText("New show", "", "Create") { n -> val s = Library.createShelf(n, Library.KIND_SHOW); Library.save(); openEdit(s) }
        }, lp(MATCH, WRAP, t = dp(8)))

        body.addView(section("🎵  Playlists"))
        body.addView(label("A playlist is your own mix of videos in the order you choose. It auto-plays one after another.", 14f, Pal.SUB).apply { setPadding(dp(4), 0, dp(4), dp(6)) })
        Library.playlists().forEach { body.addView(shelfRow(it), lp(t = dp(6))) }
        body.addView(pill("＋  New playlist", Pal.CARD, Pal.INK) {
            askText("New playlist", "Bedtime mix", "Create") { n -> val s = Library.createShelf(n, Library.KIND_PLAYLIST); Library.save(); openEdit(s) }
        }, lp(MATCH, WRAP, t = dp(8)))

        body.addView(pill("🎞️  All videos (${Library.videos.size})", Pal.CARD, Pal.INK) {
            startActivity(Intent(this, ShelfActivity::class.java).putExtra("shelf", Library.ALL))
        }, lp(MATCH, WRAP, t = dp(14)))

        body.addView(section("⚙️  Settings"))
        val s = Library.settings
        val set = card(6)
        set.addView(clickRow("Child's name", s.childName) { askText("Child's name", s.childName) { s.childName = it; Library.save(); rebuild() } })
        set.addView(switchRow("Auto-play next video", "When one ends, the next one starts", s.autoplay) { s.autoplay = it; Library.save() })
        set.addView(switchRow("Start over at the end", "After the last video, go back to the first", s.loop) { s.loop = it; Library.save() })
        set.addView(switchRow("Resume where she left off", "Remember the spot in each video", s.resume) { s.resume = it; Library.save() })
        val sleepOpts = listOf(0, 15, 30, 45, 60, 90, 120)
        set.addView(clickRow("Sleep timer (default)", if (s.sleepDefaultMin == 0) "Off" else "${s.sleepDefaultMin} min") {
            pickOne("Stop playing after…", sleepOpts.map { if (it == 0) "Off" else "$it minutes" }) { s.sleepDefaultMin = sleepOpts[it]; Library.save(); rebuild() }
        })
        val relock = listOf(15, 30, 60, 120)
        set.addView(clickRow("Parent panel locks again after", "${s.relockSec} seconds") {
            pickOne("Auto-lock the parent panel after…", relock.map { "$it seconds" }) { s.relockSec = relock[it]; Library.save(); rebuild() }
        })
        set.addView(switchRow("Keep video level", "Turns the picture as she flips the phone, so the show stays upright", s.levelVideo) { s.levelVideo = it; Library.save() })
        set.addView(switchRow("Show unlock hint", "Small \"hold both volume buttons\" note when she taps a locked screen", s.lockHint) { s.lockHint = it; Library.save() })
        body.addView(set)

        body.addView(section("🛡️  Kid lock & 📷 Baby cam"))
        body.addView(pill("🔒  Kid lock setup", Pal.GREEN) { startActivity(Intent(this, LockSetupActivity::class.java)) }, lp(MATCH, WRAP))
        body.addView(pill("📷  Baby cam for iPhone", Pal.BLUE) { startActivity(Intent(this, CamSetupActivity::class.java)) }, lp(MATCH, WRAP, t = dp(8)))
    }

    private fun openEdit(s: Shelf) = startActivity(Intent(this, EditShelfActivity::class.java).putExtra("shelf", s.id))

    private fun shelfRow(s: Shelf): View {
        val r = dpf(18f)
        val row = hbox().apply {
            background = pressable(rounded(Pal.CARD, r), r)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { openEdit(s) }
        }
        row.addView(label(s.emoji, 26f), lp(WRAP, WRAP, r = dp(12)))
        val col = vbox()
        col.addView(label(s.name, 16f, Pal.INK, true, 1))
        val total = Library.videosIn(s.id).sumOf { it.durationMs }
        col.addView(label("${s.videoIds.size} videos" + (if (total > 0) " · ${formatDuration(total)}" else ""), 13f, Pal.SUB))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(label("Edit ›", 14f, Pal.BLUE, true))
        return row
    }

    private fun clickRow(title: String, value: String, onClick: () -> Unit): View {
        val row = hbox().apply {
            setPadding(dp(12), dp(14), dp(12), dp(14))
            val r = dpf(14f)
            background = pressable(rounded(0, r), r)
            setOnClickListener { onClick() }
        }
        row.addView(label(title, 16f, Pal.INK), lp(0, WRAP, 1f))
        row.addView(label("$value  ›", 15f, Pal.BLUE, true))
        return row
    }

    private fun switchRow(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit): View {
        val row = hbox().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val col = vbox()
        col.addView(label(title, 16f, Pal.INK))
        col.addView(label(sub, 13f, Pal.SUB))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, c -> change(c) } })
        return row
    }

    // ---------------- importing ----------------
    private fun videoPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun scanPhone() {
        if (checkSelfPermission(videoPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(videoPermission()), REQ_PERM); return
        }
        toast("Looking for videos…")
        Thread {
            val found = ArrayList<Triple<String, String, Long>>()
            try {
                val col = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                contentResolver.query(
                    col, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION),
                    null, null, MediaStore.Video.Media.DISPLAY_NAME + " ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(col, c.getLong(0)).toString()
                        found += Triple(uri, c.getString(1) ?: "Video", c.getLong(2))
                    }
                }
            } catch (_: Exception) {}
            main.post { offerFound(found) }
        }.start()
    }

    private fun offerFound(found: List<Triple<String, String, Long>>) {
        val fresh = found.filter { Library.findByUri(it.first) == null }
            .sortedWith(compareBy(Library.natural) { it.second })
        if (fresh.isEmpty()) {
            toast(if (found.isEmpty()) "No videos found. Copy her shows onto the phone first (finished downloads only)." else "All videos on this phone are already added.")
            return
        }
        val names = fresh.map { Library.cleanTitle(it.second) + if (it.third > 0) "  (${formatDuration(it.third)})" else "" }
        pickMany("Add which videos?", names, BooleanArray(fresh.size) { true }, "Add") { picked ->
            val vids = picked.map { fresh[it] }.map { Library.addVideo(it.first, it.second, it.third) }
            Library.save()
            afterAdd(vids)
        }
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(i, REQ_FILES) } catch (_: Exception) { toast("No file picker on this phone") }
    }

    private fun pickFolder() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE) } catch (_: Exception) { toast("No folder picker on this phone") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris += cd.getItemAt(k).uri }
                data.data?.let { if (it !in uris) uris += it }
                val vids = uris.map { u ->
                    try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    Library.addVideo(u.toString(), displayName(u))
                }
                Library.save()
                afterAdd(vids)
            }
            REQ_TREE -> {
                val tree = data.data ?: return
                try { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                toast("Reading folder…")
                Thread {
                    val list = ArrayList<Pair<String, String>>()
                    var folderName = "My show"
                    try {
                        val rootId = DocumentsContract.getTreeDocumentId(tree)
                        folderName = queryName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: folderName
                        walk(tree, rootId, list, 0)
                    } catch (_: Exception) {}
                    main.post { importFolder(folderName, list) }
                }.start()
            }
        }
    }

    private fun walk(tree: Uri, docId: String, out: MutableList<Pair<String, String>>, depth: Int) {
        if (depth > 4) return
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        contentResolver.query(kids, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: ""; val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(tree, id, out, depth + 1)
                else if (mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "m4v", "mov", "3gp", "avi", "ts")) {
                    out += DocumentsContract.buildDocumentUriUsingTree(tree, id).toString() to name
                }
            }
        }
    }

    private fun importFolder(folderName: String, list: List<Pair<String, String>>) {
        if (list.isEmpty()) { toast("No finished videos found in that folder."); return }
        val sorted = list.sortedWith(compareBy(Library.natural) { it.second })
        askText("Found ${sorted.size} videos. Name this show:", Library.guessName(sorted.map { Library.cleanTitle(it.second) }, folderName), "Create show") { name ->
            val shelf = Library.shows().firstOrNull { it.name.equals(name, true) } ?: Library.createShelf(name, Library.KIND_SHOW)
            if (name.contains("bluey", true)) { shelf.emoji = "🐶"; shelf.color = 0xFF5B9DF9.toInt() }
            sorted.forEach { (u, n) -> val v = Library.addVideo(u, n); if (v.id !in shelf.videoIds) shelf.videoIds.add(v.id) }
            Library.save(); rebuild()
            toast("Added ${sorted.size} videos to ${shelf.name}")
        }
    }

    /** After adding videos, offer to file them into a show or playlist right away. */
    private fun afterAdd(vids: List<Video>) {
        if (vids.isEmpty()) return
        rebuild()
        val guess = Library.guessName(vids.map { it.title }, "My show")
        val options = mutableListOf("➕ New show \"$guess\"", "➕ New show with another name…")
        Library.shelves.forEach { options += "${it.emoji} ${it.name}" + if (it.isPlaylist) " (playlist)" else "" }
        options += "Just keep in All videos"
        pickOne("Put ${vids.size} video${if (vids.size == 1) "" else "s"} where?", options) { i ->
            fun into(s: Shelf) {
                vids.forEach { if (it.id !in s.videoIds) s.videoIds.add(it.id) }
                if (s.name.contains("bluey", true)) { s.emoji = "🐶"; s.color = 0xFF5B9DF9.toInt() }
                Library.save(); rebuild(); toast("Added to ${s.name}")
            }
            when {
                i == 0 -> into(Library.createShelf(guess, Library.KIND_SHOW))
                i == 1 -> askText("Show name", guess, "Create") { into(Library.createShelf(it, Library.KIND_SHOW)) }
                i - 2 < Library.shelves.size -> into(Library.shelves[i - 2])
                else -> toast("Added to All videos")
            }
        }
    }

    private fun displayName(u: Uri): String = queryName(u) ?: (u.lastPathSegment ?: "Video")

    private fun queryName(u: Uri): String? = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanPhone()
            else toast("Without permission, use \"Add a whole folder\" or \"Pick video files\" instead.")
        }
    }
}
