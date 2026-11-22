package com.avriella.tv.cam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.avriella.tv.MainActivity
import com.avriella.tv.data.Library
import com.avriella.tv.kid.KidActivity
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Remote wake-up: turns her screen on from the iPhone and brings Avriella TV back to the front,
 * even when the phone has gone to sleep.
 *
 * Two things make this work on modern Android:
 *  - the kid screens are allowed to turn the screen on and show over the lock screen, and
 *  - "Display over other apps" lets the app come to the front while it's in the background.
 */
object Wake {
    /** The Avriella TV screen most recently in front (still alive, maybe behind the sleeping screen). */
    @Volatile var top: WeakReference<Activity>? = null
    @Volatile var topResumed = false

    private val ctx get() = Library.appCtx
    private val pm get() = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

    val screenOn get() = pm.isInteractive
    fun canLaunch() = Settings.canDrawOverlays(ctx)

    /** True when her phone is awake with an Avriella TV screen in front. */
    fun ready() = screenOn && topResumed

    /** Lights the screen and brings the last Avriella TV screen (or the menu) forward. Main thread. */
    fun wakeNow() {
        try {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "avriella:wake")
                .acquire(5000)
        } catch (e: Exception) { android.util.Log.w("AvriellaRemote", "screen wake lock failed", e) }
        val a = top?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val cls = a?.javaClass ?: MainActivity::class.java
        try {
            ctx.startActivity(Intent(ctx, cls).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { android.util.Log.w("AvriellaRemote", "couldn't bring the app forward", e) }
    }

    /** Brings Avriella TV to the front without lighting the screen (Android only lends the camera to the app on screen). */
    fun bringForward() {
        val a = top?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val cls = a?.javaClass ?: MainActivity::class.java
        try {
            ctx.startActivity(Intent(ctx, cls).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { android.util.Log.w("AvriellaRemote", "couldn't bring the app forward", e) }
    }

    // ---------- screen off ----------
    private val dpm get() = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    private val admin get() = android.content.ComponentName(ctx, com.avriella.tv.kid.AdminReceiver::class.java)

    /** True once "Allow screen off" was approved on her phone (Android needs that once, for the app to switch the screen off). */
    fun canScreenOff(): Boolean = try { dpm.isAdminActive(admin) } catch (_: Exception) { false }

    /** The screen Android shows to approve "Allow screen off". */
    fun screenOffSetupIntent(): Intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Lets you turn her screen off from your iPhone. Avriella TV only ever uses this to switch the screen off.")

    /** Switches her screen off completely (a show pauses first; sleep sounds keep playing). */
    fun screenOff(): String {
        if (!canScreenOff()) return "To turn her screen off from here, allow it once on her phone: Manage → Baby cam → Allow screen off."
        return Remote.onMain {
            if (CamState.mode == "watching") try { Remote.handler?.invoke(Remote.Cmd("pause")) } catch (_: Exception) {}
            try { dpm.lockNow(); "ok" } catch (e: SecurityException) { "Her phone didn't allow turning the screen off." }
        } ?: "Her phone didn't answer."
    }

    /** Wakes her phone and waits (off the main thread) until a screen is ready for commands. */
    fun wakeAndWait(timeoutMs: Long = 5000): Boolean {
        if (ready()) return true
        Remote.onMain { wakeNow(); true }
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (ready() && Remote.handler != null) { Thread.sleep(250); return true }
            Thread.sleep(100)
        }
        return ready()
    }

    /** True when a kid screen (video, game, sounds) is alive — it keeps taking commands while asleep. */
    fun kidScreenAlive() = top?.get()?.let { it is KidActivity && !it.isFinishing && !it.isDestroyed } == true

    /** Every address her phone can be reached on (Wi-Fi, hotspot, Tailscale). */
    fun addresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                if (ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") || ni.name.startsWith("dummy")) continue
                for (a in ni.inetAddresses) if (a is Inet4Address) a.hostAddress?.let { out += it }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Her Tailscale address (100.64.0.0/10), if Tailscale is running on her phone. */
    fun tailscale(): String? = addresses().firstOrNull {
        val p = it.split('.'); p.size == 4 && p[0] == "100" && (p[1].toIntOrNull() ?: 0) in 64..127
    }
}
