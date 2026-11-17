package com.avriella.tv.sleep

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Turns her show / sleep sounds down while a parent's voice plays, then smoothly back up.
 * Players multiply their own volume by [level] and listen for changes.
 */
object Duck {
    @Volatile var level = 1f
        private set
    private val listeners = CopyOnWriteArrayList<(Float) -> Unit>()
    private val ui = Handler(Looper.getMainLooper())
    private var target = 1f
    private var step = 0.05f

    fun listen(l: (Float) -> Unit) { listeners += l }
    fun unlisten(l: (Float) -> Unit) { listeners -= l }

    /** Fades everything to [to] (1 = normal) over [ms]. */
    fun to(to: Float, ms: Long) {
        ui.post {
            target = to.coerceIn(0f, 1f)
            step = (Math.abs(target - level) / (ms / 30f).coerceAtLeast(1f)).coerceAtLeast(0.005f)
            ui.removeCallbacks(tick); ui.post(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            level = if (level < target) (level + step).coerceAtMost(target) else (level - step).coerceAtLeast(target)
            listeners.forEach { try { it(level) } catch (_: Exception) {} }
            if (level != target) ui.postDelayed(this, 30)
        }
    }
}
