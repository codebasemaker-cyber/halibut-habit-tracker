package com.codebasemaker.habittracker.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
private val OCT = YearMonth.of(2026, 10)
private val NOV = YearMonth.of(2026, 11)
private val START = d(1, 1)

private fun task(id: String, schedule: Schedule, points: Int, from: LocalDate = START, archivedOn: LocalDate? = null) =
    Task(id, listOf(TaskVersion(from, id, schedule, points)), archivedOn)

private fun mark(id: String, date: LocalDate, state: MarkState = MarkState.DONE, override: Boolean = false) =
    Mark(id, date, state, override)

private fun builder(today: LocalDate, settings: ScoringSettings = ScoringSettings()) = MonthGridBuilder(settings, today)

private fun TaskEvaluator.eval(t: Task, marks: List<Mark>, window: DateRange, overrides: List<WeekResultOverride> = emptyList()) =
    evaluate(
        t,
        marks.associateBy { it.date },
        overrides.associateBy { it.dueDate },
        marks.filter { it.state != MarkState.BLANK }.map { it.date }.toSet(),
        window,
    )

class EngineTest {

    @Nested
    inner class Weeks {
        private val cal = WeekCalendar(Timeline(emptyList(), DayOfWeek.MONDAY))

        @Test
        fun `october 2026 weeks are due 4, 11, 18, 25 and carry over to Nov 1`() {
            val weeks = cal.weeksOverlapping(OCT.toRange())
            assertEquals(listOf(d(10, 4), d(10, 11), d(10, 18), d(10, 25), d(11, 1)), weeks.map { it.endInclusive })
            assertEquals(DateRange(d(9, 28), d(10, 4)), weeks.first())
        }

        @Test
        fun `changing week start creates a short transition week`() {
            val tl = Timeline(listOf(Effective(d(11, 15), DayOfWeek.SUNDAY)), DayOfWeek.MONDAY)
            val c = WeekCalendar(tl)
            assertEquals(DateRange(d(11, 2), d(11, 8)), c.weekContaining(d(11, 5)))
            assertEquals(DateRange(d(11, 9), d(11, 14)), c.weekContaining(d(11, 10))) // Mon-Sat
            assertEquals(DateRange(d(11, 15), d(11, 21)), c.weekContaining(d(11, 15))) // Sun-Sat
            // Nov 1 (end of the Oct 26 week) is due in November too.
            assertEquals(listOf(d(11, 1), d(11, 8), d(11, 14), d(11, 21), d(11, 28)), c.weeksDueIn(NOV.toRange()).map { it.endInclusive })
        }
    }

    @Nested
    inner class Daily {
        private val water = task("water", Schedule.Daily, 1)

        @Test
        fun `done, skip and unrecorded days score as expected`() {
            val marks = listOf(
                mark("water", d(10, 1)),
                mark("water", d(10, 2), MarkState.SKIP),
                mark("water", d(10, 3), MarkState.MISSED),
            )
            val g = builder(today = d(11, 2)).build(OCT, listOf(water), marks)
            assertEquals(Score(2.0, 31.0), g.score.month)
            assertEquals(Score(2.0, 31.0), g.score.toDate)
            assertEquals(Cell.EMPTY, g.dayColumns.single().cells[d(10, 4)])
        }

        @Test
        fun `dashes can be excluded from earned points from a date forward`() {
            val settings = ScoringSettings(dashCountsAsEarned = Timeline(listOf(Effective(d(10, 2), false)), true))
            val marks = listOf(mark("water", d(10, 1), MarkState.SKIP), mark("water", d(10, 2), MarkState.SKIP))
            val g = builder(d(11, 2), settings).build(OCT, listOf(water), marks)
            assertEquals(1.0, g.score.month.earned)
        }

        @Test
        fun `to date excludes today until it is marked, month includes everything`() {
            val g = builder(today = d(10, 10)).build(OCT, listOf(water), listOf(mark("water", d(10, 1))))
            assertEquals(Score(1.0, 9.0), g.score.toDate)
            assertEquals(Score(1.0, 31.0), g.score.month)
            val g2 = builder(today = d(10, 10)).build(OCT, listOf(water), listOf(mark("water", d(10, 10))))
            assertEquals(Score(1.0, 10.0), g2.score.toDate)
        }

        @Test
        fun `effective-from and archive limit possible points`() {
            val t = task("stretch", Schedule.Daily, 1, from = d(10, 11), archivedOn = d(10, 21))
            val g = builder(d(11, 2)).build(OCT, listOf(t), emptyList())
            assertEquals(10.0, g.score.month.possible) // Oct 11..20
        }

        @Test
        fun `point change applies by due date`() {
            val t = Task(
                "read",
                listOf(
                    TaskVersion(START, "read", Schedule.Daily, 1),
                    TaskVersion(d(10, 16), "read", Schedule.Daily, 2),
                ),
            )
            val g = builder(d(11, 2)).build(OCT, listOf(t), emptyList())
            assertEquals(15.0 + 16 * 2, g.score.month.possible)
        }
    }

    @Nested
    inner class WeeklyAndMonthly {
        private val office = task("office", Schedule.Weekly, 5)
        private val car = task("car", Schedule.Monthly, 20)

        @Test
        fun `carry-over week shows in October but is scored in November`() {
            val marks = listOf(mark("office", d(10, 4)), mark("office", d(11, 1)))
            val oct = builder(d(11, 2)).build(OCT, listOf(office), marks)
            assertEquals(Score(5.0, 20.0), oct.score.month) // 4 weeks due in Oct
            val col = oct.weekColumns.last()
            assertEquals(d(11, 1), col.due)
            assertFalse(col.scoredInMonth)
            assertEquals(CellDisplay.DONE, oct.weeklyRows.single().cells[d(11, 1)]!!.display)

            val nov = builder(d(11, 2)).build(NOV, listOf(office), marks)
            val firstNovWeek = nov.weeklyRows.single().instances.first()
            assertEquals(d(11, 1), firstNovWeek.due)
            assertEquals(InstanceStatus.EARNED, firstNovWeek.status)
            assertTrue(nov.weekColumns.first().scoredInMonth)
        }

        @Test
        fun `monthly task is due on the last day`() {
            val g = builder(d(11, 2)).build(OCT, listOf(car), listOf(mark("car", d(10, 31))))
            assertEquals(Score(20.0, 20.0), g.score.month)
            assertEquals(d(10, 31), g.monthlyRows.single().instances.single().due)
        }
    }

    @Nested
    inner class XPerWeek {
        private val gym = task("gym", Schedule.XPerWeek(3), 5)

        @Test
        fun `partial credit, extra marks not scored`() {
            val marks = listOf(
                // week Oct 5-11: 2 of 3
                mark("gym", d(10, 5)), mark("gym", d(10, 7)),
                // week Oct 12-18: 4 of 3
                mark("gym", d(10, 12)), mark("gym", d(10, 13)), mark("gym", d(10, 14)), mark("gym", d(10, 15)),
            )
            val g = builder(d(11, 2)).build(OCT, listOf(gym), marks)
            val res = g.dayColumns.single().weekResults.associateBy { it.due }
            assertEquals(5.0 * 2 / 3, res[d(10, 11)]!!.earned, 1e-9)
            assertEquals(InstanceStatus.PARTIAL, res[d(10, 11)]!!.status)
            assertEquals(5.0, res[d(10, 18)]!!.earned)
            assertEquals(4, res[d(10, 18)]!!.done)
        }

        @Test
        fun `days of the straddling first week count toward it`() {
            val marks = listOf(mark("gym", d(9, 28)), mark("gym", d(9, 29)), mark("gym", d(10, 2)))
            val g = builder(d(11, 2)).build(OCT, listOf(gym), marks)
            assertEquals(InstanceStatus.EARNED, g.dayColumns.single().weekResults.first { it.due == d(10, 4) }.status)
        }

        @Test
        fun `goal met fills remaining days with derived dash`() {
            val marks = listOf(mark("gym", d(10, 12)), mark("gym", d(10, 13)), mark("gym", d(10, 14)))
            val cells = builder(d(11, 2)).build(OCT, listOf(gym), marks).dayColumns.single().cells
            // Oct 15 has no mark at all -> gated (past, unrecorded day)
            assertEquals(Cell.EMPTY, cells[d(10, 15)])
            // but it shows once the day has any Section 1 entry
            val other = mark("other", d(10, 15))
            val cells2 = builder(d(11, 2)).build(OCT, listOf(gym), marks + other).dayColumns.single().cells
            assertEquals(Cell(CellDisplay.SKIP, CellSource.DERIVED), cells2[d(10, 15)])
        }

        @Test
        fun `future days of a completed week show dash without gating`() {
            val marks = listOf(mark("gym", d(11, 2)), mark("gym", d(11, 3)), mark("gym", d(11, 4)))
            val cells = builder(d(11, 4)).build(NOV, listOf(gym), marks).dayColumns.single().cells
            assertEquals(Cell(CellDisplay.SKIP, CellSource.DERIVED), cells[d(11, 6)])
        }

        @Test
        fun `missed week shows derived X on the due day`() {
            val marks = listOf(mark("gym", d(10, 19)), mark("other", d(10, 25)))
            val cells = builder(d(11, 2)).build(OCT, listOf(gym), marks).dayColumns.single().cells
            assertEquals(Cell(CellDisplay.MISSED, CellSource.DERIVED), cells[d(10, 25)])
        }

        @Test
        fun `week result override wins`() {
            val ov = WeekResultOverride("gym", d(10, 11), MarkState.DONE)
            val g = builder(d(11, 2)).build(OCT, listOf(gym), emptyList(), listOf(ov))
            val r = g.dayColumns.single().weekResults.first { it.due == d(10, 11) }
            assertEquals(5.0, r.earned)
            assertTrue(r.overridden)
        }

        @Test
        fun `manual override on a derived cell is flagged`() {
            val marks = listOf(
                mark("gym", d(10, 12)), mark("gym", d(10, 13)), mark("gym", d(10, 14)),
                mark("gym", d(10, 16), MarkState.MISSED, override = true),
            )
            val cells = builder(d(11, 2)).build(OCT, listOf(gym), marks).dayColumns.single().cells
            assertEquals(Cell(CellDisplay.MISSED, CellSource.OVERRIDE), cells[d(10, 16)])
        }
    }

    @Nested
    inner class EveryN {
        private fun car(anchor: LocalDate = d(10, 10)) = task("tires", Schedule.EveryN(7, anchor), 3)
        private val window = DateRange(d(10, 1), d(11, 30))

        @Test
        fun `on-time completion rolls the next due date from the completion`() {
            val ev = TaskEvaluator(ScoringSettings(), d(11, 2))
            val r = ev.eval(car(), listOf(mark("tires", d(10, 8))), window)
            assertEquals(d(10, 10), r.instances[0].due)
            assertEquals(InstanceStatus.EARNED, r.instances[0].status)
            assertEquals(d(10, 15), r.instances[1].due) // Oct 8 + 7
        }

        @Test
        fun `option B - misses accrue every N days while overdue (done Oct 31)`() {
            val ev = TaskEvaluator(ScoringSettings(), d(11, 2))
            val r = ev.eval(car(), listOf(mark("tires", d(10, 31))), window)
            val byDue = r.instances.associate { it.due to it.status }
            assertEquals(InstanceStatus.MISSED, byDue[d(10, 10)])
            assertEquals(InstanceStatus.MISSED, byDue[d(10, 17)])
            assertEquals(InstanceStatus.MISSED, byDue[d(10, 24)])
            assertEquals(InstanceStatus.EARNED, byDue[d(10, 31)])
            assertEquals(InstanceStatus.PENDING, byDue[d(11, 7)])
        }

        @Test
        fun `late completion credits the next deadline and restarts the cycle (done Oct 13)`() {
            val ev = TaskEvaluator(ScoringSettings(), d(11, 2))
            val r = ev.eval(car(), listOf(mark("tires", d(10, 13))), window)
            val dues = r.instances.map { it.due to it.status }
            assertEquals(d(10, 10) to InstanceStatus.MISSED, dues[0])
            assertEquals(d(10, 17) to InstanceStatus.EARNED, dues[1])
            assertEquals(d(10, 20), dues[2].first)
        }

        @Test
        fun `overdue days show X until completed, earlier cycle days show dash`() {
            val marks = listOf(mark("tires", d(10, 13))) +
                (1..31).map { mark("other", d(10, it)) } // every day recorded, so no gating
            val cells = TaskEvaluator(ScoringSettings(), d(11, 2)).evaluate(
                car(), mapOf(d(10, 13) to marks[0]), emptyMap(), marks.map { it.date }.toSet(), window,
            ).cells
            assertEquals(CellDisplay.SKIP, cells[d(10, 9)]!!.display)
            assertEquals(CellDisplay.MISSED, cells[d(10, 10)]!!.display)
            assertEquals(CellDisplay.MISSED, cells[d(10, 12)]!!.display)
            assertEquals(CellSource.MANUAL, cells[d(10, 13)]!!.source)
            assertEquals(CellDisplay.SKIP, cells[d(10, 14)]!!.display)
            assertNull(cells[d(10, 2)]) // before the first cycle (Oct 3..9)
        }

        @Test
        fun `currently overdue shows X today`() {
            val ev = TaskEvaluator(ScoringSettings(), d(10, 12))
            val cells = ev.eval(car(), emptyList(), window).cells
            assertEquals(Cell(CellDisplay.MISSED, CellSource.DERIVED), cells[d(10, 12)])
        }

        @Test
        fun `skip resets the clock`() {
            val ev = TaskEvaluator(ScoringSettings(), d(11, 2))
            val r = ev.eval(car(), listOf(mark("tires", d(10, 10), MarkState.SKIP)), window)
            assertEquals(InstanceStatus.SKIPPED, r.instances[0].status)
            assertEquals(d(10, 17), r.instances[1].due)
        }

        @Test
        fun `changing N keeps the chain`() {
            val t = Task(
                "tires",
                listOf(
                    TaskVersion(START, "tires", Schedule.EveryN(7, d(10, 10)), 3),
                    TaskVersion(d(10, 15), "tires", Schedule.EveryN(10, d(10, 15)), 3),
                ),
            )
            val ev = TaskEvaluator(ScoringSettings(), d(11, 30))
            val r = ev.eval(t, listOf(mark("tires", d(10, 10)), mark("tires", d(10, 17))), window)
            // Oct 10 done -> next due Oct 17 (completion before the change uses N=7)
            // Oct 17 done -> next due Oct 27 (N=10)
            assertEquals(listOf(d(10, 10), d(10, 17), d(10, 27)), r.instances.take(3).map { it.due })
        }

        @Test
        fun `instances are scored in the month of their due date`() {
            val g = builder(d(11, 2)).build(OCT, listOf(car()), listOf(mark("tires", d(10, 31))))
            // Oct 10, 17, 24 missed, Oct 31 earned
            assertEquals(Score(3.0, 12.0), g.score.month)
        }
    }

    @Nested
    inner class SleepMath {
        private val chicago = ZoneId.of("America/Chicago")

        @Test
        fun `span past midnight is attributed to the wake date`() {
            val e = SleepEntry.fromTimes(d(10, 5), LocalTime.of(22, 30), LocalTime.of(7, 0))
            assertEquals(d(10, 4), e.bed.toLocalDate())
            assertEquals(d(10, 5), e.attributedDate)
            assertEquals(8.5, totalSleepHours(listOf(e), d(10, 5), chicago))
        }

        @Test
        fun `DST fall-back night on Nov 1 2026 is an hour longer`() {
            val e = SleepEntry.fromTimes(d(11, 1), LocalTime.of(22, 30), LocalTime.of(7, 0))
            assertEquals(9.5, totalSleepHours(listOf(e), d(11, 1), chicago))
        }

        @Test
        fun `naps are excluded from total hours`() {
            val main = SleepEntry.fromTimes(d(10, 5), LocalTime.of(23, 0), LocalTime.of(7, 0))
            val nap = SleepEntry.fromTimes(d(10, 5), LocalTime.of(14, 0), LocalTime.of(15, 0), SleepKind.NAP)
            assertEquals(d(10, 5), nap.attributedDate)
            assertEquals(8.0, totalSleepHours(listOf(main, nap), d(10, 5), chicago))
        }
    }
}
