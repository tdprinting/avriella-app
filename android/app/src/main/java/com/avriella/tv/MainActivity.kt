package com.avriella.tv

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.avriella.tv.cam.CamState
import com.avriella.tv.data.Library
import com.avriella.tv.data.Shelf
import com.avriella.tv.games.GAMES
import com.avriella.tv.games.Sounds
import com.avriella.tv.kid.GameActivity
import com.avriella.tv.kid.KidLock
import com.avriella.tv.kid.PlayerActivity
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.ParentActivity
import com.avriella.tv.ui.ProgressLine
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.art
import com.avriella.tv.ui.card
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.formatDuration
import com.avriella.tv.ui.grid
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
import com.avriella.tv.ui.pressable
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.section
import com.avriella.tv.ui.vbox

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        Thread { Sounds.init(this) }.start()
    }
}

/** Parent home: one tap on any tile starts it, locked. */
class MainActivity : ParentActivity() {
    override val hasBack = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        addAction("⚙️ Manage") { startActivity(Intent(this, ManageActivity::class.java)) }
    }

    override fun onResume() {
        // If something ever crashed while locked, don't leave the parents stuck.
        KidLock.exit(this)
        super.onResume()
    }

    override fun build() {
        val s = Library.settings
        titleView.text = "🌈 ${s.childName}'s TV"
        body.addView(label("Tap ▶ on anything and it starts locked. To come back here, hold BOTH volume buttons for 1 second.", 14f, Pal.SUB).apply {
            setPadding(dp(4), 0, dp(4), dp(6))
        })

        if (Library.videos.isEmpty()) {
            val c = card(22)
            c.addView(label("No videos yet", 20f, Pal.INK, true))
            c.addView(label("Copy her shows onto this phone (for example into a \"Bluey\" folder in Downloads), then add them here.", 15f, Pal.SUB).apply { setPadding(0, dp(6), 0, dp(14)) })
            c.addView(pill("➕  Add videos", Pal.BLUE) { startActivity(Intent(this, ManageActivity::class.java)) }, lp(WRAP, WRAP))
            body.addView(c, lp(t = dp(12)))
        } else {
            continueCard()?.let { body.addView(it, lp(t = dp(12))) }

            val shows = Library.shows()
            body.addView(section("📺  Shows"))
            if (shows.isEmpty()) body.addView(hintCard("Make a show (like \"Bluey\") in Manage to sort her videos."))
            else body.addView(grid(shows.map { shelfTile(it) }))

            val pls = Library.playlists()
            body.addView(section("🎵  Playlists"))
            if (pls.isEmpty()) body.addView(hintCard("Playlists are your own mixes that auto-play in order. Make one in Manage → New playlist."))
            else body.addView(grid(pls.map { shelfTile(it) }))

            body.addView(pill("🎞️  All videos (${Library.videos.size})", Pal.CARD, Pal.INK) {
                startActivity(Intent(this, ShelfActivity::class.java).putExtra("shelf", Library.ALL))
            }, lp(MATCH, WRAP, t = dp(14)))
        }

        body.addView(section("🎮  Baby games"))
        body.addView(grid(GAMES.map { g ->
            tile(g.name, g.blurb, g.color, g.emoji, null, onOpen = { GameActivity.start(this, g.id) }, onPlay = { GameActivity.start(this, g.id) })
        }))

        body.addView(section("🛡️  Safety"))
        val full = KidLock.isFullLock(this)
        body.addView(statusRow(
            if (full) "🔒  Full lock is ON" else "⚠️  Basic lock (tap to set up Full Lock)",
            if (full) Pal.SOFT_GREEN else Pal.SOFT_ORANGE,
        ) { startActivity(Intent(this, LockSetupActivity::class.java)) }, lp(t = dp(4)))
        val camText = when {
            !s.camEnabled -> "📷  Baby cam is off (tap to set up iPhone viewing)"
            CamState.running -> "📷  Baby cam is ON right now"
            else -> "📷  Baby cam turns on while she watches"
        }
        body.addView(statusRow(camText, if (s.camEnabled) Pal.SOFT_BLUE else Pal.CARD) {
            startActivity(Intent(this, CamSetupActivity::class.java))
        }, lp(t = dp(8)))
    }

    private fun hintCard(t: String) = card(16).apply { addView(label(t, 14f, Pal.SUB)) }

    private fun statusRow(t: String, bg: Int, onClick: () -> Unit) = label(t, 15f, Pal.INK, true).apply {
        val r = dpf(18f)
        background = pressable(rounded(bg, r, dp(1), Pal.LINE), r)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

    private fun continueCard(): View? {
        val v = Library.settings.lastVideoId?.let { Library.videos[it] } ?: return null
        val shelfId = Library.settings.lastShelfId ?: Library.ALL
        val c = card(14)
        c.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF8B5CF6.toInt(), 0xFFF2557A.toInt(), 0xFFFF9A3C.toInt()),
        ).apply { cornerRadius = dpf(26f) }
        c.elevation = dpf(6f)
        val row = hbox()
        val a = art(Pal.ORANGE, "▶", v, 0.6f, 14f, 28f)
        row.addView(a, lp(dp(150), WRAP, r = dp(14)))
        val col = vbox()
        col.addView(label("✨ KEEP WATCHING", 12f, 0xFFFFF3C4.toInt(), true))
        col.addView(label(v.title, 17f, Color.WHITE, true, 2))
        col.addView(label("from ${Library.shelfName(shelfId)}", 13f, 0xE6FFFFFF.toInt(), lines = 1))
        col.addView(ProgressLine(this, v.progress, 0xFFFFE14D.toInt(), 0x44FFFFFF), lp(MATCH, dp(6), t = dp(8)))
        row.addView(col, lp(0, WRAP, 1f))
        c.addView(row)
        c.addView(pill("▶  Play for ${Library.settings.childName}", 0x40FFFFFF, android.graphics.Color.WHITE, 18f) { PlayerActivity.start(this, shelfId, v.id) }, lp(MATCH, WRAP, t = dp(12)))
        return c
    }

    private fun shelfTile(s: Shelf): View {
        val vids = Library.videosIn(s.id)
        val total = vids.sumOf { it.durationMs }
        val sub = "${vids.size} video${if (vids.size == 1) "" else "s"}" + (if (total > 0) " · ${formatDuration(total)}" else "")
        return tile(s.name, sub, s.color, s.emoji, vids.firstOrNull(),
            onOpen = { startActivity(Intent(this, ShelfActivity::class.java).putExtra("shelf", s.id)) },
            onPlay = if (vids.isEmpty()) null else ({ PlayerActivity.start(this, s.id) }))
    }

    private fun tile(title: String, sub: String, color: Int, emoji: String, thumb: com.avriella.tv.data.Video?, onOpen: () -> Unit, onPlay: (() -> Unit)?): View {
        val c = vbox()
        val r = dpf(20f)
        c.background = pressable(rounded(Pal.CARD, r, dp(1.5f), Pal.EDGE), r)
        c.setPadding(dp(8), dp(8), dp(8), dp(12))
        c.setOnClickListener { onOpen() }
        val a = art(color, emoji, thumb, 0.62f, 16f, 44f)
        if (onPlay != null) {
            val play = label("▶", 20f, Pal.INK, true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.WHITE, dpf(24f))
                elevation = dpf(3f)
                setOnClickListener { onPlay() }
            }
            a.addView(play, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
        }
        c.addView(a, lp(MATCH, WRAP))
        c.addView(label(title, 16f, Pal.INK, true, 2).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        c.addView(label(sub, 13f, Pal.SUB, lines = 2).apply { setPadding(dp(4), dp(2), dp(4), 0) })
        return c
    }
}
