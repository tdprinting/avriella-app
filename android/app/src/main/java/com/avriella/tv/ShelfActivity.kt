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

        vids.forEachIndexed { i, v -> body.addView(row(i, v), lp(t = dp(8))) }
        body.addView(label("Tip: press and hold a video for more options.", 13f, Pal.SUB).apply { setPadding(dp(4), dp(14), 0, 0) })
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
        )
        if (shelf != null) items += "➖  Remove from ${shelf.name}" to { shelf.videoIds.remove(v.id); Library.save(); rebuild() }
        items += "🗑️  Remove from app (file stays on phone)" to {
            confirm("Remove video?", "\"${v.title}\" will be removed from the app. The file itself stays on the phone.", "Remove") {
                Library.deleteVideo(v.id); Thumbs.forget(v.id); Library.save(); rebuild()
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
