package com.codebasemaker.habittracker.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.codebasemaker.habittracker.MainActivity
import com.codebasemaker.habittracker.R

object Notifier {
    const val CH_REMINDERS = "reminders"
    const val CH_SUMMARY = "summary"
    const val CH_BACKUP = "backup"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SUMMARY, "End-of-day summary", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_BACKUP, "Backups", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun reminder(context: Context, minutes: Int, taskNames: List<String>) {
        val title = if (taskNames.size == 1) taskNames[0] else "${taskNames.size} tasks to do"
        post(context, 1000 + minutes, CH_REMINDERS, title, taskNames.joinToString(", "))
    }

    fun summary(context: Context, title: String, text: String) = post(context, 3000, CH_SUMMARY, title, text)

    fun backup(context: Context, title: String, text: String) = post(context, 4000, CH_BACKUP, title, text)
}
