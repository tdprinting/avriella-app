package com.avriella.tv

import android.view.Gravity
import android.view.View
import com.avriella.tv.data.Library
import com.avriella.tv.data.Shelf
import com.avriella.tv.kid.PlayerActivity
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.art
import com.avriella.tv.ui.askText
import com.avriella.tv.ui.card
import com.avriella.tv.ui.confirm
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.outlinePill
import com.avriella.tv.ui.pickMany
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.section
import com.avriella.tv.ui.toast
import com.avriella.tv.ui.vbox
import android.widget.HorizontalScrollView

/** Edit a show or playlist: name, look, which videos and their order. */
class EditShelfActivity : ParentActivity() {
    private val emojis = listOf("📺", "🐶", "🐾", "🎵", "🌈", "⭐", "🌙", "🦄", "🐻", "🌻", "🎨", "🚗", "🐠", "🎈", "❤️", "🦕")

    private val shelf: Shelf? get() = Library.shelf(intent.getStringExtra("shelf"))

    override fun build() {
        val s = shelf ?: run { finish(); return }
        titleView.text = if (s.isPlaylist) "Edit playlist" else "Edit show"

        val head = card(16)
        val top = hbox()
        top.addView(art(s.color, s.emoji, null, 1f, 18f, 34f), lp(dp(72), WRAP, r = dp(14)))
        val col = vbox()
        col.addView(label(s.name, 20f, Pal.INK, true, 2))
        col.addView(label(if (s.isPlaylist) "Playlist · plays in this order" else "Show", 13f, Pal.SUB))
        top.addView(col, lp(0, WRAP, 1f))
        top.addView(outlinePill("Rename") { askText("Name", s.name) { s.name = it; Library.save(); rebuild() } })
        head.addView(top)

        head.addView(label("Picture", 13f, Pal.SUB, true).apply { setPadding(0, dp(14), 0, dp(6)) })
        val er = hbox()
        emojis.forEach { e ->
            er.addView(label(e, 26f).apply {
                gravity = Gravity.CENTER
                background = rounded(if (e == s.emoji) Pal.SOFT_BLUE else 0, dpf(14f), if (e == s.emoji) dp(2) else 0, Pal.BLUE)
                setOnClickListener { s.emoji = e; Library.save(); rebuild() }
            }, lp(dp(50), dp(50), r = dp(4)))
        }
        head.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(er) })
        head.addView(label("Color", 13f, Pal.SUB, true).apply { setPadding(0, dp(12), 0, dp(6)) })
        val cr = hbox()
        Pal.TILE.forEach { c ->
            cr.addView(View(this).apply {
                background = rounded(c, dpf(20f), if (c == s.color) dp(4) else 0, Pal.INK)
                setOnClickListener { s.color = c; Library.save(); rebuild() }
            }, lp(dp(40), dp(40), r = dp(8)))
        }
        head.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(cr) })
        body.addView(head, lp(t = dp(6)))

        body.addView(section("Videos (${s.videoIds.size})"))
        val actions = hbox()
        actions.addView(pill("＋  Add videos", Pal.BLUE) { addVideos(s) }, lp(0, WRAP, 1f, r = dp(6)))
        actions.addView(pill("▶  Play", Pal.ORANGE) {
            if (s.videoIds.isEmpty()) toast("Add some videos first") else PlayerActivity.start(this, s.id)
        }, lp(0, WRAP, 1f, l = dp(6)))
        body.addView(actions)

        if (s.videoIds.size > 1) body.addView(pill("🔤  Sort A → Z", Pal.CARD, Pal.INK) {
            s.videoIds.sortWith(compareBy(Library.natural) { Library.videos[it]?.title ?: "" }); Library.save(); rebuild()
        }, lp(MATCH, WRAP, t = dp(8)))

        val vids = s.videoIds.mapNotNull { Library.videos[it] }
        vids.forEachIndexed { i, v ->
            val row = hbox().apply {
                background = rounded(Pal.CARD, dpf(16f))
                setPadding(dp(8), dp(6), dp(6), dp(6))
            }
            row.addView(label("${i + 1}", 15f, Pal.SUB, true).apply { gravity = Gravity.CENTER }, lp(dp(28), WRAP))
            row.addView(art(s.color, "🎬", v, 0.58f, 10f, 18f), lp(dp(84), WRAP, r = dp(10)))
            row.addView(label(v.title, 15f, Pal.INK, true, 2), lp(0, WRAP, 1f))
            row.addView(small("▲", i > 0) { move(s, i, -1) })
            row.addView(small("▼", i < vids.size - 1) { move(s, i, 1) })
            row.addView(small("✕", true) { s.videoIds.remove(v.id); Library.save(); rebuild() })
            body.addView(row, lp(t = dp(6)))
        }

        body.addView(pill("🗑️  Delete this ${if (s.isPlaylist) "playlist" else "show"}", Pal.CARD, Pal.RED) {
            confirm("Delete \"${s.name}\"?", "The videos stay in All videos and on the phone.", "Delete") {
                Library.deleteShelf(s.id); Library.save(); finish()
            }
        }, lp(MATCH, WRAP, t = dp(24)))
    }

    private fun small(t: String, enabled: Boolean, onClick: () -> Unit) = label(t, 16f, if (enabled) Pal.INK else Pal.LINE, true).apply {
        gravity = Gravity.CENTER
        if (enabled) setOnClickListener { onClick() }
        layoutParams = lp(dp(40), dp(44))
    }

    private fun move(s: Shelf, i: Int, d: Int) {
        val j = i + d
        if (j !in s.videoIds.indices) return
        val t = s.videoIds[i]; s.videoIds[i] = s.videoIds[j]; s.videoIds[j] = t
        Library.save(); rebuild()
    }

    private fun addVideos(s: Shelf) {
        val all = Library.videosIn(Library.ALL).filter { it.id !in s.videoIds }
        if (all.isEmpty()) { toast("All videos are already here. Add more in Manage → Add videos."); return }
        pickMany("Add videos", all.map { it.title }, BooleanArray(all.size), "Add") { picked ->
            picked.forEach { s.videoIds.add(all[it].id) }
            Library.save(); rebuild()
        }
    }
}
