package com.avriella.tv.kid

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.avriella.tv.cam.CamState
import com.avriella.tv.cam.Remote
import com.avriella.tv.data.Library
import com.avriella.tv.games.GAMES
import com.avriella.tv.games.GameView
import com.avriella.tv.games.Sounds
import com.avriella.tv.games.makeGame
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.chips
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill

/** Hosts the baby games. Touch is allowed inside the game, but there are no buttons to get out. */
class GameActivity : KidActivity() {
    companion object {
        fun start(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, GameActivity::class.java).putExtra("game", id))
    }

    private lateinit var holder: FrameLayout
    private var game: GameView? = null
    private var gameId = "paint"

    /** From the iPhone: picking a video switches her from the game to that show (still locked). */
    override fun onRemote(c: Remote.Cmd): String =
        if (c.action == "play" && c.videoId != null && Library.videos.containsKey(c.videoId)) {
            PlayerActivity.start(this, c.shelfId, c.videoId)
            finish()
            "ok"
        } else if (c.action == "play") "That video isn't on her phone any more."
        else "She's playing a game right now. Pick a video to switch to a show."

    override fun initialTouchLock() = false
    override fun camMode() = "playing"

    override fun createContent(): View {
        Sounds.init(applicationContext)
        holder = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        gameId = intent.getStringExtra("game") ?: "paint"
        show(gameId)
        return holder
    }

    private fun show(id: String) {
        gameId = id
        holder.removeAllViews()
        val g = makeGame(this, id)
        game = g
        holder.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))
        CamState.nowPlaying = "Game: " + (GAMES.firstOrNull { it.id == id }?.name ?: id)
    }

    override fun buildPanelExtras(box: LinearLayout) {
        box.addView(label("Switch game", 13f, 0xFFB9B8D6.toInt(), true))
        val idx = GAMES.indexOfFirst { it.id == gameId }.coerceAtLeast(0)
        box.addView(chips(GAMES.map { "${it.emoji} ${it.name}" }, idx, dark = true) {
            show(GAMES[it].id); rebuildPanel()
        }, lp(MATCH, WRAP, t = dp(6)))
        if (game?.canClear == true) {
            box.addView(pill("🧽  New blank page", Pal.BLUE) { game?.clear(); closePanel() }, lp(MATCH, WRAP, t = dp(12)))
        }
    }
}
