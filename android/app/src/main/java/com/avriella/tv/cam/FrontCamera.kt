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
        fpsRange?.let { set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
    }.build()

    /** Frame-rate range asked of the camera on home Wi-Fi (null = camera's default). */
    private var fpsRange: android.util.Range<Int>? = null

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
            val gap = if (hq) 33L else 80L
            val quality = if (hq) 78 else 60
            // a range topping out at 30 fps whose lowest is as close to 24 as possible: smooth, but still bright enough at night
            fpsRange = if (!hq) null else (ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf())
                .filter { it.upper in 30..31 }.minByOrNull { abs(it.lower - 24) * 2 + (if (it.lower < 24) 1 else 0) }
            interleaved = null
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrame >= gap && busy.get() < 2) {
                        lastFrame = now
                        val n = ++frameNo
                        val w = img.width and 1.inv(); val h = img.height and 1.inv()
                        val buf = toNv21(img, w, h)
                        busy.incrementAndGet()
                        encoders.execute {
                            try {
                                val bos = ByteArrayOutputStream(w * h / 5)
                                YuvImage(buf, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, bos)
                                val jpg = bos.toByteArray()
                                if (error != null) error = null // pictures are flowing, so any old hiccup message is out of date
                                synchronized(lock) { if (n > shownNo) { shownNo = n; latest = jpg; seq++; lock.notifyAll() } }
                            } catch (e: Exception) { Log.w("FrontCamera", "jpeg", e) } finally { pool.offer(buf); busy.decrementAndGet() }
                        }
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
                            if (device !== cd) { try { s.close() } catch (_: Exception) {}; return } // an older camera we already closed
                            session = s
                            fails = 0
                            try {
                                s.setRepeatingRequest(request(cd, r), null, handler)
                            } catch (e: Exception) { error = "Camera error: ${e.message}" }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { if (device === cd) error = "Camera could not start" }
                    }, handler)
                    } catch (e: Exception) {
                        Log.w("FrontCamera", "camera taken while starting", e)
                        try { cd.close() } catch (_: Exception) {}
                        device = null; error = "Camera is busy – trying again…"
                        handler?.postDelayed({ if (viewers > 0 && device == null) openCamera() }, 3000)
                    }
                }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); if (device !== cd && device != null) return; device = null; holdsBack = false; error = "Camera was taken by another app – trying again…"; afterFail() }
                override fun onError(cd: CameraDevice, e: Int) { cd.close(); if (device !== cd && device != null) return; device = null; holdsBack = false; error = "Camera error $e – trying again…"; afterFail() }
            }, handler)
        } catch (e: SecurityException) {
            error = "Camera permission is off"
        } catch (e: Exception) {
            error = "Camera error: ${e.message}"
        }
    }

    /**
     * The camera dropped out. Never leave it broken: try again, a little slower each time.
     * After her phone restarts (or the app updates) Android only lends the camera to Avriella TV
     * while it's on screen, so bring it forward if the screen is on, or say what to do if it's off.
     */
    private var fails = 0
    private fun afterFail() {
        fails++
        try { reader?.close() } catch (_: Exception) {}
        reader = null; session = null
        if (viewers <= 0 || CamState.inCall) return
        if (!Wake.topResumed) {
            if (Wake.screenOn && Wake.canLaunch()) android.os.Handler(android.os.Looper.getMainLooper()).post { Wake.bringForward() }
            else if (fails >= 2) error = "Open Avriella TV on her phone once (or tap Wake) to turn the camera back on."
        }
        val wait = minOf(15_000L, 1200L * fails)
        handler?.postDelayed({ if (viewers > 0 && device == null && !CamState.inCall) openCamera() }, wait)
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

    private val encoders = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val busy = java.util.concurrent.atomic.AtomicInteger(0)
    private val pool = java.util.concurrent.ArrayBlockingQueue<ByteArray>(4)
    private var frameNo = 0L
    private var shownNo = 0L
    private var uArr = ByteArray(0)
    private var vArr = ByteArray(0)
    /** true when the camera hands over its colour as one V,U,V,U… block (then it can be copied a row at a time). */
    private var interleaved: Boolean? = null

    private fun toNv21(img: Image, w: Int, h: Int): ByteArray {
        val need = w * h * 3 / 2
        var out = pool.poll()
        while (out != null && out.size != need) out = pool.poll()
        if (out == null) out = ByteArray(need)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ys = yP.rowStride
        if (ys == w) { yb.position(0); yb.get(out, 0, w * h) }
        else for (row in 0 until h) { yb.position(row * ys); yb.get(out, row * w, w) }
        val ub = uP.buffer; val vb = vP.buffer
        ub.position(0); vb.position(0)
        val un = ub.remaining(); val vn = vb.remaining()
        if (uArr.size < un) uArr = ByteArray(un)
        if (vArr.size < vn) vArr = ByteArray(vn)
        ub.get(uArr, 0, un); vb.get(vArr, 0, vn)
        val urs = uP.rowStride; val ups = uP.pixelStride
        val vrs = vP.rowStride; val vps = vP.pixelStride
        if (interleaved == null) interleaved = ups == 2 && vps == 2 && urs == vrs &&
            (0 until minOf(64, un / 2 - 1)).all { vArr[it * 2 + 1] == uArr[it * 2] }
        var o = w * h
        if (interleaved == true) {
            // already in NV21 order: copy each row of colour in one go
            for (row in 0 until h / 2) {
                val src = row * vrs
                val n = minOf(w, vn - src)
                System.arraycopy(vArr, src, out, o, n)
                if (n < w) out[o + w - 1] = uArr[row * urs + w - 2]
                o += w
            }
        } else {
            for (row in 0 until h / 2) {
                val vr = row * vrs; val ur = row * urs
                for (col in 0 until w / 2) {
                    val vi = vr + col * vps; val ui = ur + col * ups
                    out[o++] = if (vi < vn) vArr[vi] else 0x80.toByte()
                    out[o++] = if (ui < un) uArr[ui] else 0x80.toByte()
                }
            }
        }
        return out
    }
}
