package com.codebasemaker.habittracker.domain

import java.time.LocalDate
import java.time.YearMonth

data class Score(val earned: Double, val possible: Double) {
    val percent: Double? get() = if (possible > 0) earned * 100.0 / possible else null

    operator fun plus(o: Score) = Score(earned + o.earned, possible + o.possible)

    companion object {
        val ZERO = Score(0.0, 0.0)
        fun of(instances: List<Instance>) = instances.fold(ZERO) { acc, i -> acc + Score(i.earned, i.possible) }
    }
}

data class TaskScore(val taskId: String, val toDate: Score, val month: Score)

data class MonthScore(val toDate: Score, val month: Score, val perTask: List<TaskScore>)

data class WeekColumn(val period: DateRange, val scoredInMonth: Boolean) {
    val due: LocalDate get() = period.endInclusive
}

data class ColumnHeader(val taskId: String, val name: String, val thresholdLabel: String?, val type: ScheduleType, val points: Int)

/** A task column in the daily grid. [cells] only holds days where the task applies. */
data class DayGridColumn(
    val header: ColumnHeader,
    val cells: Map<LocalDate, Cell>,
    /** X-per-week week results (badges), including carry-over weeks. */
    val weekResults: List<Instance>,
)

/** A row of the weekly or monthly panel. [cells] keyed by due date; missing = not applicable. */
data class PeriodRow(val header: ColumnHeader, val cells: Map<LocalDate, Cell>, val instances: List<Instance>)

data class MonthGrid(
    val month: YearMonth,
    val days: List<LocalDate>,
    val dayColumns: List<DayGridColumn>,
    val weekColumns: List<WeekColumn>,
    val weeklyRows: List<PeriodRow>,
    val monthlyRows: List<PeriodRow>,
    val score: MonthScore,
)

/**
 * Builds everything the month screen and the (future) PDF need. Inputs are plain data so this
 * runs the same in the app, in tests and in exports.
 */
class MonthGridBuilder(private val settings: ScoringSettings, private val today: LocalDate) {
    private val weeks = WeekCalendar(settings.weekStart)
    private val evaluator = TaskEvaluator(settings, today)

    fun build(
        month: YearMonth,
        tasks: List<Task>,
        marks: List<Mark>,
        weekOverrides: List<WeekResultOverride> = emptyList(),
    ): MonthGrid {
        val monthRange = month.toRange()
        val weekCols = weeks.weeksOverlapping(monthRange).map { WeekColumn(it, it.endInclusive in monthRange) }
        val weekDues = weekCols.map { it.due }.toSet()
        val window = DateRange(minOf(weekCols.first().period.start, monthRange.start), maxOf(weekCols.last().due, monthRange.endInclusive))

        val marksByTask = marks.groupBy { it.taskId }.mapValues { (_, l) -> l.associateBy { it.date } }
        val overridesByTask = weekOverrides.groupBy { it.taskId }.mapValues { (_, l) -> l.associateBy { it.dueDate } }
        val recorded = marks.filter { it.state != MarkState.BLANK }.map { it.date }.toSet()

        val evals = tasks.sortedBy { it.sortOrder }
            .filter { t -> window.days.any { t.isActiveOn(it) } }
            .map { t ->
                evaluator.evaluate(t, marksByTask[t.id].orEmpty(), overridesByTask[t.id].orEmpty(), recorded, window)
            }

        val dayColumns = evals.mapNotNull { e ->
            val applicable = monthRange.days.filter { d ->
                e.task.activeVersionOn(d)?.let { Schedule.isDayGrid(it.schedule.type) } == true
            }.toList()
            if (applicable.isEmpty()) return@mapNotNull null
            DayGridColumn(
                header = header(e.task, applicable.last()),
                cells = applicable.associateWith { e.cells[it] ?: Cell.EMPTY },
                weekResults = e.instances.filter { it.type == ScheduleType.X_PER_WEEK && it.due in weekDues },
            )
        }

        fun periodRows(type: ScheduleType, visible: (LocalDate) -> Boolean) = evals.mapNotNull { e ->
            val inst = e.instances.filter { it.type == type && visible(it.due) }
            if (inst.isEmpty()) return@mapNotNull null
            PeriodRow(
                header = header(e.task, inst.last().due),
                cells = inst.associate { it.due to (e.cells[it.due] ?: Cell.EMPTY) },
                instances = inst,
            )
        }

        val scored = evals.associate { e -> e.task.id to e.instances.filter { it.due in monthRange } }
        val perTask = scored.map { (id, inst) ->
            TaskScore(id, Score.of(inst.filter { it.countsToDate(today) }), Score.of(inst))
        }
        val score = MonthScore(
            toDate = perTask.fold(Score.ZERO) { a, t -> a + t.toDate },
            month = perTask.fold(Score.ZERO) { a, t -> a + t.month },
            perTask = perTask,
        )

        return MonthGrid(
            month = month,
            days = monthRange.days.toList(),
            dayColumns = dayColumns,
            weekColumns = weekCols,
            weeklyRows = periodRows(ScheduleType.WEEKLY) { it in weekDues },
            monthlyRows = periodRows(ScheduleType.MONTHLY) { it in monthRange },
            score = score,
        )
    }

    private fun header(task: Task, at: LocalDate): ColumnHeader {
        val v = task.versionOn(at)!!
        return ColumnHeader(task.id, v.name, v.thresholdLabel, v.schedule.type, v.points)
    }
}
