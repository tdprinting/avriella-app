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
    private var mdns: MdnsResponder? = null
    private var relay: RelayClient? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("cam", "Baby cam", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cam")
            .setContentTitle("Baby cam is on")
            .setContentText("Parents can watch, control and wake her phone from their iPhones")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        notification = n
        try {
            goForeground()
        } catch (e: Exception) {
            android.util.Log.w("AvriellaCam", "couldn't go foreground", e)
            stopSelf(); return
        }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "avriella:cam").apply { setReferenceCounted(false); acquire() }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avriella:cam").apply { setReferenceCounted(false); @Suppress("WakelockTimeout") acquire() }
        val c = FrontCamera(applicationContext)
        cam = c
        server = CamServer(applicationContext, c).also { android.util.Log.i("AvriellaCam", "server on port " + it.start()) }
        mdns = MdnsResponder(applicationContext).also { it.start() }
        relay = RelayClient(applicationContext).also { it.start() }
        CamState.running = true
    }

    private var notification: Notification? = null

    /** The remote-control server always runs; the camera part only once camera access is allowed. */
    private fun goForeground() {
        val n = notification ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            try { startForeground(7, n, type) } catch (e: SecurityException) {
                // e.g. right after the phone restarts: the camera part waits until Avriella TV is opened
                startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else startForeground(7, n)
    }

    // Called again each time a screen opens: picks up camera access granted since.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { goForeground() } catch (e: Exception) { android.util.Log.w("AvriellaCam", "foreground update failed", e) }
        return START_STICKY // if Android ever stops it, it comes back by itself
    }

    override fun onDestroy() {
        CamState.running = false
        server?.stop()
        mdns?.stop()
        relay?.stop()
        cam?.shutdown()
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
