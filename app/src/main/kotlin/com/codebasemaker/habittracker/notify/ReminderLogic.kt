package com.codebasemaker.habittracker.notify

import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.domain.CellDisplay
import com.codebasemaker.habittracker.domain.DateRange
import com.codebasemaker.habittracker.domain.InstanceStatus
import com.codebasemaker.habittracker.domain.MarkState
import com.codebasemaker.habittracker.domain.MonthGridBuilder
import com.codebasemaker.habittracker.domain.ScheduleType
import com.codebasemaker.habittracker.domain.TaskEvaluator
import com.codebasemaker.habittracker.domain.WeekCalendar
import com.codebasemaker.habittracker.domain.Task
import java.time.LocalDate
import java.time.YearMonth

object ReminderLogic {

    /** Reminder minute-of-day for each active task today. */
    fun reminderTimes(snap: AppSnapshot, today: LocalDate): Map<String, Int> =
        snap.tasks.filter { it.isActiveOn(today) }.mapNotNull { t ->
            val v = snap.taskVersions[t.id].orEmpty().lastOrNull { !LocalDate.parse(it.effectiveFrom).isAfter(today) }
            v?.reminderMinutes?.let { t.id to it }
        }.toMap()

    /**
     * Whether [task] still needs doing on [today]:
     * - daily: not marked today
     * - X per week: weekly goal not yet met and not done today
     * - every N: due today or overdue, and not completed
     * - weekly / monthly: today is the due date and it isn't marked
     */
    fun needsDoing(snap: AppSnapshot, task: Task, today: LocalDate): Boolean {
        val v = task.activeVersionOn(today) ?: return false
        val todayMark = snap.marksByKey[task.id to today]?.state?.takeIf { it != MarkState.BLANK }
        val weeks = WeekCalendar(snap.scoring.weekStart)
        return when (v.schedule.type) {
            ScheduleType.DAILY -> todayMark == null
            ScheduleType.WEEKLY -> weeks.weekContaining(today).endInclusive == today && todayMark == null
            ScheduleType.MONTHLY -> YearMonth.from(today).atEndOfMonth() == today && todayMark == null
            ScheduleType.X_PER_WEEK, ScheduleType.EVERY_N -> {
                if (todayMark != null) return false
                val week = weeks.weekContaining(today)
                val window = DateRange(minOf(week.start, today.minusDays(1)), maxOf(week.endInclusive, today))
                val eval = TaskEvaluator(snap.scoring, today).evaluate(
                    task,
                    snap.marks.filter { it.taskId == task.id }.associateBy { it.date },
                    snap.weekOverrides.filter { it.taskId == task.id }.associateBy { it.dueDate },
                    setOf(today),
                    window,
                )
                if (v.schedule.type == ScheduleType.X_PER_WEEK) {
                    eval.instances.firstOrNull { it.due == week.endInclusive }?.status != InstanceStatus.EARNED
                } else {
                    eval.cells[today]?.display == CellDisplay.MISSED ||
                        eval.instances.any { it.due == today && it.status == InstanceStatus.PENDING }
                }
            }
        }
    }

    fun dueNames(snap: AppSnapshot, minutes: Int, today: LocalDate): List<String> {
        val times = reminderTimes(snap, today)
        return snap.tasks.filter { times[it.id] == minutes && needsDoing(snap, it, today) }
            .mapNotNull { it.versionOn(today)?.name }
    }

    fun summary(snap: AppSnapshot, today: LocalDate): Pair<String, String> {
        val open = snap.tasks.filter { needsDoing(snap, it, today) }.mapNotNull { it.versionOn(today)?.name }
        val grid = MonthGridBuilder(snap.scoring, today.plusDays(1)) // treat today as finished
            .build(YearMonth.from(today), snap.tasks, snap.marks, snap.weekOverrides)
        val pct = grid.score.toDate.percent?.let { "%.1f%%".format(it) } ?: "–"
        val title = if (open.isEmpty()) "All done today · $pct this month" else "${open.size} left today · $pct this month"
        val text = if (open.isEmpty()) "Nice work." else open.joinToString(", ")
        return title to text
    }
}
