package com.codebasemaker.habittracker

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A fresh install seeds every task and tracker from the 1st of the current month, so earlier
 * months and days come before anything exists. Stepping back there must not crash.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34], qualifiers = "w840dp-h900dp-xhdpi")
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun seedFreshInstall() = runBlocking {
        ApplicationProvider.getApplicationContext<App>().repo.seed(true)
    }

    private fun clickText(t: String) = compose.onAllNodesWithText(t).onFirst().performClick()

    private fun previous(times: Int) = repeat(times) {
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.waitForIdle()
    }

    @Test
    fun monthBeforeTasksStart() {
        seedFreshInstall()
        compose.waitForIdle()
        previous(3)
        for (tab in listOf("Weekly & Monthly", "Values", "Summary", "Daily")) {
            clickText(tab)
            compose.waitForIdle()
        }
    }

    /** The owner's steps on v1.0.0: examples, a few days of marks, a new task, a later "start date". */
    @Test
    fun dayBeforeTasksStartWithRealUse() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<App>()
            val repo = app.repo
            repo.seed(true)
            val start = java.time.YearMonth.now().atDay(1)
            val dao = app.db.dao()
            for (v in dao.taskVersionsNow()) for (i in 0L..2L) {
                repo.setMark(v.taskId, start.plusDays(i), com.codebasemaker.habittracker.domain.MarkState.DONE, false)
            }
            val id = repo.createTask(dao.taskVersionsNow().first().copy(name = "Test", effectiveFrom = start.toString()))
            val v1 = dao.versionsOf(id).single()
            repo.saveTaskVersion(v1.copy(id = java.util.UUID.randomUUID().toString(), effectiveFrom = start.plusDays(4).toString()))
        }
        compose.waitForIdle()
        clickText("Day")
        compose.waitForIdle()
        previous(40)
    }

    @Test
    fun dayBeforeTasksStart() {
        seedFreshInstall()
        compose.waitForIdle()
        clickText("Day")
        compose.waitForIdle()
        previous(40)
    }
}
