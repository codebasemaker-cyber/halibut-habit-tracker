package com.codebasemaker.habittracker.domain

import java.time.LocalDate
import java.time.YearMonth

enum class InstanceStatus { EARNED, PARTIAL, SKIPPED, MISSED, UNRECORDED, PENDING }

/** One time a task comes due. Scored in the month containing [due]. */
data class Instance(
    val taskId: String,
    val type: ScheduleType,
    val due: LocalDate,
    val period: DateRange,
    val possible: Double,
    val earned: Double,
    val status: InstanceStatus,
    /** X-per-week only. */
    val done: Int? = null,
    val goal: Int? = null,
    val overridden: Boolean = false,
) {
    /** Due before today, or due today and already resolved. */
    fun countsToDate(today: LocalDate): Boolean =
        due.isBefore(today) || (due == today && status != InstanceStatus.PENDING)
}

enum class CellDisplay { DONE, SKIP, MISSED, EMPTY }
enum class CellSource { NONE, MANUAL, OVERRIDE, DERIVED }

data class Cell(val display: CellDisplay, val source: CellSource) {
    companion object {
        val EMPTY = Cell(CellDisplay.EMPTY, CellSource.NONE)
    }
}

data class TaskEvaluation(
    val task: Task,
    val instances: List<Instance>,
    /** Day cells for day-grid schedules; due-date cells for weekly/monthly. Missing = empty. */
    val cells: Map<LocalDate, Cell>,
)

/**
 * Computes instances, scores and derived cell states for one task. Pure and deterministic:
 * nothing it produces is stored. See DESIGN.md §3.
 */
class TaskEvaluator(
    private val settings: ScoringSettings,
    private val today: LocalDate,
) {
    private val weeks = WeekCalendar(settings.weekStart)

    fun evaluate(
        task: Task,
        marks: Map<LocalDate, Mark>,
        weekOverrides: Map<LocalDate, WeekResultOverride>,
        recordedDays: Set<LocalDate>,
        window: DateRange,
    ): TaskEvaluation {
        val instances = mutableListOf<Instance>()
        val derived = mutableMapOf<LocalDate, MarkState>()
        val segs = segments(task)

        var i = 0
        while (i < segs.size) {
            val (version, seg) = segs[i]
            if (version.schedule is Schedule.EveryN) {
                // Group consecutive every-N segments into one rolling chain.
                var j = i
                while (j + 1 < segs.size && segs[j + 1].first.schedule is Schedule.EveryN &&
                    segs[j + 1].second.start == segs[j].second.endInclusive.plusDays(1)
                ) j++
                val run = DateRange(seg.start, segs[j].second.endInclusive)
                everyN(task, run, marks, window, instances, derived)
                i = j + 1
                continue
            }
            val clipped = seg.intersect(window)
            if (clipped != null) {
                when (val s = version.schedule) {
                    Schedule.Daily -> clipped.days.forEach { d ->
                        instances += markInstance(task, version, d, DateRange(d, d), marks[d])
                    }
                    Schedule.Weekly -> weeks.weeksDueIn(clipped).forEach { w ->
                        instances += markInstance(task, version, w.endInclusive, w, marks[w.endInclusive])
                    }
                    Schedule.Monthly -> monthsDueIn(clipped).forEach { ym ->
                        val due = ym.atEndOfMonth()
                        instances += markInstance(task, version, due, ym.toRange(), marks[due])
                    }
                    is Schedule.XPerWeek -> weeks.weeksDueIn(clipped).forEach { w ->
                        instances += xPerWeek(task, version, s, w, marks, weekOverrides[w.endInclusive], derived)
                    }
                    is Schedule.EveryN -> error("handled above")
                }
            }
            i++
        }

        val cells = mutableMapOf<LocalDate, Cell>()
        for ((date, state) in derived) {
            if (date !in window) continue
            if (date.isBefore(today) && date !in recordedDays) continue // gating, DESIGN §3.3
            cells[date] = Cell(state.toDisplay(), CellSource.DERIVED)
        }
        for ((date, mark) in marks) {
            if (date !in window) continue
            cells[date] = Cell(mark.state.toDisplay(), if (mark.isOverride) CellSource.OVERRIDE else CellSource.MANUAL)
        }
        return TaskEvaluation(task, instances.sortedBy { it.due }, cells)
    }

    // ---- schedule types -------------------------------------------------------------------

    private fun markInstance(task: Task, v: TaskVersion, due: LocalDate, period: DateRange, mark: Mark?): Instance {
        val p = v.points.toDouble()
        val (earned, status) = when (mark?.state?.takeIf { it != MarkState.BLANK }) {
            MarkState.DONE -> p to InstanceStatus.EARNED
            MarkState.SKIP -> dashValue(p, due) to InstanceStatus.SKIPPED
            MarkState.MISSED -> 0.0 to InstanceStatus.MISSED
            else -> 0.0 to if (due.isBefore(today)) InstanceStatus.UNRECORDED else InstanceStatus.PENDING
        }
        return Instance(task.id, v.schedule.type, due, period, p, earned, status)
    }

    private fun xPerWeek(
        task: Task,
        v: TaskVersion,
        s: Schedule.XPerWeek,
        week: DateRange,
        marks: Map<LocalDate, Mark>,
        override: WeekResultOverride?,
        derived: MutableMap<LocalDate, MarkState>,
    ): Instance {
        val p = v.points.toDouble()
        val due = week.endInclusive
        val days = week.days.toList()
        val done = days.count { marks[it]?.state == MarkState.DONE }
        val anyMark = days.any { marks[it]?.state.let { st -> st != null && st != MarkState.BLANK } }

        if (done >= s.times) {
            days.filter { marks[it] == null }.forEach { derived[it] = MarkState.SKIP }
        } else if (due.isBefore(today) && marks[due] == null) {
            derived[due] = MarkState.MISSED
        }

        val ov = override?.state?.takeIf { it != MarkState.BLANK }
        val (earned, status) = when (ov) {
            MarkState.DONE -> p to InstanceStatus.EARNED
            MarkState.SKIP -> dashValue(p, due) to InstanceStatus.SKIPPED
            MarkState.MISSED -> 0.0 to InstanceStatus.MISSED
            else -> {
                val e = p * minOf(done, s.times) / s.times
                e to when {
                    done >= s.times -> InstanceStatus.EARNED
                    !due.isBefore(today) -> InstanceStatus.PENDING
                    done > 0 -> InstanceStatus.PARTIAL
                    anyMark -> InstanceStatus.MISSED
                    else -> InstanceStatus.UNRECORDED
                }
            }
        }
        return Instance(task.id, ScheduleType.X_PER_WEEK, due, week, p, earned, status, done, s.times, ov != null)
    }

    /** Rolling every-N chain over [run], DESIGN §3.4 (misses accrue every N days while overdue). */
    private fun everyN(
        task: Task,
        run: DateRange,
        marks: Map<LocalDate, Mark>,
        window: DateRange,
        instances: MutableList<Instance>,
        derived: MutableMap<LocalDate, MarkState>,
    ) {
        val first = task.versionOn(run.start)!!.schedule as Schedule.EveryN
        fun nAt(d: LocalDate): Long =
            ((task.versionOn(d)?.schedule as? Schedule.EveryN) ?: first).days.toLong()

        val completions = marks.values
            .filter { it.state == MarkState.DONE || it.state == MarkState.SKIP }
            .map { it.date }
            .filter { it in run }
            .sorted()

        var due = maxOf(first.anchor, run.start)
        var lastReset = due.minusDays(first.days.toLong())
        var overdueSince: LocalDate? = null
        val limit = minOf(run.endInclusive, window.endInclusive)

        fun fill(fromExclusive: LocalDate, toExclusive: LocalDate) {
            var d = fromExclusive.plusDays(1)
            while (d.isBefore(toExclusive)) {
                if (d in run) {
                    val od = overdueSince
                    derived[d] = if (od != null && !d.isBefore(od)) MarkState.MISSED else MarkState.SKIP
                }
                d = d.plusDays(1)
            }
        }

        fun emit(d: LocalDate, earned: Double, status: InstanceStatus) {
            if (d !in window) return
            val v = task.versionOn(d)!!
            instances += Instance(
                task.id, ScheduleType.EVERY_N, d, DateRange(minOf(lastReset.plusDays(1), d), d),
                v.points.toDouble(), earned, status,
            )
        }

        while (!due.isAfter(limit)) {
            val p = task.versionOn(due)!!.points.toDouble()
            val c = completions.firstOrNull { it.isAfter(lastReset) }
            when {
                c != null && !c.isAfter(due) -> {
                    fill(lastReset, c)
                    val skip = marks[c]?.state == MarkState.SKIP
                    if (skip) emit(due, dashValue(p, due), InstanceStatus.SKIPPED)
                    else emit(due, p, InstanceStatus.EARNED)
                    overdueSince = null
                    lastReset = c
                    due = c.plusDays(nAt(c))
                }
                due.isBefore(today) -> {
                    if (overdueSince == null) overdueSince = due
                    fill(lastReset, due.plusDays(1))
                    emit(due, 0.0, InstanceStatus.MISSED)
                    due = due.plusDays(nAt(due))
                }
                else -> {
                    // Due today or later and not yet completed.
                    fill(lastReset, today)
                    if (overdueSince != null && today in run) derived[today] = MarkState.MISSED
                    emit(due, 0.0, InstanceStatus.PENDING)
                    due = due.plusDays(nAt(due))
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun dashValue(points: Double, date: LocalDate) =
        if (settings.dashCountsAsEarned.at(date)) points else 0.0

    private fun segments(task: Task): List<Pair<TaskVersion, DateRange>> {
        val vs = task.sortedVersions
        return vs.mapIndexedNotNull { i, v ->
            var end = vs.getOrNull(i + 1)?.effectiveFrom?.minusDays(1) ?: FAR_FUTURE
            task.archivedOn?.let { end = minOf(end, it.minusDays(1)) }
            if (end.isBefore(v.effectiveFrom)) null else v to DateRange(v.effectiveFrom, end)
        }
    }

    private fun monthsDueIn(range: DateRange): List<YearMonth> {
        val out = mutableListOf<YearMonth>()
        var ym = YearMonth.from(range.start)
        while (!ym.atEndOfMonth().isAfter(range.endInclusive)) {
            if (ym.atEndOfMonth() in range) out += ym
            ym = ym.plusMonths(1)
        }
        return out
    }

    companion object {
        val FAR_FUTURE: LocalDate = LocalDate.of(2999, 12, 31)
    }
}

fun DateRange.intersect(other: DateRange): DateRange? {
    val s = maxOf(start, other.start)
    val e = minOf(endInclusive, other.endInclusive)
    return if (e.isBefore(s)) null else DateRange(s, e)
}

private fun MarkState.toDisplay() = when (this) {
    MarkState.DONE -> CellDisplay.DONE
    MarkState.SKIP -> CellDisplay.SKIP
    MarkState.MISSED -> CellDisplay.MISSED
    MarkState.BLANK -> CellDisplay.EMPTY
}
