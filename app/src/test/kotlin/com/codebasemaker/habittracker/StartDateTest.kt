package com.codebasemaker.habittracker

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codebasemaker.habittracker.domain.ScheduleType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** "Starts on" in the task/tracker editors moves the first version instead of adding a new one. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class StartDateTest {
    private lateinit var app: App
    private val nov1 = LocalDate.of(2026, 11, 1)
    private val nov5 = LocalDate.of(2026, 11, 5)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.repo.seed(true) }
    }

    private suspend fun taskOf(type: ScheduleType) = app.db.dao().taskVersionsNow().first { it.scheduleType == type.name }

    @Test
    fun movingTaskStartKeepsOneVersion() = runBlocking {
        val v = taskOf(ScheduleType.DAILY).let { app.repo.saveTaskVersion(it.copy(effectiveFrom = nov1.toString())); it }
        assertTrue(app.repo.moveTaskStart(v.taskId, nov5))
        val after = app.db.dao().versionsOf(v.taskId)
        assertEquals(1, after.size)
        assertEquals(v.id, after.single().id)
        assertEquals(nov5.toString(), after.single().effectiveFrom)
        val task = app.repo.loadSnapshot().tasks.first { it.id == v.taskId }
        assertFalse(task.isActiveOn(LocalDate.of(2026, 11, 4)))
        assertTrue(task.isActiveOn(nov5))
    }

    @Test
    fun everyNFirstDueMovesWithStart() = runBlocking {
        val v = taskOf(ScheduleType.EVERY_N)
        app.repo.saveTaskVersion(v.copy(effectiveFrom = nov1.toString(), anchorDate = nov1.toString()))
        assertTrue(app.repo.moveTaskStart(v.taskId, nov5))
        assertEquals(nov5.toString(), app.db.dao().versionsOf(v.taskId).single().anchorDate)
    }

    @Test
    fun startCannotPassTheNextChange() = runBlocking {
        val v = taskOf(ScheduleType.DAILY)
        app.repo.saveTaskVersion(v.copy(effectiveFrom = nov1.toString()))
        app.repo.saveTaskVersion(v.copy(id = "later", effectiveFrom = nov5.toString(), points = 2))
        assertFalse(app.repo.moveTaskStart(v.taskId, nov5))
        assertTrue(app.repo.moveTaskStart(v.taskId, LocalDate.of(2026, 10, 20)))
        assertEquals(listOf("2026-10-20", "2026-11-05"), app.db.dao().versionsOf(v.taskId).map { it.effectiveFrom }.sorted())
    }

    @Test
    fun movingTrackerStart() = runBlocking {
        val v = app.db.dao().trackerVersionsNow().first()
        assertTrue(app.repo.moveTrackerStart(v.trackerId, nov5))
        assertEquals(listOf(nov5.toString()), app.db.dao().trackerVersionsOf(v.trackerId).map { it.effectiveFrom })
    }
}
