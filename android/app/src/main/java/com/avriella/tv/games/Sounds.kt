package com.avriella.tv.games

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** All game sounds are synthesized at startup – soft bells, pops and boings. No sound files needed. */
object Sounds {
    private const val SR = 22050
    @Volatile private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()

    // C major pentatonic: any combination sounds nice.
    private val NOTES = doubleArrayOf(523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1174.66, 1318.51)
    val noteCount get() = NOTES.size

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        val dir = File(ctx.cacheDir, "snd").apply { mkdirs() }
        fun add(name: String, s: FloatArray) {
            val f = File(dir, "$name.wav")
            writeWav(f, s)
            ids[name] = p.load(f.path, 1)
        }
        NOTES.forEachIndexed { i, f -> add("n$i", bell(f, 1.0)) }
        add("pop", synthPop()); add("boing", synthBoing()); add("giggle", synthGiggle())
        add("whoosh", synthWhoosh()); add("chime", synthChime())
        pool = p
    }

    fun play(name: String, vol: Float = 0.8f, rate: Float = 1f) {
        val p = pool ?: return
        val id = ids[name] ?: return
        p.play(id, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun note(i: Int, vol: Float = 0.7f) = play("n${((i % NOTES.size) + NOTES.size) % NOTES.size}", vol)
    fun pop() = play("pop", 0.9f, 0.8f + Random.nextFloat() * 0.5f)

    // ---------------- synthesis ----------------
    private fun bell(f: Double, dur: Double): FloatArray {
        val n = (SR * dur).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / SR
            val env = min(1.0, t / 0.005) * exp(-t * 4.0)
            val s = sin(2 * PI * f * t) * 0.7 +
                sin(2 * PI * 2 * f * t) * 0.2 * exp(-t * 8) +
                sin(2 * PI * 3 * f * t) * 0.08 * exp(-t * 12)
            (s * env * 0.75).toFloat()
        }
    }

    private fun sweep(dur: Double, freq: (Double) -> Double, env: (Double) -> Double): FloatArray {
        val n = (SR * dur).toInt()
        var ph = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / SR
            ph += 2 * PI * freq(t) / SR
            (sin(ph) * env(t)).toFloat()
        }
    }

    private fun synthPop() = sweep(0.13, { t -> 900 * exp(-t * 28) + 170 }, { t -> min(1.0, t / 0.002) * exp(-t * 32) * 0.9 })

    private fun synthBoing() = sweep(0.5, { t -> 220 + 420 * (t / 0.5) + 35 * sin(2 * PI * 11 * t) },
        { t -> min(1.0, t / 0.01) * exp(-t * 4.5) * 0.8 })

    private fun synthGiggle(): FloatArray {
        val out = FloatArray((SR * 0.55).toInt())
        for (k in 0 until 5) {
            val start = (k * 0.1 * SR).toInt()
            val base = 720.0 + k * 90
            val c = sweep(0.075, { t -> base + 2600 * t }, { t -> sin(PI * t / 0.075) * 0.6 })
            for (i in c.indices) if (start + i < out.size) out[start + i] += c[i]
        }
        return out
    }

    private fun synthWhoosh(): FloatArray {
        val n = (SR * 0.55).toInt()
        var lp = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / n
            val a = 0.04 + 0.25 * sin(PI * t)
            lp += a * ((Random.nextDouble() * 2 - 1) - lp)
            (lp * sin(PI * t) * 1.4).toFloat()
        }
    }

    private fun synthChime(): FloatArray {
        val a = bell(1318.51, 1.0); val b = bell(1760.0, 0.9)
        val off = (0.09 * SR).toInt()
        return FloatArray(a.size) { i -> (a[i] * 0.6f + (if (i >= off && i - off < b.size) b[i - off] * 0.5f else 0f)) }
    }

    private fun writeWav(f: File, s: FloatArray) {
        val data = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in s) data.putShort((v.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + s.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(SR); h.putInt(SR * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(s.size * 2)
        FileOutputStream(f).use { it.write(h.array()); it.write(data.array()) }
    }
}
