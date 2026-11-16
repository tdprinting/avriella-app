package com.avriella.tv.kid

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.avriella.tv.cam.CamState
import com.avriella.tv.data.Library
import com.avriella.tv.data.Video
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.formatTime
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.rounded
import com.avriella.tv.ui.toast

/** Full-screen, touch-locked video player that auto-plays through a show or playlist. */
class PlayerActivity : KidActivity() {
    companion object {
        fun start(ctx: Context, shelfId: String?, videoId: String? = null, shuffle: Boolean = false) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra("shelf", shelfId ?: Library.ALL)
                    .putExtra("video", videoId)
                    .putExtra("shuffle", shuffle)
            )
        }
    }

    private lateinit var video: VideoView
    private var shelfId: String = Library.ALL
    private var queue: List<Video> = emptyList()
    private var index = 0
    private var pendingSeek = 0L
    private var resumeAt = 0L
    private var errorsInRow = 0
    private var seeking = false
    private var seekBar: SeekBar? = null
    private var timeLabel: TextView? = null
    private var playBtn: TextView? = null

    private val saver = object : Runnable {
        override fun run() { savePosition(); ui.postDelayed(this, 5000) }
    }

    override fun initialTouchLock() = true
    override fun camMode() = "watching"

    override fun createContent(): View {
        val f = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        f.addView(video, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        return f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shelfId = intent.getStringExtra("shelf") ?: Library.ALL
        queue = Library.videosIn(shelfId).let { if (intent.getBooleanExtra("shuffle", false)) it.shuffled() else it }
        if (queue.isEmpty()) { toast("There are no videos here yet."); exitKidMode(); return }
        val wanted = intent.getStringExtra("video")
        index = queue.indexOfFirst { it.id == wanted }.takeIf { it >= 0 } ?: Library.resumeIndex(queue)

        video.setOnPreparedListener { mp ->
            errorsInRow = 0
            if (pendingSeek > 0) { mp.seekTo(pendingSeek.toInt()); pendingSeek = 0 }
            val cur = queue.getOrNull(index)
            if (cur != null && mp.duration > 0 && cur.durationMs.toInt() != mp.duration) { cur.durationMs = mp.duration.toLong() }
            if (!sleeping) video.start()
        }
        video.setOnCompletionListener {
            queue.getOrNull(index)?.let { it.positionMs = 0 }
            Library.save()
            next(auto = true)
        }
        video.setOnErrorListener { _, _, _ ->
            errorsInRow++
            if (errorsInRow >= queue.size) {
                toast("These videos can't be opened. Re-add them in Manage.")
                goToSleep("Uh oh!")
            } else ui.postDelayed({ next(auto = true) }, 800)
            true
        }
        play(index)
        ui.postDelayed(saver, 5000)
    }

    private fun play(i: Int, fromStart: Boolean = false) {
        if (queue.isEmpty()) return
        index = (i + queue.size) % queue.size
        val v = queue[index]
        val nearEnd = v.durationMs > 0 && v.positionMs > v.durationMs - 30_000
        pendingSeek = if (!fromStart && Library.settings.resume && !nearEnd) v.positionMs else 0
        v.lastPlayed = System.currentTimeMillis()
        Library.settings.lastVideoId = v.id
        Library.settings.lastShelfId = shelfId
        Library.save()
        CamState.nowPlaying = v.title
        video.setVideoURI(Uri.parse(v.uri))
    }

    private fun next(auto: Boolean = false) {
        if (auto && !Library.settings.autoplay) { goToSleep("All done!"); return }
        if (index + 1 >= queue.size) {
            if (auto && !Library.settings.loop) { goToSleep("All done!"); return }
            play(0, fromStart = true)
        } else play(index + 1, fromStart = auto)
    }

    private fun savePosition() {
        val v = queue.getOrNull(index) ?: return
        if (video.isPlaying || video.currentPosition > 0) {
            v.positionMs = video.currentPosition.toLong()
            Library.save()
        }
    }

    override fun onPause() {
        super.onPause()
        if (queue.isNotEmpty()) {
            savePosition()
            resumeAt = video.currentPosition.toLong()
            video.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (queue.isNotEmpty() && resumeAt > 0) {
            video.seekTo(resumeAt.toInt())
            resumeAt = 0
            if (!sleeping) video.start()
        }
    }

    override fun onSleep() { savePosition(); video.pause() }
    override fun onWake() {
        if (!video.isPlaying) {
            // after "All done!" start over; otherwise just continue
            if (video.currentPosition <= 0 && queue.isNotEmpty()) play(index) else video.start()
        }
    }

    override fun onExitKidMode() {
        ui.removeCallbacks(saver)
        if (queue.isNotEmpty()) savePosition()
        video.stopPlayback()
    }

    // ---------------- parent panel bits ----------------
    override fun buildPanelExtras(box: LinearLayout) {
        val v = queue.getOrNull(index) ?: return
        box.addView(label("Now playing", 13f, 0xFFB9B8D6.toInt(), true))
        box.addView(label(v.title, 18f, Color.WHITE, true, 2))

        val dur = video.duration.coerceAtLeast(0)
        val sb = SeekBar(this).apply {
            max = if (dur > 0) dur else 1
            progress = video.currentPosition
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) timeLabel?.text = timeText(p) }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar) { seeking = false; video.seekTo(s.progress) }
            })
        }
        seekBar = sb
        box.addView(sb, lp(MATCH, WRAP, t = dp(8)))
        timeLabel = label(timeText(video.currentPosition), 13f, 0xFFB9B8D6.toInt()).apply { gravity = Gravity.END }
        box.addView(timeLabel, lp())

        val ctr = hbox().apply { gravity = Gravity.CENTER }
        ctr.addView(roundBtn("⏮", 20f) { play(index - 1, fromStart = true); ui.postDelayed({ rebuildPanel() }, 400) })
        ctr.addView(roundBtn("−30", 15f) { video.seekTo((video.currentPosition - 30_000).coerceAtLeast(0)) })
        playBtn = roundBtn(if (video.isPlaying) "⏸" else "▶", 22f) {
            if (video.isPlaying) video.pause() else video.start()
            playBtn?.text = if (video.isPlaying) "⏸" else "▶"
        }
        ctr.addView(playBtn)
        ctr.addView(roundBtn("+30", 15f) { video.seekTo(video.currentPosition + 30_000) })
        ctr.addView(roundBtn("⏭", 20f) { next(); ui.postDelayed({ rebuildPanel() }, 400) })
        box.addView(ctr, lp(MATCH, WRAP, t = dp(6)))

        if (queue.size > 1) {
            box.addView(sectionLabel("Up next in ${Library.shelfName(shelfId)}  •  autoplay ${if (Library.settings.autoplay) "on" else "off"}"))
            val row = hbox()
            queue.forEachIndexed { i, q ->
                val cur = i == index
                row.addView(label("${i + 1}. ${q.title}", 14f, Color.WHITE, cur, 2).apply {
                    background = rounded(if (cur) Pal.ORANGE else 0x22FFFFFF, dpf(14f))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    maxWidth = dp(220)
                    setOnClickListener { play(i, fromStart = false); ui.postDelayed({ rebuildPanel() }, 400) }
                }, lp(WRAP, WRAP, r = dp(8)))
            }
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp())
        }
    }

    private fun timeText(p: Int) = "${formatTime(p.toLong())} / ${formatTime(video.duration.toLong().coerceAtLeast(0))}"

    override fun onPanelTick() {
        val sb = seekBar ?: return
        if (seeking) return
        val d = video.duration
        if (d > 0 && sb.max != d) sb.max = d
        sb.progress = video.currentPosition
        timeLabel?.text = timeText(video.currentPosition)
        playBtn?.text = if (video.isPlaying) "⏸" else "▶"
    }
}
