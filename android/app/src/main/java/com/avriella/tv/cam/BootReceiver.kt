package com.avriella.tv.cam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.avriella.tv.App

/** Turns the iPhone connection back on after her phone restarts (or the app updates). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        android.util.Log.i("AvriellaCam", "starting after " + intent.action)
        App.startCam(ctx)
        KeeperJob.schedule(ctx)
        // after a restart: open the invisible starter so the camera is allowed too (not after app updates)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && com.avriella.tv.data.Library.settings.camEnabled) WakeStarter.launch(ctx)
        com.avriella.tv.PhotoReminder.schedule(ctx)
    }
}
