package com.avriella.tv.cam

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.avriella.tv.data.Library
import com.avriella.tv.sleep.Duck
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Walkie-talkie: plays a parent's voice message out loud on her phone. Her show or sleep sounds
 * dip down while the voice plays and fade back up afterwards. Works with the screen off too.
 */
object Talk {
    private val ui = Handler(Looper.getMainLooper())
    private val queue = ConcurrentLinkedQueue<File>()
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    @Volatile var playing = false
        private set

    private val ctx get() = Library.appCtx
    private val audio get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun play(bytes: ByteArray): Boolean {
        if (bytes.size < 100) return false
        val dir = File(ctx.cacheDir, "talk").apply { mkdirs() }
        val f = File(dir, "v" + System.nanoTime() + ".wav")
        f.writeBytes(bytes)
        queue += f
        ui.post { if (!playing) next() }
        return true
    }

    private fun next() {
        val f = queue.poll()
        if (f == null) { finish(); return }
        if (!playing) {
            playing = true
            CamState.talking = true
            Duck.to(0.12f, 250)
            // make sure the voice is loud enough to hear even if her show was turned way down
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val want = (max * 0.6f).toInt()
            if (cur < want) { savedVolume = cur; audio.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0) }
            Remote.handler?.let { h -> try { h(Remote.Cmd("talking")) } catch (_: Exception) {} }
        }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.path)
            mp.setOnCompletionListener { it.release(); f.delete(); player = null; ui.postDelayed({ next() }, 150) }
            mp.setOnErrorListener { p, _, _ -> p.release(); f.delete(); player = null; ui.post { next() }; true }
            mp.prepare()
            player = mp
            // let the show dip first
            ui.postDelayed({ try { mp.start() } catch (_: Exception) { next() } }, 280)
        } catch (e: Exception) {
            android.util.Log.w("AvriellaTalk", "couldn't play voice", e)
            f.delete(); ui.post { next() }
        }
    }

    private fun finish() {
        if (!playing) return
        playing = false
        CamState.talking = false
        if (savedVolume >= 0) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0); savedVolume = -1 }
        Duck.to(1f, 1400)
    }
}
