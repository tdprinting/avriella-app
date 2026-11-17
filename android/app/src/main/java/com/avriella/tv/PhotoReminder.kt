package com.avriella.tv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.avriella.tv.data.Baby
import com.avriella.tv.data.Library
import java.util.Calendar

/** On the 1st of every month (10 AM) a reminder pops up to take her monthly photo for the growth album. */
object PhotoReminder {
    private const val CHANNEL = "photo"

    /** Next 1st of the month at 10:00 (today, if it's the 1st and before 10). */
    fun nextTime(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.MONTH, 1)
        return c.timeInMillis
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime(), pi)
    }

    fun show(ctx: Context) {
        Baby.ensure()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Monthly photo reminder", NotificationManager.IMPORTANCE_HIGH))
        val name = Library.settings.childName
        val age = Baby.ageText()
        val open = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, BabyStatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("📸 Time for $name's monthly photo!")
            .setContentText("She's $age old. Snap a picture for her album to see how much she's changed.")
            .setStyle(Notification.BigTextStyle().bigText("She's $age old. Snap a picture for her growth album to see how much she's changed. Tap to add it."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(51, n) } catch (_: SecurityException) { /* notifications not allowed */ }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            show(ctx)
            schedule(ctx) // and next month's
        }
    }
}
