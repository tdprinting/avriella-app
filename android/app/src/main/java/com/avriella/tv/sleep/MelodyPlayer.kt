package com.avriella.tv.sleep

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Plays one of the recorded sleep melodies on an endless loop.
 *
 * The files in res/raw were prepared as seamless loops (fades trimmed, the ending blended into the
 * start, volumes matched) and saved as OGG, which Android loops without a gap.
 */
class MelodyPlayer(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var rawId = 0
    private var volume = 0f
    private var target = 0f
    private var step = 0.05f
    private val tickMs = 50L

    val isPlaying get() = player != null && target > 0f

    /** Starts (or keeps playing) a melody, fading it in. */
    fun play(raw: Int, fadeSec: Float = 1.5f) {
        if (raw != rawId || player == null) {
            release()
            val mp = MediaPlayer.create(ctx, raw, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(), 0) ?: return
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            volume = 0f
            player = mp
            rawId = raw
        }
        player?.let { if (!it.isPlaying) it.start() }
        fade(1f, fadeSec)
    }

    /** Smoothly changes volume; reaching 0 pauses the player (so it costs nothing while silent). */
    fun fade(to: Float, seconds: Float) {
        target = to.coerceIn(0f, 1f)
        step = (tickMs / 1000f) / seconds.coerceAtLeast(0.05f)
        if (target > 0f) player?.let { if (!it.isPlaying) it.start() }
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    fun pause() = fade(0f, 1.5f)
    fun resume() = fade(1f, 1.5f)

    fun stop() {
        ui.removeCallbacks(tick)
        release()
    }

    private fun release() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        rawId = 0
        volume = 0f
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            volume = if (volume < target) (volume + step).coerceAtMost(target) else (volume - step).coerceAtLeast(target)
            // squared curve sounds like an even fade to the ear
            val v = volume * volume
            try { p.setVolume(v, v) } catch (_: Exception) {}
            if (volume == target) {
                if (target == 0f) try { if (p.isPlaying) p.pause() } catch (_: Exception) {}
                return
            }
            ui.postDelayed(this, tickMs)
        }
    }
}
