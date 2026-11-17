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
        com.avriella.tv.PhotoReminder.schedule(ctx)
    }
}
