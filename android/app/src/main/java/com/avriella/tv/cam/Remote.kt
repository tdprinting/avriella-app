package com.avriella.tv.cam

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge between the iPhone (web server threads) and whatever screen is showing on her phone.
 * The screen in front registers a handler; commands run on the main thread.
 */
object Remote {
    class Cmd(val action: String, val videoId: String? = null, val shelfId: String? = null)

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

    fun send(cmd: Cmd): String =
        onMain { handler?.invoke(cmd) ?: "Her phone isn't playing a show or game right now." } ?: "Her phone didn't answer."
}
