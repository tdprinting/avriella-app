package com.avriella.tv.kid

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A video player that can turn its picture to stay level with the ground.
 *
 * It works like VideoView (same calls the player screen uses), but draws into a TextureView so the
 * picture can be rotated. With [keepLevel] on, the gravity sensor tells us how the phone is tilted and
 * the picture is counter-rotated (and shrunk to fit) so the show stays upright while she flips the phone.
 */
class LevelVideoView(ctx: Context) : FrameLayout(ctx), TextureView.SurfaceTextureListener, SensorEventListener {
    private val texture = TextureView(ctx)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingUri: Uri? = null
    private var prepared = false
    private var wantPlay = false
    private var videoW = 16
    private var videoH = 9

    private var onPrepared: MediaPlayer.OnPreparedListener? = null
    private var onCompletion: MediaPlayer.OnCompletionListener? = null
    private var onError: MediaPlayer.OnErrorListener? = null

    // ---------- keep-level ----------
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val usingAccel = gravitySensor?.type == Sensor.TYPE_ACCELEROMETER
    private val g = FloatArray(3)
    private var listening = false
    private var angle = 0f          // what's on screen (degrees, clockwise)
    private var target = 0f         // where we're heading

    var keepLevel = false
        set(v) {
            field = v
            if (v) startSensor() else { stopSensor(); target = 0f; angle = 0f; applyTransform() }
        }

    init {
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    // ---------- VideoView-like API ----------
    fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener) { onPrepared = l }
    fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener) { onCompletion = l }
    fun setOnErrorListener(l: MediaPlayer.OnErrorListener) { onError = l }

    fun setVideoURI(uri: Uri) {
        pendingUri = uri
        wantPlay = false
        openVideo()
    }

    fun start() { wantPlay = true; if (prepared) player?.start() }
    fun pause() { wantPlay = false; if (prepared) try { player?.pause() } catch (_: Exception) {} }
    fun seekTo(ms: Int) { if (prepared) player?.seekTo(ms.coerceAtLeast(0)) }
    val isPlaying: Boolean get() = prepared && try { player?.isPlaying == true } catch (_: Exception) { false }
    val currentPosition: Int get() = if (prepared) try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 } else 0
    val duration: Int get() = if (prepared) try { player?.duration ?: -1 } catch (_: Exception) { -1 } else -1

    fun stopPlayback() {
        pendingUri = null
        release()
        stopSensor()
    }

    private fun release() {
        prepared = false
        player?.let { try { it.reset(); it.release() } catch (_: Exception) {} }
        player = null
    }

    private fun openVideo() {
        val uri = pendingUri ?: return
        val s = surface ?: return   // will open once the surface exists
        release()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            mp.setDataSource(context, uri)
            mp.setSurface(s)
            mp.setScreenOnWhilePlaying(true)
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                prepared = true
                onPrepared?.onPrepared(p)
                if (wantPlay && !p.isPlaying) p.start()
            }
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { videoW = w; videoH = h; requestLayout(); applyTransform() } }
            mp.setOnCompletionListener { p -> if (player === p) onCompletion?.onCompletion(p) }
            mp.setOnErrorListener { p, what, extra ->
                if (player !== p) return@setOnErrorListener true
                prepared = false
                onError?.onError(p, what, extra) ?: false
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            prepared = false
            post { onError?.onError(mp, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0) }
        }
    }

    // ---------- surface ----------
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        if (player != null && prepared) player?.setSurface(surface) else openVideo()
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        try { player?.setSurface(null) } catch (_: Exception) {}
        surface?.release(); surface = null
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---------- layout: the picture is fitted at 0°, then scaled while turning ----------
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = r - l; val ch = b - t
        if (cw <= 0 || ch <= 0) return
        val ar = videoW.toFloat() / videoH
        var bw = cw; var bh = (cw / ar).toInt()
        if (bh > ch) { bh = ch; bw = (ch * ar).toInt() }
        val x = (cw - bw) / 2; val y = (ch - bh) / 2
        texture.layout(x, y, x + bw, y + bh)
        applyTransform()
    }

    private fun applyTransform() {
        val cw = width.toFloat(); val ch = height.toFloat()
        val bw = texture.width.toFloat(); val bh = texture.height.toFloat()
        if (cw <= 0 || bw <= 0) return
        val r = Math.toRadians(angle.toDouble())
        val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        val scale = min(cw / (bw * c + bh * s), ch / (bw * s + bh * c))
        texture.pivotX = bw / 2; texture.pivotY = bh / 2
        texture.rotation = angle
        texture.scaleX = scale; texture.scaleY = scale
    }

    // ---------- sensor ----------
    private fun startSensor() {
        if (listening || gravitySensor == null || !isAttachedToWindow) return
        listening = sensors.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
    }
    private fun stopSensor() {
        if (listening) sensors.unregisterListener(this)
        listening = false
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (keepLevel) startSensor() }
    override fun onDetachedFromWindow() { stopSensor(); super.onDetachedFromWindow() }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (usingAccel) { for (i in 0..2) g[i] = g[i] * 0.85f + e.values[i] * 0.15f }   // smooth out shakes
        else { g[0] = e.values[0]; g[1] = e.values[1]; g[2] = e.values[2] }
        val gx = g[0]; val gy = g[1]
        val flat = sqrt(gx * gx + gy * gy)
        // Lying flat (face up/down): no clear "up", so keep the last angle.
        if (flat > 3f) {
            // Turn device axes into screen axes for however the screen is currently showing.
            @Suppress("DEPRECATION")
            val rot = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val (sx, sy) = when (rot) {
                Surface.ROTATION_90 -> -gy to gx
                Surface.ROTATION_180 -> -gx to -gy
                Surface.ROTATION_270 -> gy to -gx
                else -> gx to gy
            }
            target = Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()
        }
        // Ease toward the target along the shortest way round.
        var d = target - angle
        while (d > 180) d -= 360
        while (d < -180) d += 360
        if (abs(d) < 0.3f) return
        angle += d * 0.18f
        if (angle > 180) angle -= 360
        if (angle < -180) angle += 360
        applyTransform()
    }
}
