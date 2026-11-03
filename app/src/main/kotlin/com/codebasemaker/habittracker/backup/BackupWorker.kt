package com.codebasemaker.habittracker.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.codebasemaker.habittracker.App
import com.codebasemaker.habittracker.data.SettingKeys
import com.codebasemaker.habittracker.notify.Notifier
import java.util.concurrent.TimeUnit

/**
 * Automatic backup (DESIGN.md §8). Runs every ~12 h and on app start; backs up when the last
 * successful backup is at least N days old. Android may delay it while the phone is idle.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val repo = app.repo
        val days = repo.getSetting(SettingKeys.AUTO_BACKUP_DAYS)?.toIntOrNull() ?: 0
        val tree = repo.getSetting(SettingKeys.BACKUP_TREE_URI)
        val last = repo.getSetting(SettingKeys.LAST_BACKUP_AT)?.toLongOrNull()
        val now = System.currentTimeMillis()
        val dayMs = TimeUnit.DAYS.toMillis(1)

        if (days > 0 && tree != null && (last == null || now - last >= days * dayMs - TimeUnit.HOURS.toMillis(2))) {
            runCatching { runBackup(app) }
        }

        val reminder = repo.getSetting(SettingKeys.BACKUP_REMINDER)?.toBoolean() ?: true
        val lastNow = repo.getSetting(SettingKeys.LAST_BACKUP_AT)?.toLongOrNull()
        val threshold = (if (days > 0) days + 1 else 8) * dayMs
        if (reminder && (lastNow == null || now - lastNow > threshold)) {
            val prefs = app.getSharedPreferences("device", Context.MODE_PRIVATE)
            if (now - prefs.getLong("lastBackupNag", 0) > dayMs) {
                prefs.edit().putLong("lastBackupNag", now).apply()
                val ago = lastNow?.let { "${(now - it) / dayMs} days ago" } ?: "never"
                Notifier.backup(app, "Backup overdue", "Last successful backup: $ago. Open the app to back up.")
            }
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "auto-backup"
        private const val ONCE = "auto-backup-now"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(12, TimeUnit.HOURS).build(),
            )
            // Also check right away when the app starts, in case periodic work was deferred.
            wm.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().build())
        }

        /** Backs up now and records the outcome. Returns the file name or throws. */
        suspend fun runBackup(app: App): String {
            return try {
                val name = app.backup.backupToFolder()
                app.repo.setSetting(SettingKeys.LAST_BACKUP_AT, System.currentTimeMillis().toString())
                app.repo.setSetting(SettingKeys.LAST_BACKUP_ERROR, null)
                name
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                app.repo.setSetting(SettingKeys.LAST_BACKUP_ERROR, msg)
                Notifier.backup(app, "Backup failed", msg)
                throw e
            }
        }
    }
}
