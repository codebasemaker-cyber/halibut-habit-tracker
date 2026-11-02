package com.codebasemaker.habittracker

import android.app.Application
import com.codebasemaker.habittracker.backup.BackupManager
import com.codebasemaker.habittracker.backup.BackupWorker
import com.codebasemaker.habittracker.data.AppDatabase
import com.codebasemaker.habittracker.data.Repository
import com.codebasemaker.habittracker.notify.Notifier
import com.codebasemaker.habittracker.notify.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Manual dependency container; the app is small enough not to need a DI framework. */
open class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var repo: Repository
        private set
    lateinit var backup: BackupManager
        private set
    val scope = CoroutineScope(SupervisorJob())

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.create(this)
        repo = Repository(db)
        backup = BackupManager(this, db)
        Notifier.createChannels(this)
        BackupWorker.schedule(this)
        // Keep alarms in step with task reminder times and settings.
        scope.launch {
            repo.snapshot.debounce(1500).collect { ReminderScheduler.scheduleAll(this@App, it) }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
