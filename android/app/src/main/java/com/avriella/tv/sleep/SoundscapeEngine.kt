package com.avriella.tv.sleep

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class SleepSound(val id: String, val name: String, val emoji: String, val theme: Int)

/** Soothing sounds, generated live on the phone: seamless, never repeating, fully offline. */
val SLEEP_SOUNDS = listOf(
    SleepSound("ocean", "Ocean waves", "🌊", SleepScene.OCEAN),
    SleepSound("rain", "Gentle rain", "🌧️", SleepScene.RAIN),
    SleepSound("storm", "Distant thunder", "⛈️", SleepScene.RAIN),
    SleepSound("waterfall", "Waterfall", "💦", SleepScene.WATER),
    SleepSound("stream", "Babbling brook", "🏞️", SleepScene.WATER),
    SleepSound("forest", "Forest breeze", "🌲", SleepScene.FOREST),
    SleepSound("crickets", "Night crickets", "🦗", SleepScene.FOREST),
    SleepSound("wind", "Soft wind", "🍃", SleepScene.NIGHT),
    SleepSound("heartbeat", "Mommy's heartbeat", "💓", SleepScene.WOMB),
    SleepSound("shush", "Shushing", "🤫", SleepScene.WOMB),
    SleepSound("lullaby", "Music box", "🎶", SleepScene.NIGHT),
    SleepSound("fan", "Fan", "🌀", SleepScene.NIGHT),
    SleepSound("white", "White noise", "⚪", SleepScene.NIGHT),
    SleepSound("brown", "Deep hum", "🟤", SleepScene.NIGHT),
)

fun sleepSound(id: String?) = SLEEP_SOUNDS.firstOrNull { it.id == id } ?: SLEEP_SOUNDS[0]

class SoundscapeEngine {
    private val sr = 22050
    @Volatile private var soundId = "ocean"
    @Volatile private var target = 0f
    @Volatile private var fadePerSample = 1f / (22050 * 2)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    val current get() = soundId
    val isPlaying get() = running && target > 0f

    fun start(id: String) {
        select(id)
        if (running) { target = 1f; return }
        running = true
        target = 1f
        thread = Thread({ loop() }, "sleep-sounds").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** Switches sound with a quick cross-fade. */
    fun select(id: String) {
        if (id == soundId && running) return
        pendingId = id
    }

    fun pause() { fade(0f, 1.5f) }
    fun resume() { fade(1f, 1.5f) }
    fun fade(to: Float, seconds: Float) { fadePerSample = 1f / (sr * seconds.coerceAtLeast(0.05f)); target = to }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    @Volatile private var pendingId: String? = null

    private fun loop() {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min * 2, 8192))
            .build()
        track = t
        t.play()
        val frames = 1024
        val buf = ShortArray(frames * 2)
        var gain = 0f
        var switchGain = 1f
        while (running) {
            for (i in 0 until frames) {
                // cross-fade when a new sound is picked
                val p = pendingId
                if (p != null) {
                    switchGain -= 1f / (sr * 0.6f)
                    if (switchGain <= 0f) { soundId = p; pendingId = null; resetState() }
                } else if (switchGain < 1f) switchGain = (switchGain + 1f / (sr * 0.8f)).coerceAtMost(1f)
                val tg = target
                gain = if (gain < tg) (gain + fadePerSample).coerceAtMost(tg) else (gain - fadePerSample).coerceAtLeast(tg)
                gen()
                val g = gain * switchGain * 0.9f
                buf[i * 2] = (clip(outL * g) * 32000).toInt().toShort()
                buf[i * 2 + 1] = (clip(outR * g) * 32000).toInt().toShort()
            }
            t.write(buf, 0, buf.size)
        }
        try { t.stop() } catch (_: Exception) {}
        t.release()
        track = null
    }

    private fun clip(v: Float) = if (v > 1f) 1f else if (v < -1f) -1f else v

    // ------------------------------------------------------------------ synthesis state
    private var outL = 0f; private var outR = 0f
    private var n = 0L // sample counter
    private val rnd = Random(42)
    private fun white() = rnd.nextFloat() * 2f - 1f

    // pink noise (Paul Kellet) per channel
    private val pk = Array(2) { FloatArray(7) }
    private fun pink(ch: Int): Float {
        val w = white(); val b = pk[ch]
        b[0] = 0.99886f * b[0] + w * 0.0555179f; b[1] = 0.99332f * b[1] + w * 0.0750759f
        b[2] = 0.96900f * b[2] + w * 0.1538520f; b[3] = 0.86650f * b[3] + w * 0.3104856f
        b[4] = 0.55000f * b[4] + w * 0.5329522f; b[5] = -0.7616f * b[5] - w * 0.0168980f
        val out = b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + w * 0.5362f
        b[6] = w * 0.115926f
        return out * 0.11f
    }
    private val br = FloatArray(2)
    private fun brown(ch: Int): Float { br[ch] = (br[ch] + 0.02f * white()) / 1.02f; return br[ch] * 3.5f }

    private val lp = FloatArray(8)
    private fun lowpass(slot: Int, x: Float, fc: Float): Float {
        val a = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()
        lp[slot] += a * (x - lp[slot]); return lp[slot]
    }

    // slow random "breathing" used for wind / waterfall movement
    private var walk = 0.5f; private var walkTarget = 0.5f
    private fun drift(speed: Float): Float {
        if (n % 2205 == 0L) walkTarget = rnd.nextFloat()
        walk += (walkTarget - walk) * speed
        return walk
    }

    // events (drips, bubbles, chirps, bells)
    private class Voice { var on = false; var t = 0; var len = 1; var f = 0f; var f2 = 0f; var ph = 0f; var amp = 0f; var pan = 0.5f; var kind = 0 }
    private val voices = Array(12) { Voice() }
    private fun spawn(kind: Int, f: Float, f2: Float, len: Float, amp: Float, pan: Float = rnd.nextFloat()) {
        val v = voices.firstOrNull { !it.on } ?: return
        v.on = true; v.t = 0; v.len = (len * sr).toInt().coerceAtLeast(1); v.f = f; v.f2 = f2; v.ph = 0f; v.amp = amp; v.pan = pan; v.kind = kind
    }
    private fun voicesOut() {
        for (v in voices) if (v.on) {
            val x = v.t.toFloat() / v.len
            val freq = v.f + (v.f2 - v.f) * x
            v.ph += (2 * PI * freq / sr).toFloat()
            if (v.ph > 2 * PI) v.ph -= (2 * PI).toFloat()
            val env = when (v.kind) {
                1 -> exp(-x * 6f)                                    // drip / bubble
                2 -> sin(PI * x).toFloat()                             // chirp
                3 -> minOf(1f, x * 60f) * exp(-x * 3.2f)               // bell
                4 -> minOf(1f, x * 40f) * exp(-x * 7f)                 // heartbeat thump
                else -> 1f
            }
            var s = sin(v.ph) * env * v.amp
            if (v.kind == 3) s += sin(v.ph * 2f) * env * v.amp * 0.25f * exp(-x * 6f)
            outL += s * (1f - v.pan); outR += s * v.pan
            v.t++
            if (v.t >= v.len) v.on = false
        }
    }

    private var nextEvent = 0L
    private var cricketPh = FloatArray(3)
    private var noteIdx = 0
    private var delayL = FloatArray(sr / 3); private var delayR = FloatArray(sr / 3 + 137); private var dPos = 0
    private var rumble = 0f; private var rumbleTarget = 0f; private var nextRumble = 0L

    private fun resetState() {
        voices.forEach { it.on = false }
        nextEvent = n + sr
        delayL.fill(0f); delayR.fill(0f)
    }

    private fun gen() {
        n++
        val t = n.toFloat() / sr
        outL = 0f; outR = 0f
        when (soundId) {
            "white" -> { outL = white() * 0.22f; outR = white() * 0.22f }
            "brown" -> { outL = brown(0) * 0.6f; outR = brown(1) * 0.6f }
            "fan" -> {
                val m = 1f + 0.06f * sin(2f * PI.toFloat() * 0.35f * t)
                outL = lowpass(0, pink(0), 900f) * 1.6f * m + brown(0) * 0.25f
                outR = lowpass(1, pink(1), 900f) * 1.6f * m + brown(1) * 0.25f
            }
            "ocean" -> {
                // two overlapping swells with different periods, brighter at the crest
                val w1 = sin(2 * PI * t / 9.5).toFloat(); val w2 = sin(2 * PI * t / 13.7 + 1.3).toFloat()
                val sw = (0.12f + 0.88f * (w1 * w1 * 0.65f + w2 * w2 * 0.35f))
                val fc = 300f + 1600f * sw * sw
                outL = (lowpass(0, pink(0), fc) * 2.2f + brown(0) * 0.25f) * sw
                val swR = (0.12f + 0.88f * (sin(2 * PI * t / 9.5 + 0.5).toFloat().let { it * it } * 0.65f + w2 * w2 * 0.35f))
                outR = (lowpass(1, pink(1), 300f + 1600f * swR * swR) * 2.2f + brown(1) * 0.25f) * swR
            }
            "rain", "storm" -> {
                outL = lowpass(0, pink(0), 4500f) * 0.9f + white() * 0.03f
                outR = lowpass(1, pink(1), 4500f) * 0.9f + white() * 0.03f
                if (n >= nextEvent) { // individual drops
                    spawn(1, 1800f + rnd.nextFloat() * 2500f, 900f, 0.025f, 0.10f + rnd.nextFloat() * 0.12f)
                    nextEvent = n + (sr * (0.01f + rnd.nextFloat() * 0.06f)).toLong()
                }
                if (soundId == "storm") {
                    if (n >= nextRumble) { rumbleTarget = 0.6f + rnd.nextFloat() * 0.6f; nextRumble = n + sr * (18 + rnd.nextInt(25)) }
                    rumble += (rumbleTarget - rumble) * 0.00004f
                    if (rumble > rumbleTarget * 0.95f) rumbleTarget = 0f
                    val r = lowpass(4, brown(0), 120f) * 5f * rumble
                    outL += r; outR += r
                }
            }
            "waterfall" -> {
                val m = 0.85f + 0.3f * drift(0.00002f)
                outL = (pink(0) * 0.9f + lowpass(0, brown(0), 500f) * 0.6f) * m
                outR = (pink(1) * 0.9f + lowpass(1, brown(1), 500f) * 0.6f) * m
            }
            "stream" -> {
                outL = (pink(0) - lowpass(0, pink(0), 700f)) * 0.55f
                outR = (pink(1) - lowpass(1, pink(1), 700f)) * 0.55f
                if (n >= nextEvent) { // little gurgles
                    val f = 350f + rnd.nextFloat() * 900f
                    spawn(1, f, f * (1.3f + rnd.nextFloat()), 0.04f + rnd.nextFloat() * 0.06f, 0.05f + rnd.nextFloat() * 0.07f)
                    nextEvent = n + (sr * (0.02f + rnd.nextFloat() * 0.09f)).toLong()
                }
            }
            "wind", "forest" -> {
                val d = drift(0.000015f)
                val fc = 250f + 900f * d
                outL = lowpass(0, brown(0) + pink(0) * 0.4f, fc) * (0.5f + 0.9f * d) * 1.5f
                outR = lowpass(1, brown(1) + pink(1) * 0.4f, fc * 1.1f) * (0.5f + 0.9f * d) * 1.5f
                if (soundId == "forest" && n >= nextEvent) { // a few soft birds
                    val base = 2200f + rnd.nextFloat() * 1800f
                    val pan = rnd.nextFloat()
                    repeat(2 + rnd.nextInt(3)) { k -> spawnLater(k * 0.13f, base * (1f + 0.08f * k), base * (1.25f + 0.05f * k), 0.09f, 0.035f, pan) }
                    nextEvent = n + (sr * (3f + rnd.nextFloat() * 6f)).toLong()
                }
                flushLater()
            }
            "crickets" -> {
                outL = lowpass(0, brown(0), 300f) * 0.25f
                outR = lowpass(1, brown(1), 300f) * 0.25f
                val freqs = floatArrayOf(4300f, 4700f, 5100f); val rates = floatArrayOf(0.85f, 1.15f, 0.7f); val pans = floatArrayOf(0.2f, 0.75f, 0.5f)
                for (c in 0 until 3) {
                    cricketPh[c] += (2 * PI * freqs[c] / sr).toFloat()
                    val cyc = (t * rates[c] + c * 0.37f) % 1f
                    // 3 pulses per chirp, then silence
                    val pulse = if (cyc < 0.24f) { val q = (cyc / 0.08f) % 1f; if (q < 0.6f) sin(PI * q / 0.6f).toFloat() else 0f } else 0f
                    val s = sin(cricketPh[c]) * pulse * 0.05f
                    outL += s * (1f - pans[c]); outR += s * pans[c]
                }
            }
            "heartbeat" -> {
                outL = lowpass(0, brown(0), 250f) * 1.2f + lowpass(2, pink(0), 500f) * 0.3f
                outR = lowpass(1, brown(1), 250f) * 1.2f + lowpass(3, pink(1), 500f) * 0.3f
                val beat = (sr * 60f / 68f).toLong()
                val inBeat = n % beat
                if (inBeat == 0L) spawn(4, 55f, 38f, 0.16f, 0.55f, 0.5f)
                if (inBeat == (sr * 0.28f).toLong()) spawn(4, 48f, 34f, 0.14f, 0.35f, 0.5f)
            }
            "shush" -> {
                val cyc = (t / 2.6f) % 1f
                val env = if (cyc < 0.7f) sin(PI * cyc / 0.7f).toFloat().let { it * it } else 0f
                val hiL = pink(0) - lowpass(0, pink(0), 1800f); val hiR = pink(1) - lowpass(1, pink(1), 1800f)
                outL = hiL * env * 1.2f + brown(0) * 0.08f
                outR = hiR * env * 1.2f + brown(1) * 0.08f
            }
            "lullaby" -> {
                outL = lowpass(0, pink(0), 400f) * 0.12f; outR = lowpass(1, pink(1), 400f) * 0.12f
                if (n >= nextEvent) {
                    val scale = floatArrayOf(523.25f, 587.33f, 659.25f, 783.99f, 880f, 1046.5f)
                    val tune = intArrayOf(2, 1, 0, 1, 2, 2, 2, -1, 1, 1, 1, -1, 2, 4, 4, -1, 2, 1, 0, 1, 2, 2, 2, 1, 1, 2, 1, 0, -1, -1)
                    val step = tune[noteIdx % tune.size]; noteIdx++
                    if (step >= 0) spawn(3, scale[step], scale[step], 2.2f, 0.16f, 0.35f + rnd.nextFloat() * 0.3f)
                    nextEvent = n + (sr * 0.62f).toLong()
                }
            }
        }
        voicesOut()
        if (soundId == "lullaby") { // soft echo for a dreamy music box
            val l = outL + delayR[dPos % delayR.size] * 0.35f
            val r = outR + delayL[dPos % delayL.size] * 0.35f
            delayL[dPos % delayL.size] = l; delayR[dPos % delayR.size] = r; dPos++
            outL = l; outR = r
        }
    }

    // birds need a little delay between notes of a trill
    private val later = ArrayList<FloatArray>()
    private fun spawnLater(after: Float, f: Float, f2: Float, len: Float, amp: Float, pan: Float) {
        later += floatArrayOf((n + after * sr).toFloat(), f, f2, len, amp, pan)
    }
    private fun flushLater() {
        if (later.isEmpty()) return
        val it = later.iterator()
        while (it.hasNext()) { val e = it.next(); if (n >= e[0].toLong()) { spawn(2, e[1], e[2], e[3], e[4], e[5]); it.remove() } }
    }

    @Suppress("unused") private fun abs01(x: Float) = abs(x)
}
