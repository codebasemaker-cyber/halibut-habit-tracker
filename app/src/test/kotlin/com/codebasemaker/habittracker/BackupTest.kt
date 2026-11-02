package com.codebasemaker.habittracker

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codebasemaker.habittracker.backup.Csv
import com.codebasemaker.habittracker.backup.RestoreMode
import com.codebasemaker.habittracker.data.SettingKeys
import com.codebasemaker.habittracker.data.TrackerValueEntity
import com.codebasemaker.habittracker.domain.MarkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class BackupTest {
    private lateinit var app: App
    private val day = LocalDate.of(2026, 10, 5)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private suspend fun zip(): ByteArray = ByteArrayOutputStream().also { app.backup.writeZip(it) }.toByteArray()

    /** Every table as sorted CSV lines, for equality checks. */
    private suspend fun dump(): Map<String, List<String>> =
        app.backup.read(ByteArrayInputStream(zip())).tables.mapValues { (_, t) ->
            t.rows.map { Csv.writeRow(it) }.sorted()
        }

    private suspend fun fill() {
        val repo = app.repo
        repo.seed(true)
        val tasks = app.db.dao().tasksNow()
        repo.setMark(tasks[0].id, day, MarkState.DONE, false, "note, with \"quotes\"\nand a newline")
        repo.setMark(tasks[1].id, day, MarkState.SKIP, true)
        repo.setMark(tasks[2].id, day, MarkState.BLANK, true)
        repo.setWeekOverride(tasks[5].id, LocalDate.of(2026, 10, 11), MarkState.DONE)
        val trackers = app.db.dao().trackersNow()
        val sleep = app.db.dao().trackerVersionsNow().first { it.name == "Sleep" }.trackerId
        repo.saveValue(TrackerValueEntity(sleep, day.toString(), 0, "MAIN", null, null, null, null,
            "2026-10-04T22:30", "2026-10-05T07:00", "woke once", false, "MANUAL", 0))
        repo.saveValue(TrackerValueEntity(sleep, day.toString(), 1, "NAP", null, null, null, null,
            "2026-10-05T14:00", "2026-10-05T14:40", null, false, "MANUAL", 0))
        repo.saveValue(TrackerValueEntity(trackers[0].id, day.toString(), 0, "MAIN", null, null, "", 960, null, null, null, false, "MANUAL", 0))
        repo.saveValue(TrackerValueEntity(trackers[2].id, day.toString(), 0, "MAIN", 1834.5, true, null, null, null, null, null, false, "MANUAL", 0))
        repo.setVersionedSetting(SettingKeys.DASH_COUNTS, "false", LocalDate.of(2026, 10, 15))
        repo.setVersionedSetting(SettingKeys.WEEK_START, "SUNDAY", LocalDate.of(2026, 11, 15))
        repo.setSetting(SettingKeys.WEIGHT_UNIT, "kg")
        repo.deleteTaskPermanently(tasks.last().id)
    }

    @Test
    fun `replace restore round-trips every table losslessly`() = runBlocking {
        fill()
        val before = dump()
        val bytes = zip()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.db.clearAllTables() }
        assertTrue(app.db.dao().tasksNow().isEmpty())

        val content = app.backup.read(ByteArrayInputStream(bytes))
        app.backup.restore(content, RestoreMode.REPLACE)
        assertEquals(before, dump())

        // Spot-check tricky values survived: null vs empty string, quotes/newlines in notes.
        val mark = app.db.dao().marksNow().first { it.note != null }
        assertEquals("note, with \"quotes\"\nand a newline", mark.note)
        val v = app.db.dao().valuesNow().first { it.timeMinutes == 960 }
        assertEquals("", v.text)
        assertNull(v.num)
    }

    @Test
    fun `merge keeps local deletions and newer local edits, adds missing rows`() = runBlocking {
        fill()
        val backup = app.backup.read(ByteArrayInputStream(zip()))
        val task0 = app.db.dao().tasksNow().first().id

        Thread.sleep(5)
        app.repo.setMark(task0, day, null, false)                      // delete locally (tombstone)
        val v = app.db.dao().versionsOf(task0).first()
        app.repo.saveTaskVersion(v.copy(name = "Renamed later"))       // newer local edit
        app.repo.setMark(task0, day.plusDays(1), MarkState.DONE, false) // local-only row

        app.backup.restore(backup, RestoreMode.MERGE)

        assertNull(app.db.dao().marksOf(task0).firstOrNull { it.date == day.toString() })
        assertEquals("Renamed later", app.db.dao().versionsOf(task0).first().name)
        assertNotNull(app.db.dao().marksOf(task0).firstOrNull { it.date == day.plusDays(1).toString() })
    }

    @Test
    fun `merge into an empty device brings everything in`() = runBlocking {
        fill()
        val before = dump()
        val backup = app.backup.read(ByteArrayInputStream(zip()))
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.db.clearAllTables() }
        app.backup.restore(backup, RestoreMode.MERGE)
        assertEquals(before, dump())
    }

    @Test
    fun `preview counts rows`() = runBlocking {
        fill()
        val backup = app.backup.read(ByteArrayInputStream(zip()))
        val p = app.backup.preview(backup, RestoreMode.MERGE)
        assertTrue(p.tables.all { it.adds == 0 && it.updates == 0 && it.deletes == 0 })
    }
}
