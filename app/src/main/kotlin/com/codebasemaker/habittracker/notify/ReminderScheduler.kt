package com.codebasemaker.habittracker.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codebasemaker.habittracker.App
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.SettingKeys
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * One exact alarm per distinct reminder time, plus one for the end-of-day summary.
 * Alarms are re-armed after they fire, on boot, time/zone change and app update.
 */
object ReminderScheduler {
    private const val ACTION_REMIND = "com.codebasemaker.habittracker.REMIND"
    private const val ACTION_SUMMARY = "com.codebasemaker.habittracker.SUMMARY"
    private const val EXTRA_MINUTES = "minutes"
    private const val SUMMARY_CODE = 5000
    private const val PREFS = "device"
    private const val KEY_SCHEDULED = "scheduledReminderMinutes"

    fun scheduleAll(context: Context, snap: AppSnapshot) {
        val today = LocalDate.now()
        // Reminder times for today and tomorrow (a task may start tomorrow).
        val minutes = (ReminderLogic.reminderTimes(snap, today).values + ReminderLogic.reminderTimes(snap, today.plusDays(1)).values).toSet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getStringSet(KEY_SCHEDULED, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        (old - minutes).forEach { cancel(context, remindIntent(context, it)) }
        minutes.forEach { schedule(context, it, remindIntent(context, it)) }
        prefs.edit().putStringSet(KEY_SCHEDULED, minutes.map { it.toString() }.toSet()).apply()

        val eodOn = snap.setting(SettingKeys.EOD_ENABLED)?.toBoolean() ?: false
        val eodMin = snap.setting(SettingKeys.EOD_MINUTES)?.toIntOrNull() ?: (21 * 60)
        if (eodOn) schedule(context, eodMin, summaryIntent(context)) else cancel(context, summaryIntent(context))
    }

    fun canExact(context: Context): Boolean = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun schedule(context: Context, minutes: Int, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = nextOccurrence(minutes).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun cancel(context: Context, pi: PendingIntent) = context.getSystemService(AlarmManager::class.java).cancel(pi)

    fun nextOccurrence(minutes: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val t = LocalTime.of(minutes / 60, minutes % 60)
        val todayAt = now.toLocalDate().atTime(t)
        return if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
    }

    private fun remindIntent(context: Context, minutes: Int) = PendingIntent.getBroadcast(
        context, minutes,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_REMIND).putExtra(EXTRA_MINUTES, minutes),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun summaryIntent(context: Context) = PendingIntent.getBroadcast(
        context, SUMMARY_CODE, Intent(context, AlarmReceiver::class.java).setAction(ACTION_SUMMARY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext as App
            val pending = goAsync()
            app.scope.launch {
                try {
                    val snap = app.repo.loadSnapshot()
                    val today = LocalDate.now()
                    when (intent.action) {
                        ACTION_REMIND -> {
                            val m = intent.getIntExtra(EXTRA_MINUTES, -1)
                            val names = ReminderLogic.dueNames(snap, m, today)
                            if (names.isNotEmpty()) Notifier.reminder(app, m, names)
                        }
                        ACTION_SUMMARY -> {
                            val (title, text) = ReminderLogic.summary(snap, today)
                            Notifier.summary(app, title, text)
                        }
                    }
                    scheduleAll(app, snap)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /** Boot, time change, time-zone change and app update all re-arm the alarms. */
    class RescheduleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext as App
            val pending = goAsync()
            app.scope.launch {
                try { scheduleAll(app, app.repo.loadSnapshot()) } finally { pending.finish() }
            }
        }
    }
}
