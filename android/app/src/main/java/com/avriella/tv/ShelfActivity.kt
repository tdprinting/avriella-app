package com.avriella.tv

import android.content.Intent
import android.view.View
import com.avriella.tv.data.Library
import com.avriella.tv.data.Thumbs
import com.avriella.tv.data.Video
import com.avriella.tv.kid.PlayerActivity
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.ProgressLine
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.art
import com.avriella.tv.ui.askText
import com.avriella.tv.ui.card
import com.avriella.tv.ui.confirm
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
import com.avriella.tv.ui.toast
import com.avriella.tv.ui.vbox
import com.avriella.tv.ui.DvdCase
import com.avriella.tv.ui.DvdOpen
import com.avriella.tv.ui.toyBackground

/** List of videos in a show / playlist / "All videos". Tap = play from there. Hold = options. */
class ShelfActivity : ParentActivity() {
    private val shelfId get() = intent.getStringExtra("shelf") ?: Library.ALL

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (shelfId != Library.ALL) addAction("✏️ Edit") {
            startActivity(Intent(this, EditShelfActivity::class.java).putExtra("shelf", shelfId))
        }
    }

    override fun build() {
        val shelf = Library.shelf(shelfId)
        if (shelfId != Library.ALL && shelf == null) { finish(); return }
        titleView.text = shelf?.let { "${it.emoji} ${it.name}" } ?: "🎞️ All videos"
        val vids = Library.videosIn(shelfId)

        if (vids.isEmpty()) {
            val c = card(20)
            c.addView(label("Nothing here yet", 18f, Pal.INK, true))
            c.addView(label("Tap ✏️ Edit to add videos.", 15f, Pal.SUB).apply { setPadding(0, dp(6), 0, 0) })
            body.addView(c, lp(t = dp(8)))
            return
        }

        val buttons = hbox()
        buttons.addView(pill("▶  Play", Pal.ORANGE) { PlayerActivity.start(this, shelfId) }, lp(0, WRAP, 1f, r = dp(6)))
        buttons.addView(pill("🔀  Shuffle", Pal.PURPLE) { PlayerActivity.start(this, shelfId, shuffle = true) }, lp(0, WRAP, 1f, l = dp(6)))
        body.addView(buttons, lp(t = dp(6)))
        val auto = if (Library.settings.autoplay) "Auto-play is on: when one ends the next starts${if (Library.settings.loop) ", then it starts over" else ""}." else "Auto-play is off (Manage → Settings)."
        body.addView(label(auto, 13f, Pal.SUB).apply { setPadding(dp(4), dp(10), dp(4), dp(6)) })

        // the 3D shelf: swipe through, the middle DVD comes forward; open it and send it to her phone or a TV
        val infoT = label("", 19f, Pal.INK, true, 2).apply { gravity = android.view.Gravity.CENTER }
        val infoS = label("", 13f, Pal.SUB).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(3), 0, 0) }
        var midCase: DvdCase? = null
        lateinit var flow: com.avriella.tv.ui.DvdShelf
        val showMid = { i: Int ->
            val v = vids[i]; midCase = flow.cases[i]; lastMid = v.id
            infoT.text = v.title
            val bits = mutableListOf<String>()
            if (v.durationMs > 0) bits += formatDuration(v.durationMs)
            if (v.progress > 0.01f) bits += "${(v.progress * 100).toInt()}% watched"
            infoS.text = bits.joinToString(" · ")
        }
        flow = com.avriella.tv.ui.DvdShelf(this, vids, { i -> showMid(i) }) { case, v -> pick(case, v) }
        body.addView(flow, lp(MATCH, WRAP, t = dp(4)))
        body.addView(infoT, lp(t = dp(6)))
        body.addView(infoS)
        body.addView(pill("📀  Open this DVD", Pal.ORANGE) { midCase?.let { pick(it, it.video) } }, lp(MATCH, WRAP, t = dp(12)))
        val start = vids.indexOfFirst { it.id == (lastMid ?: Library.settings.lastVideoId) }.coerceAtLeast(0)
        flow.showAt(start); showMid(start)

        body.addView(label("THE WHOLE SHELF", 12f, Pal.ORANGE, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(26), 0, 0) })
        // a shelf of DVDs, three across
        vids.chunked(3).forEachIndexed { r, chunk ->
            val line = hbox()
            chunk.forEachIndexed { k, v -> line.addView(dvd(r * 3 + k, v), lp(0, WRAP, 1f, l = if (k > 0) dp(6) else 0, r = if (k < 2) dp(6) else 0)) }
            repeat(3 - chunk.size) { line.addView(View(this), lp(0, 1, 1f)) }
            body.addView(line, lp(t = dp(14)))
            // the wooden shelf they stand on
            body.addView(View(this).apply { background = toyBackground(0xFFC98A4B.toInt(), dpf(6f), 0xFF8E5A2B.toInt(), dp(4)) }, lp(MATCH, dp(14), t = dp(2)))
        }
        body.addView(label("Tip: press and hold a DVD for more options.", 13f, Pal.SUB).apply { setPadding(dp(4), dp(14), 0, 0) })
    }

    private fun dvd(i: Int, v: Video): View {
        val col = vbox()
        val case = DvdCase(this, v, Pal.TILE[i % Pal.TILE.size])
        col.addView(case, lp(MATCH, WRAP))
        col.addView(label(v.title, 13f, Pal.INK, true, 2).apply { setPadding(dp(2), dp(6), dp(2), 0) })
        if (v.progress > 0.01f) col.addView(ProgressLine(this, v.progress), lp(MATCH, dp(4), t = dp(4)))
        col.setOnClickListener { pick(case, v) }
        col.setOnLongClickListener { options(v); true }
        case.isClickable = false
        return col
    }

    private var lastMid: String? = null
    /** Open the case; the disc goes to her phone or to a TV. */
    private fun pick(case: DvdCase, v: Video) {
        lastMid = v.id
        DvdOpen.choose(this, case) { PlayerActivity.start(this@ShelfActivity, shelfId, v.id) }
    }

    // when a newly found cover arrives, show it
    private val onCover: (String) -> Unit = { id -> if (Library.videosIn(shelfId).any { it.id == id }) rebuild() }

    override fun onResume() {
        super.onResume()
        DvdOpen.clear(this)
        com.avriella.tv.data.Covers.listeners += onCover
        com.avriella.tv.data.Covers.fetchMissing(this)
    }

    override fun onPause() {
        com.avriella.tv.data.Covers.listeners -= onCover
        super.onPause()
    }

    private fun row(i: Int, v: Video): View {
        val r = dpf(18f)
        val row = hbox().apply {
            background = pressable(rounded(Pal.CARD, r), r)
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setOnClickListener { PlayerActivity.start(this@ShelfActivity, shelfId, v.id) }
            setOnLongClickListener { options(v); true }
        }
        row.addView(art(Pal.TILE[i % Pal.TILE.size], "🎬", v, 0.58f, 12f, 24f), lp(dp(128), WRAP, r = dp(12)))
        val col = vbox()
        col.addView(label(v.title, 16f, Pal.INK, true, 2))
        val bits = mutableListOf<String>()
        if (v.durationMs > 0) bits += formatDuration(v.durationMs)
        if (v.progress > 0.01f) bits += "${(v.progress * 100).toInt()}% watched"
        if (bits.isNotEmpty()) col.addView(label(bits.joinToString(" · "), 13f, Pal.SUB))
        if (v.progress > 0.01f) col.addView(ProgressLine(this, v.progress), lp(MATCH, dp(5), t = dp(6)))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(label("▶", 20f, Pal.ORANGE, true), lp(WRAP, WRAP, l = dp(8)))
        return row
    }

    private fun options(v: Video) {
        val shelf = Library.shelf(shelfId)
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "▶  Play from here" to { PlayerActivity.start(this, shelfId, v.id) },
            "➕  Add to a show or playlist…" to { addTo(listOf(v)) },
            "✏️  Rename" to { askText("Rename video", v.title) { v.title = it; Library.save(); rebuild() } },
            "⏮  Start over next time" to { v.positionMs = 0; Library.save(); rebuild() },
            "📀  Find the DVD cover again" to { com.avriella.tv.data.Covers.fetch(this, v, force = true); toast("Looking for the cover…") },
        )
        if (shelf != null) items += "➖  Remove from ${shelf.name}" to { shelf.videoIds.remove(v.id); Library.save(); rebuild() }
        items += "🗑️  Remove from app (file stays on phone)" to {
            confirm("Remove video?", "\"${v.title}\" will be removed from the app. The file itself stays on the phone.", "Remove") {
                Library.deleteVideo(v.id); Thumbs.forget(v.id); com.avriella.tv.data.Covers.forget(this, v.id); Library.save(); rebuild()
            }
        }
        pickOne(v.title, items.map { it.first }) { which -> items[which].second() }
    }

    private fun addTo(vs: List<Video>) {
        val shelves = Library.shelves
        if (shelves.isEmpty()) { toast("Make a show or playlist first (Manage)."); return }
        val names = shelves.map { "${it.emoji} ${it.name}" + if (it.isPlaylist) "  (playlist)" else "" }
        pickMany("Add to…", names, BooleanArray(shelves.size), "Add") { picked ->
            picked.forEach { i -> vs.forEach { v -> if (v.id !in shelves[i].videoIds) shelves[i].videoIds.add(v.id) } }
            Library.save(); toast("Added")
        }
    }
}
