package com.avriella.tv.cam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The flashlight on her phone (the light next to the back camera), for finding a binky in the dark.
 * It can be switched from her phone's parent screens or from the iPhone app, and turns itself off after 10 minutes.
 */
object Torch {
    @Volatile var on = false
        private set
    private const val AUTO_OFF_MS = 10 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var watching = false
    private val autoOff = Runnable { app?.let { set(it, false) } }
    /** Called (on the main thread) whenever the light changes, so screens can update their button. */
    val listeners = mutableListOf<() -> Unit>()

    private fun mgr(ctx: Context) = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun backId(ctx: Context): String? = try {
        val m = mgr(ctx)
        m.cameraIdList.firstOrNull {
            val c = m.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Exception) { null }

    fun available(ctx: Context) = backId(ctx) != null

    /** Keep [on] right when someone uses the flashlight tile in quick settings instead. */
    private fun watch(ctx: Context, id: String) {
        if (watching) return
        watching = true
        try {
            mgr(ctx).registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    // while the baby cam uses the back camera it drives the light itself, so ignore the plain torch then
                    if (cameraId != id || FrontCamera.instance?.holdsBack == true || enabled == on) return
                    on = enabled
                    if (!enabled) main.removeCallbacks(autoOff)
                    changed()
                }
            }, main)
        } catch (_: Exception) {}
    }

    /** Turns the light on or off. Returns null if it worked, or a short reason it didn't. */
    fun set(ctx: Context, want: Boolean): String? {
        val c = ctx.applicationContext
        app = c
        val id = backId(c) ?: return "Her phone doesn't have a flashlight."
        watch(c, id)
        val before = on
        on = want
        val cam = FrontCamera.instance
        if (cam != null && cam.holdsBack) cam.applyTorch() // the baby cam is using the back camera: light it through that
        else try {
            mgr(c).setTorchMode(id, want)
        } catch (e: Exception) {
            Log.w("Torch", "torch failed", e)
            on = before
            changed()
            return if (want) "The light is busy right now. If the baby cam is on the front camera, switch it to the back camera and try again." else "Couldn't turn the light off."
        }
        main.removeCallbacks(autoOff)
        if (want) main.postDelayed(autoOff, AUTO_OFF_MS)
        changed()
        return null
    }

    fun toggle(ctx: Context): String? = set(ctx, !on)

    /** The baby cam let go of the back camera: keep the light on with the plain flashlight. */
    fun cameraReleased(ctx: Context) {
        if (!on) return
        main.postDelayed({
            if (on && FrontCamera.instance?.holdsBack != true) {
                val id = backId(ctx) ?: return@postDelayed
                try { mgr(ctx).setTorchMode(id, true) } catch (_: Exception) { on = false; changed() }
            }
        }, 400)
    }

    private fun changed() {
        main.post { listeners.toList().forEach { try { it() } catch (_: Exception) {} } }
    }
}
