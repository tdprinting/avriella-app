package com.avriella.tv.cam

import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.avriella.tv.App

/**
 * Keeps the iPhone connection up no matter what: after a restart, after Android stops the app,
 * after an update. Every 15 minutes it checks and starts the connection again if it isn't running.
 */
class KeeperJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (!CamState.running) {
            Log.i("AvriellaCam", "watchdog: connection was down, starting it again")
            App.startCam(applicationContext)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?) = true

    companion object {
        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(77) != null) return
                js.schedule(
                    JobInfo.Builder(77, ComponentName(ctx, KeeperJob::class.java))
                        .setPeriodic(15 * 60_000L)
                        .setPersisted(true) // survives restarts
                        .build()
                )
            } catch (e: Exception) { Log.w("AvriellaCam", "couldn't schedule watchdog", e) }
        }
    }
}

/**
 * An invisible screen opened for a moment after her phone restarts. Android only lets the baby cam
 * use the camera if the app was on screen when it started, so this gives it that moment, then steps aside.
 */
class WakeStarter : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.startCam(this)
        Handler(Looper.getMainLooper()).postDelayed({ finish(); overridePendingTransition(0, 0) }, 2500)
    }

    companion object {
        fun launch(ctx: Context) {
            try {
                ctx.startActivity(Intent(ctx, WakeStarter::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (e: Exception) { Log.w("AvriellaCam", "couldn't open the starter screen", e) }
        }
    }
}
