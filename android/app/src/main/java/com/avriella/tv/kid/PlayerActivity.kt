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
import com.avriella.tv.cam.CamState
import com.avriella.tv.cam.Remote
import com.avriella.tv.data.Library
import com.avriella.tv.data.Video
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.*
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.dpf
import com.avriella.tv.ui.formatTime
import com.avriella.tv.ui.hbox
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill
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
                    .apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    private lateinit var video: LevelVideoView
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
    /** Keeps the iPhone's "Now playing" card up to date. */
    private val stateTick = object : Runnable {
        override fun run() {
            val v = queue.getOrNull(index)
            CamState.videoId = v?.id
            CamState.shelfId = shelfId
            CamState.posMs = video.currentPosition.toLong().coerceAtLeast(0)
            CamState.durMs = video.duration.toLong().coerceAtLeast(v?.durationMs ?: 0)
            CamState.playing = video.isPlaying
            ui.postDelayed(this, 1000)
        }
    }
    private var skipWakeResume = false
    override fun onRemote(c: Remote.Cmd): String = handleRemote(c)

    /** Commands from the parents' iPhones. */
    private fun handleRemote(c: Remote.Cmd): String {
        if (queue.isEmpty()) return "Nothing is loaded yet."
        when (c.action) {
            "play" -> {
                val v = c.videoId?.let { Library.videos[it] } ?: return "That video isn't on her phone any more."
                val sid = c.shelfId?.takeIf { it == Library.ALL || Library.shelf(it) != null } ?: shelfId
                var q = Library.videosIn(sid)
                var newShelf = sid
                if (q.none { it.id == v.id }) { q = Library.videosIn(Library.ALL); newShelf = Library.ALL }
                shelfId = newShelf
                queue = q
                errorsInRow = 0
                if (sleeping) { skipWakeResume = true; wake(); skipWakeResume = false }
                play(q.indexOfFirst { it.id == v.id })
                if (panelOpen) rebuildPanel()
            }
            "level" -> { applyLevel(); if (panelOpen) rebuildPanel(); return "ok" }
            "sounds" -> { savePosition(); SleepSoundsActivity.switchFrom(this, c.value); return "ok" }
            "seek" -> {
                // Jump to a spot from the iPhone's progress bar (milliseconds).
                val ms = c.value?.toLongOrNull() ?: return "Unknown position"
                val d = video.duration.toLong()
                video.seekTo((if (d > 0) ms.coerceIn(0, d - 1000) else ms.coerceAtLeast(0)).toInt())
                if (sleeping) { skipWakeResume = true; wake(); skipWakeResume = false; video.start() }
            }
            "pause" -> { video.pause(); savePosition() }
            "resume" -> if (sleeping) wake() else video.start()
            "next" -> { if (sleeping) { skipWakeResume = true; wake(); skipWakeResume = false }; next() }
            "prev" -> { if (sleeping) { skipWakeResume = true; wake(); skipWakeResume = false }; play(index - 1, fromStart = true) }
            else -> return "Unknown command"
        }
        ui.removeCallbacks(stateTick); stateTick.run()
        return "ok"
    }

    override fun initialTouchLock() = true
    override fun camMode() = "watching"

    override fun createContent(): View {
        val f = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = LevelVideoView(this)
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
            if (stopAfterCurrent) { stopAfterCurrent = false; goToSleep("Night night"); return@setOnCompletionListener }
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
        applyLevel()
        play(index)
        ui.postDelayed(saver, 5000)
        ui.post(stateTick)
    }

    override fun onDestroy() {
        CamState.videoId = null; CamState.playing = false
        super.onDestroy()
    }

    /** Keep-level on: freeze the screen's orientation and let the picture turn instead. */
    private fun applyLevel() {
        val on = Library.settings.levelVideo
        // LOCKED would freeze whichever way the phone faced when the show started (often portrait from the
        // menu), leaving a little turning picture. Always lock wide instead; the picture still turns to stay level.
        requestedOrientation = if (on) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        video.keepLevel = on
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
        if (skipWakeResume) return
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
        box.addView(label("NOW PLAYING", 12f, Pal.ORANGE, true).apply { letterSpacing = 0.08f })
        box.addView(label(v.title, 19f, Pal.INK, true, 2).apply { typeface = CHUNKY })

        val dur = video.duration.coerceAtLeast(0)
        val sb = SeekBar(this).apply {
            max = if (dur > 0) dur else 1
            progress = video.currentPosition
            progressTintList = android.content.res.ColorStateList.valueOf(Pal.ORANGE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Pal.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) timeLabel?.text = timeText(p) }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar) { seeking = false; video.seekTo(s.progress) }
            })
        }
        seekBar = sb
        box.addView(sb, lp(MATCH, WRAP, t = dp(8)))
        timeLabel = label(timeText(video.currentPosition), 13f, Pal.SUB).apply { gravity = Gravity.END }
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
        val lv = Library.settings.levelVideo
        box.addView(pill(if (lv) "🧭  Keep level: ON" else "🧭  Keep level: off", if (lv) Pal.GREEN else Pal.SOFT_BLUE, if (lv) Color.WHITE else Pal.INK) {
            Library.settings.levelVideo = !Library.settings.levelVideo; Library.save(); applyLevel(); rebuildPanel()
        }, lp(MATCH, WRAP, t = dp(10)))

        if (queue.size > 1) {
            box.addView(sectionLabel("Up next in ${Library.shelfName(shelfId)}  •  autoplay ${if (Library.settings.autoplay) "on" else "off"}"))
            val row = hbox()
            queue.forEachIndexed { i, q ->
                val cur = i == index
                row.addView(label("${i + 1}. ${q.title}", 14f, if (cur) Color.WHITE else Pal.INK, cur, 2).apply {
                    background = rounded(if (cur) Pal.ORANGE else Pal.SOFT_BLUE, dpf(14f))
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
