package com.avriella.tv.cam

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.avriella.tv.data.Library
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge between the iPhone (web server threads) and whatever screen is showing on her phone.
 * The screen in front registers a handler; commands run on the main thread.
 */
object Remote {
    class Cmd(val action: String, val videoId: String? = null, val shelfId: String? = null, val value: String? = null)

    /** Returns "ok" or a friendly error message. */
    @Volatile var handler: ((Cmd) -> String)? = null

    private val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and waits for the answer (null on timeout). */
    fun <T : Any> onMain(timeoutMs: Long = 3000, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val out = AtomicReference<T?>(null)
        val latch = CountDownLatch(1)
        main.post {
            try { out.set(block()) } catch (_: Exception) {} finally { latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out.get()
    }

    private val audio get() = Library.appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    fun volumeMax(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun send(cmd: Cmd): String {
        if (cmd.action == "volume") {
            // Volume works no matter which screen is showing.
            return onMain {
                val cur = volume(); val max = volumeMax()
                val v = when (cmd.value) { "up" -> cur + 1; "down" -> cur - 1; else -> cmd.value?.toIntOrNull() ?: cur }
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, max), 0)
                "ok"
            } ?: "Her phone didn't answer."
        }
        if (cmd.action == "level") {
            // Saved as a setting; the player (if showing) applies it right away.
            return onMain {
                Library.settings.levelVideo = cmd.value == "on" || cmd.value == "true" || cmd.value == "1"
                Library.save()
                handler?.invoke(cmd)
                "ok"
            } ?: "Her phone didn't answer."
        }
        if (cmd.action == "screenoff") return if (!Wake.screenOn) "ok" else Wake.screenOff()
        if (cmd.action == "wake") {
            // Turn the screen on, then also wake her up from the sleep screen if it's showing.
            if (!Wake.wakeAndWait()) return wakeHelp()
            onMain { if (Wake.kidScreenAlive()) handler?.invoke(cmd); true }
            return "ok"
        }
        if (!Wake.ready()) {
            // Her phone is asleep (or the app isn't in front): wake it up first, then do the command.
            android.util.Log.i("AvriellaRemote", "waking her phone for " + cmd.action)
            val launch = (cmd.action == "play" && cmd.videoId != null && Library.videos.containsKey(cmd.videoId)) || cmd.action == "sounds"
            if (launch && !Wake.kidScreenAlive()) {
                // Open the video / sounds straight away; kid screens turn the screen on by themselves.
                onMain {
                    Wake.wakeNow()
                    if (cmd.action == "play") com.avriella.tv.kid.PlayerActivity.start(Library.appCtx, cmd.shelfId, cmd.videoId)
                    else com.avriella.tv.kid.SleepSoundsActivity.start(Library.appCtx, cmd.value)
                    true
                }
                val until = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < until && !Wake.ready()) Thread.sleep(100)
                return if (Wake.ready()) "ok" else wakeHelp()
            }
            if (!Wake.wakeAndWait()) return wakeHelp()
        }
        return onMain { handler?.invoke(cmd) ?: "Her phone isn't playing a show or game right now." } ?: "Her phone didn't answer."
    }

    private fun wakeHelp() =
        if (!Wake.canLaunch()) "Her phone is asleep. To wake it from here, turn on \"Display over other apps\" on her phone: Manage → Baby cam."
        else "Her phone didn't wake up — try again in a moment."
}
