package com.codebasemaker.habittracker

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codebasemaker.habittracker.data.TrackerType
import com.codebasemaker.habittracker.data.TrackerValueEntity
import com.codebasemaker.habittracker.domain.MarkState
import com.codebasemaker.habittracker.domain.ScheduleType
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/** Renders the main screens with realistic data so they can be inspected (build/screenshots). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotBase {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    abstract val tag: String

    protected fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        decor.draw(canvas)
        // Overlay an open dialog / bottom sheet, which lives in its own window.
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { dv ->
            canvas.drawColor(0x66000000)
            canvas.save()
            canvas.translate(((decor.width - dv.width) / 2f), ((decor.height - dv.height) / 2f))
            dv.draw(canvas)
            canvas.restore()
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$tag-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected fun seedOctober() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val repo = app.repo
        repo.seed(true)
        val dao = app.db.dao()
        // Backdate everything to September so October has data.
        dao.taskVersionsNow().forEach { v ->
            repo.saveTaskVersion(v.copy(effectiveFrom = "2026-09-01", anchorDate = v.anchorDate?.let { "2026-09-03" }))
        }
        dao.trackerVersionsNow().forEach { repo.saveTrackerVersion(it.copy(effectiveFrom = "2026-09-01")) }
        val rnd = Random(7)
        val versions = dao.taskVersionsNow()
        var d = LocalDate.of(2026, 9, 28)
        while (!d.isAfter(LocalDate.of(2026, 11, 1))) {
            for (v in versions) {
                val type = ScheduleType.valueOf(v.scheduleType)
                val roll = rnd.nextInt(100)
                when (type) {
                    ScheduleType.DAILY -> if (d.dayOfMonth != 20) repo.setMark(v.taskId, d,
                        if (roll < 75) MarkState.DONE else if (roll < 85) MarkState.SKIP else MarkState.MISSED, false)
                    ScheduleType.X_PER_WEEK -> if (roll < 45) repo.setMark(v.taskId, d, MarkState.DONE, false)
                    ScheduleType.EVERY_N -> if (d.dayOfMonth in listOf(6, 11, 19, 30)) repo.setMark(v.taskId, d, MarkState.DONE, false)
                    ScheduleType.WEEKLY -> if (d.dayOfWeek.value == 7 && roll < 70) repo.setMark(v.taskId, d, MarkState.DONE, false)
                    ScheduleType.MONTHLY -> if (d == LocalDate.of(2026, 10, 31) && roll < 60) repo.setMark(v.taskId, d, MarkState.DONE, false)
                }
            }
            val tv = dao.trackerVersionsNow()
            for (t in tv) {
                fun v(num: Double? = null, bool: Boolean? = null, min: Int? = null, s: String? = null, e: String? = null) =
                    TrackerValueEntity(t.trackerId, d.toString(), 0, "MAIN", num, bool, null, min, s, e, null, false, "MANUAL", 0)
                when (TrackerType.valueOf(t.type)) {
                    TrackerType.NUMBER -> when (t.name) {
                        "Steps" -> repo.saveValue(v(num = 6000.0 + rnd.nextInt(6000)))
                        "Calories" -> repo.saveValue(v(num = 1700.0 + rnd.nextInt(600)))
                        "Protein (g)" -> repo.saveValue(v(num = 90.0 + rnd.nextInt(60)))
                        "Weigh-in" -> if (d.dayOfWeek.value == 1) repo.saveValue(v(num = 182.0 - d.dayOfYear / 30.0))
                    }
                    TrackerType.RATING -> repo.saveValue(v(num = 4.0 + rnd.nextInt(7)))
                    TrackerType.CHECKBOX -> repo.saveValue(v(bool = rnd.nextInt(10) > 0))
                    TrackerType.TIME -> if (rnd.nextInt(3) == 0) repo.saveValue(v(min = 15 * 60 + rnd.nextInt(4) * 30))
                    TrackerType.TIME_RANGE -> {
                        val bed = d.minusDays(1).atTime(22, 0).plusMinutes(rnd.nextLong(0, 90))
                        val wake = d.atTime(6, 30).plusMinutes(rnd.nextLong(0, 60))
                        repo.saveValue(v(s = bed.toString(), e = wake.toString()))
                    }
                    else -> {}
                }
            }
            d = d.plusDays(1)
        }
    }

    protected fun clickText(t: String) = compose.onAllNodesWithText(t).onFirst().performClick()
}

@Config(application = TestApp::class, sdk = [34], qualifiers = "w840dp-h900dp-xhdpi")
class InnerScreenShots : ScreenshotBase() {
    override val tag = "inner"

    @Test
    fun screens() {
        seedOctober()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Previous").performClick() // to October
        shot("1-month-daily")
        clickText("Weekly & Monthly"); shot("2-month-weekly")
        clickText("Values"); shot("3-month-values")
        clickText("Summary"); shot("4-month-summary")
        clickText("Daily")
        clickText("Tasks"); shot("5-tasks")
        clickText("Water"); shot("6-task-editor")
        compose.onNodeWithText("Cancel").performClick()
        clickText("Settings"); shot("7-settings")
    }
}

@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CoverScreenShots : ScreenshotBase() {
    override val tag = "cover"

    @Test
    fun screens() {
        seedOctober()
        compose.waitForIdle()
        shot("1-day")
        clickText("Month"); shot("2-month")
    }
}

@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FirstRunShot : ScreenshotBase() {
    override val tag = "firstrun"

    @Test
    fun welcome() {
        compose.waitForIdle()
        shot("1-welcome")
        clickText("Use examples")
        shot("2-day")
    }
}
