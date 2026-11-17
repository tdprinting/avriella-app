package com.avriella.tv.kid

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/** Needed so the app can become "device owner", which allows a full, silent kiosk lock. */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Two levels of lock:
 *  - FULL (app is device owner): Home, Recents, notification shade, status bar and the power menu are all disabled.
 *  - BASIC (screen pinning): Android pins the app; it can still be un-pinned with a special button/gesture hold.
 */
object KidLock {
    const val ADB_COMMAND = "adb shell dpm set-device-owner com.avriella.tv/.kid.AdminReceiver"

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isOwner(ctx: Context): Boolean = try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (_: Exception) { false }

    /** Full lock: we're the device owner, or another device manager (like Test DPC) allowed us into lock task mode. */
    fun isFullLock(ctx: Context): Boolean = isOwner(ctx) || try { dpm(ctx).isLockTaskPermitted(ctx.packageName) } catch (_: Exception) { false }

    /** Package of an installed device-manager app (like Test DPC) that can allow our lock task, or null. */
    fun testDpc(ctx: Context): String? = listOf("com.afwsamples.testdpc").firstOrNull {
        try { ctx.packageManager.getLaunchIntentForPackage(it) != null } catch (_: Exception) { false }
    }

    fun isLocked(ctx: Context): Boolean =
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    fun enter(a: Activity) {
        if (isLocked(a)) return
        if (isOwner(a)) {
            try {
                val d = dpm(a); val c = admin(a)
                d.setLockTaskPackages(c, arrayOf(a.packageName))
                if (Build.VERSION.SDK_INT >= 28) d.setLockTaskFeatures(c, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            } catch (_: Exception) {}
        }
        try { a.startLockTask() } catch (_: Exception) {}
    }

    fun exit(a: Activity) {
        if (isLocked(a)) try { a.stopLockTask() } catch (_: Exception) {}
    }

    /** Must be done before the app can be uninstalled when full lock is on. */
    @Suppress("DEPRECATION")
    fun removeFullLock(ctx: Context): Boolean = try {
        try { dpm(ctx).setLockTaskPackages(admin(ctx), arrayOf()) } catch (_: Exception) {}
        dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
        true
    } catch (_: Exception) { false }
}
