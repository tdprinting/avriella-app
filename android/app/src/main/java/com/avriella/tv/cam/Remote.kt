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
        return onMain { handler?.invoke(cmd) ?: "Her phone isn't playing a show or game right now." } ?: "Her phone didn't answer."
    }
}
