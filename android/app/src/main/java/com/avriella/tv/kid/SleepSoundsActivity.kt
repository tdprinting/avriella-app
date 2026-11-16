package com.avriella.tv.kid

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import com.avriella.tv.cam.CamState
import com.avriella.tv.cam.Remote
import com.avriella.tv.data.Library
import com.avriella.tv.sleep.SLEEP_SOUNDS
import com.avriella.tv.sleep.SleepScene
import com.avriella.tv.sleep.SoundscapeEngine
import com.avriella.tv.sleep.sleepSound
import com.avriella.tv.ui.MATCH
import com.avriella.tv.ui.Pal
import com.avriella.tv.ui.WRAP
import com.avriella.tv.ui.chips
import com.avriella.tv.ui.dp
import com.avriella.tv.ui.label
import com.avriella.tv.ui.lp
import com.avriella.tv.ui.pill

/** Bedtime mode: soothing sounds, slow dark pictures, very dim screen, everything locked. */
class SleepSoundsActivity : KidActivity() {
    companion object {
        fun start(ctx: Context, soundId: String?) =
            ctx.startActivity(Intent(ctx, SleepSoundsActivity::class.java).putExtra("sound", soundId))

        /** Turns any intent into a sleep-sounds switch from another kid screen (same locked task). */
        fun switchFrom(a: KidActivity, soundId: String?) { start(a, soundId); a.finish() }
    }

    private val engine by lazy { SoundscapeEngine(this) }
    private lateinit var scene: SleepScene
    private var soundId = "ocean"

    override fun initialTouchLock() = true
    override fun camMode() = "sounds"

    override fun createContent(): View {
        soundId = sleepSound(intent.getStringExtra("sound")).id
        scene = SleepScene(this)
        brightnessOverride = 0.04f
        applySound()
        return scene
    }

    private fun applySound() {
        val s = sleepSound(soundId)
        scene.theme = s.theme
        scene.label = "${s.emoji}  ${s.name}"
        CamState.nowPlaying = "Sleep sounds: ${s.name}"
        CamState.soundId = s.id
        CamState.playing = true
    }

    override fun onResume() {
        super.onResume()
        if (!sleeping) engine.start(soundId)
    }

    override fun onDestroy() {
        engine.stop()
        CamState.soundId = null
        super.onDestroy()
    }

    override fun onExitKidMode() { engine.stop() }

    // When the sleep timer ends: slowly fade the sound away instead of cutting it.
    override fun onSleep() { engine.fade(0f, 30f); CamState.playing = false }
    override fun onWake() { engine.start(soundId); engine.fade(1f, 3f); CamState.playing = true }

    private fun pick(id: String) {
        soundId = sleepSound(id).id
        engine.start(soundId)
        applySound()
    }

    override fun onRemote(c: Remote.Cmd): String = when (c.action) {
        "sounds" -> { if (sleeping) wake(); pick(c.value ?: soundId); if (panelOpen) rebuildPanel(); "ok" }
        "pause" -> { engine.pause(); CamState.playing = false; "ok" }
        "resume" -> { if (sleeping) wake() else engine.resume(); CamState.playing = true; "ok" }
        "play" -> if (c.videoId != null && Library.videos.containsKey(c.videoId)) {
            PlayerActivity.start(this, c.shelfId, c.videoId); finish(); "ok"
        } else "That video isn't on her phone any more."
        else -> "Not available during sleep sounds."
    }

    override fun buildPanelExtras(box: LinearLayout) {
        box.addView(label("Sleep sound", 13f, 0xFFB9B8D6.toInt(), true))
        val idx = SLEEP_SOUNDS.indexOfFirst { it.id == soundId }.coerceAtLeast(0)
        box.addView(chips(SLEEP_SOUNDS.map { "${it.emoji} ${it.name}" }, idx, dark = true) { pick(SLEEP_SOUNDS[it].id) }, lp(MATCH, WRAP, t = dp(6)))
        box.addView(pill(if (engine.isPlaying) "⏸  Pause sound" else "▶  Play sound", Pal.BLUE) {
            if (engine.isPlaying) { engine.pause(); CamState.playing = false } else { engine.resume(); CamState.playing = true }
            rebuildPanel()
        }, lp(MATCH, WRAP, t = dp(10)))
    }
}
