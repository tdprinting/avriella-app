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
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastFrame = 0L
    private val closeLater = Runnable { closeCamera() }

    fun acquire() {
        synchronized(this) {
            viewers++
            CamState.viewers = viewers
            ensureThread()
            handler?.removeCallbacks(closeLater)
            if (device == null) handler?.post { openCamera() }
        }
    }

    fun release() {
        synchronized(this) {
            viewers = (viewers - 1).coerceAtLeast(0)
            CamState.viewers = viewers
            if (viewers == 0) handler?.postDelayed(closeLater, 8000)
        }
    }

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
            thread = HandlerThread("babycam").apply { start() }
            handler = Handler(thread!!.looper)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (device != null) return
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
            val size = sizes.filter { it.width <= 960 }.minByOrNull { abs(it.width * it.height - 640 * 480) } ?: sizes.last()
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrame >= 80) { // ~12 fps is plenty for a baby monitor
                        lastFrame = now
                        val jpg = toJpeg(img)
                        synchronized(lock) { latest = jpg; seq++; lock.notifyAll() }
                    }
                } catch (e: Exception) {
                    Log.w("FrontCamera", "frame", e)
                } finally { img.close() }
            }, handler)
            reader = r
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    device = cd
                    error = null
                    @Suppress("DEPRECATION")
                    cd.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            try {
                                val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                                s.setRepeatingRequest(req.build(), null, handler)
                            } catch (e: Exception) { error = "Camera error: ${e.message}" }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { error = "Camera could not start" }
                    }, handler)
                }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); device = null; error = "Camera was taken by another app" }
                override fun onError(cd: CameraDevice, e: Int) { cd.close(); device = null; error = "Camera error $e" }
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
    }

    fun shutdown() {
        handler?.post { closeCamera(); thread?.quitSafely() }
        thread = null; handler = null
    }

    private fun toJpeg(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ys = yP.rowStride
        for (row in 0 until h) {
            yb.position(row * ys)
            yb.get(out, row * w, w)
        }
        val ub = uP.buffer; val vb = vP.buffer
        val urs = uP.rowStride; val ups = uP.pixelStride
        val vrs = vP.rowStride; val vps = vP.pixelStride
        var o = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[o++] = vb.get(row * vrs + col * vps)
                out[o++] = ub.get(row * urs + col * ups)
            }
        }
        val bos = ByteArrayOutputStream(w * h / 4)
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 60, bos)
        return bos.toByteArray()
    }
}
