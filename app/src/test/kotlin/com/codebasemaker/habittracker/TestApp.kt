package com.codebasemaker.habittracker

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/** App with a test WorkManager, so background scheduling works under Robolectric. */
class TestApp : App() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }
}
