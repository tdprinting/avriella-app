package com.avriella.tv.cam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Front camera that only runs while someone is actually watching the baby cam.
 * Frames are turned into small JPEGs that the web server streams to the iPhones.
 */
class FrontCamera(private val ctx: Context) {
    private val lock = Object()
    @Volatile var latest: ByteArray? = null
        private set
    @Volatile var seq = 0L
        private set
    var sensorOrientation = 270
        private set
    @Volatile var error: String? = null
        private set
    /** true = back camera, false = front (selfie) camera. */
    @Volatile var useBack = false
        private set

    /** Switch between front and back. Only the camera restarts – her video is never touched. */
    fun select(back: Boolean) {
        if (back == useBack) return
        useBack = back
        ensureThread()
        handler?.post {
            if (device != null) { closeCamera(); if (viewers > 0) openCamera() }
        }
    }

    private var viewers = 0
    /** Viewers on the home Wi-Fi: they get a bigger, sharper, smoother picture. */
    private var hqViewers = 0
    /** The quality the camera is currently running at (it restarts when this needs to change). */
    private var openHq = false
    /** true while the camera we have open is the back one (the one with the flashlight). */
    @Volatile var holdsBack = false
        private set
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastFrame = 0L
    private val closeLater = Runnable { closeCamera() }

    init { instance = this }
    companion object { @Volatile var instance: FrontCamera? = null }

    /** A video call needs the camera: let go of it right away (and don't reopen until the call ends). */
    fun pauseForCall() { ensureThread(); handler?.removeCallbacks(closeLater); handler?.post { closeCamera() } }
    // give the call screen a moment to let go of the camera first
    fun resumeAfterCall() { ensureThread(); handler?.postDelayed({ if (viewers > 0 && device == null && !CamState.inCall) openCamera() }, 2500) }

    fun acquire(hq: Boolean = false) {
        synchronized(this) {
            viewers++
            if (hq) hqViewers++
            CamState.viewers = viewers
            ensureThread()
            handler?.removeCallbacks(closeLater)
            if (device == null && !CamState.inCall) handler?.post { openCamera() }
            else if (hq && !openHq) handler?.post { restartForQuality() }
        }
    }

    fun release(hq: Boolean = false) {
        synchronized(this) {
            viewers = (viewers - 1).coerceAtLeast(0)
            if (hq) hqViewers = (hqViewers - 1).coerceAtLeast(0)
            CamState.viewers = viewers
            if (viewers == 0) handler?.postDelayed(closeLater, 8000)
            // the last home-Wi-Fi viewer left but others still watch over the internet: go back to small frames
            else if (hq && hqViewers == 0 && openHq) handler?.postDelayed({ if (hqViewers == 0) restartForQuality() }, 4000)
        }
    }

    private fun restartForQuality() {
        if (device != null && openHq != (hqViewers > 0)) { closeCamera(); if (viewers > 0) openCamera() }
    }

    /** Turn the back camera's light on/off while we're using that camera (Android won't let the plain torch do it then). */
    fun applyTorch() {
        handler?.post {
            val cd = device; val s = session; val r = reader
            if (cd != null && s != null && r != null && holdsBack) try { s.setRepeatingRequest(request(cd, r), null, handler) } catch (_: Exception) {}
        }
    }

    private fun request(cd: CameraDevice, r: ImageReader) = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(r.surface)
        if (holdsBack && Torch.on) set(android.hardware.camera2.CaptureRequest.FLASH_MODE, android.hardware.camera2.CaptureRequest.FLASH_MODE_TORCH)
    }.build()

    /** Blocks until a frame newer than [after] exists (or timeout). */
    fun waitFrame(after: Long, timeoutMs: Long): ByteArray? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        synchronized(lock) {
            while (seq <= after) {
                val left = end - SystemClock.uptimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
        }
        return latest
    }

    private fun ensureThread() {
        if (thread == null) {
            thread = HandlerThread("babycam").apply {
                // a camera hiccup must never close her show: log it and start a fresh camera thread next time
                setUncaughtExceptionHandler { _, e ->
                    Log.w("FrontCamera", "camera thread error", e)
                    error = "Camera hiccup – trying again…"
                    device = null; session = null; reader = null
                    thread = null; handler = null
                }
                start()
            }
            handler = Handler(thread!!.looper)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            error = "Camera isn't allowed on her phone yet. Open Avriella TV there and tap Allow."; return
        }
        if (device != null || CamState.inCall) return
        try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (useBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
            } ?: mgr.cameraIdList.firstOrNull() ?: run { error = "No camera found"; return }
            val ch = mgr.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: arrayOf(Size(640, 480))
            val hq = hqViewers > 0
            openHq = hq
            // home Wi-Fi: 720p at ~25 fps. Over the internet relay: small 640x480 frames so they get through quickly.
            val size = if (hq) sizes.filter { it.width <= 1280 && it.height <= 960 }.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: sizes.last()
                else sizes.filter { it.width <= 960 }.minByOrNull { abs(it.width * it.height - 640 * 480) } ?: sizes.last()
            val gap = if (hq) 38L else 80L
            val quality = if (hq) 82 else 60
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrame >= gap) {
                        lastFrame = now
                        val jpg = toJpeg(img, quality)
                        synchronized(lock) { latest = jpg; seq++; lock.notifyAll() }
                    }
                } catch (e: Exception) {
                    Log.w("FrontCamera", "frame", e)
                } finally { img.close() }
            }, handler)
            reader = r
            // say so before opening, so the flashlight's "turned off" notice from Android isn't mistaken for a parent turning it off
            holdsBack = useBack
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    // A video call (or another app) may grab the camera at any moment: never let that crash the app.
                    if (CamState.inCall) { try { cd.close() } catch (_: Exception) {}; return }
                    device = cd
                    holdsBack = useBack
                    error = null
                    try {
                    @Suppress("DEPRECATION")
                    cd.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            try {
                                s.setRepeatingRequest(request(cd, r), null, handler)
                            } catch (e: Exception) { error = "Camera error: ${e.message}" }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { error = "Camera could not start" }
                    }, handler)
                    } catch (e: Exception) {
                        Log.w("FrontCamera", "camera taken while starting", e)
                        try { cd.close() } catch (_: Exception) {}
                        device = null; error = "Camera is busy – trying again…"
                        handler?.postDelayed({ if (viewers > 0 && device == null) openCamera() }, 3000)
                    }
                }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); device = null; holdsBack = false; error = "Camera was taken by another app" }
                override fun onError(cd: CameraDevice, e: Int) { cd.close(); device = null; holdsBack = false; error = "Camera error $e" }
            }, handler)
        } catch (e: SecurityException) {
            error = "Camera permission is off"
        } catch (e: Exception) {
            error = "Camera error: ${e.message}"
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        // the light was on through our camera: hand it back to the plain flashlight so it stays on
        if (holdsBack) { holdsBack = false; Torch.cameraReleased(ctx) }
    }

    fun shutdown() {
        handler?.post { closeCamera(); thread?.quitSafely() }
        thread = null; handler = null
    }

    private var nv = ByteArray(0)
    private var uArr = ByteArray(0)
    private var vArr = ByteArray(0)

    private fun toJpeg(img: Image, quality: Int): ByteArray {
        val w = img.width; val h = img.height
        if (nv.size != w * h * 3 / 2) nv = ByteArray(w * h * 3 / 2)
        val out = nv
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ys = yP.rowStride
        for (row in 0 until h) {
            yb.position(row * ys)
            yb.get(out, row * w, w)
        }
        // copy the color planes out in one go (reading a camera buffer byte by byte is very slow at 720p)
        val ub = uP.buffer; val vb = vP.buffer
        ub.position(0); vb.position(0)
        if (uArr.size < ub.remaining()) uArr = ByteArray(ub.remaining())
        if (vArr.size < vb.remaining()) vArr = ByteArray(vb.remaining())
        val un = ub.remaining(); val vn = vb.remaining()
        ub.get(uArr, 0, un); vb.get(vArr, 0, vn)
        val urs = uP.rowStride; val ups = uP.pixelStride
        val vrs = vP.rowStride; val vps = vP.pixelStride
        var o = w * h
        for (row in 0 until h / 2) {
            val vr = row * vrs; val ur = row * urs
            for (col in 0 until w / 2) {
                val vi = vr + col * vps; val ui = ur + col * ups
                out[o++] = if (vi < vn) vArr[vi] else 0x80.toByte()
                out[o++] = if (ui < un) uArr[ui] else 0x80.toByte()
            }
        }
        val bos = ByteArrayOutputStream(w * h / 4)
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, bos)
        return bos.toByteArray()
    }
}
