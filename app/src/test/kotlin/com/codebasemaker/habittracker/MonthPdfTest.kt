package com.codebasemaker.habittracker

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.codebasemaker.habittracker.data.TaskVersionEntity
import com.codebasemaker.habittracker.domain.MarkState
import com.codebasemaker.habittracker.domain.MonthGridBuilder
import com.codebasemaker.habittracker.domain.ScheduleType
import com.codebasemaker.habittracker.export.MonthPage
import com.codebasemaker.habittracker.export.MonthPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Renders the one-page month PDF; previews go to build/screenshots/pdf-*.png for inspection. */
@Config(application = TestApp::class, sdk = [34], qualifiers = "w840dp-h900dp-xhdpi")
class MonthPdfTest : ScreenshotBase() {
    override val tag = "pdf"
    private val october = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 11, 3)

    private fun render(name: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val snap = app.repo.loadSnapshot()
        val grid = MonthGridBuilder(snap.scoring, today).build(october, snap.tasks, snap.marks, snap.weekOverrides)

        // Same drawing code the PDF page uses. (PdfDocument itself is native and can't run under
        // Robolectric, so MonthPdf.write is exercised on a device.)
        val scale = 2f
        val bmp = Bitmap.createBitmap((MonthPdf.PAGE_W * scale).toInt(), (MonthPdf.PAGE_H * scale).toInt(), Bitmap.Config.ARGB_8888)
        val page = MonthPage(snap, grid, today)
        page.draw(Canvas(bmp).apply { scale(scale, scale) })
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "pdf-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name fits on one page", page.fits)
    }

    @Test
    fun october() {
        seedOctober()
        render("1-october")
    }

    @Test
    fun fileName() = assertEquals("habit-tracker-2026-10.pdf", MonthPdf.fileName(october))

    /** DESIGN §10 budget: 20 daily tasks, plus every seed tracker, still on one page. */
    @Test
    fun crowdedMonth() {
        seedOctober()
        runBlocking {
            val repo = ApplicationProvider.getApplicationContext<App>().repo
            for (i in 1..15) {
                val id = repo.createTask(
                    TaskVersionEntity("", "", "2026-09-01", "Extra habit $i", ScheduleType.DAILY.name, 1, null, null, null, null, null, null, 0),
                )
                var d = october.atDay(1)
                while (!d.isAfter(october.atEndOfMonth())) {
                    if ((d.dayOfMonth + i) % 3 != 0) repo.setMark(id, d, MarkState.DONE, false)
                    d = d.plusDays(1)
                }
            }
        }
        render("2-crowded")
    }
}
