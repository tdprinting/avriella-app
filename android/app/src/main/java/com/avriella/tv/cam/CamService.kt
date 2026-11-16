package com.avriella.tv.cam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Keeps the baby-cam web server (and, when someone watches, the front camera) running. */
class CamService : Service() {
    private var cam: FrontCamera? = null
    private var server: CamServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("cam", "Baby cam", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cam")
            .setContentTitle("Baby cam is on")
            .setContentText("Parents can watch from their iPhones on home Wi-Fi")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(7, n)
        } catch (e: Exception) {
            stopSelf(); return
        }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "avriella:cam").apply { setReferenceCounted(false); acquire() }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avriella:cam").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        val c = FrontCamera(applicationContext)
        cam = c
        server = CamServer(applicationContext, c).also { it.start() }
        CamState.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        CamState.running = false
        server?.stop()
        cam?.shutdown()
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
