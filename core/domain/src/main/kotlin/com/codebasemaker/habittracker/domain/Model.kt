package com.codebasemaker.habittracker.domain

import java.time.DayOfWeek
import java.time.LocalDate

/** Inclusive date range. */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!endInclusive.isBefore(start)) { "Empty range $start..$endInclusive" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    val days: Sequence<LocalDate>
        get() = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(endInclusive) }

    val length: Int get() = (endInclusive.toEpochDay() - start.toEpochDay() + 1).toInt()
}

enum class ScheduleType { DAILY, WEEKLY, X_PER_WEEK, EVERY_N, MONTHLY }

sealed interface Schedule {
    val type: ScheduleType

    data object Daily : Schedule {
        override val type = ScheduleType.DAILY
    }

    data object Weekly : Schedule {
        override val type = ScheduleType.WEEKLY
    }

    data class XPerWeek(val times: Int) : Schedule {
        init {
            require(times in 1..7) { "times must be 1..7" }
        }
        override val type = ScheduleType.X_PER_WEEK
    }

    /** Rolling: next due = last completion + [days]. First due = [anchor]. */
    data class EveryN(val days: Int, val anchor: LocalDate) : Schedule {
        init {
            require(days >= 1) { "days must be >= 1" }
        }
        override val type = ScheduleType.EVERY_N
    }

    data object Monthly : Schedule {
        override val type = ScheduleType.MONTHLY
    }

    companion object {
        /** Schedules that appear as columns of the daily grid. */
        fun isDayGrid(type: ScheduleType) =
            type == ScheduleType.DAILY || type == ScheduleType.X_PER_WEEK || type == ScheduleType.EVERY_N
    }
}

data class TaskVersion(
    val effectiveFrom: LocalDate,
    val name: String,
    val schedule: Schedule,
    val points: Int,
    val thresholdLabel: String? = null,
)

data class Task(
    val id: String,
    val versions: List<TaskVersion>,
    val archivedOn: LocalDate? = null,
    val sortOrder: Int = 0,
) {
    val sortedVersions: List<TaskVersion> = versions.sortedBy { it.effectiveFrom }

    init {
        require(versions.isNotEmpty()) { "Task $id has no versions" }
        require(sortedVersions.zipWithNext().none { (a, b) -> a.effectiveFrom == b.effectiveFrom }) {
            "Task $id has two versions with the same effective date"
        }
    }

    val firstEffective: LocalDate get() = sortedVersions.first().effectiveFrom

    fun versionOn(date: LocalDate): TaskVersion? = sortedVersions.lastOrNull { !it.effectiveFrom.isAfter(date) }

    fun isActiveOn(date: LocalDate): Boolean =
        versionOn(date) != null && (archivedOn == null || date.isBefore(archivedOn))

    /** Version in effect on [date] if the task is active then. */
    fun activeVersionOn(date: LocalDate): TaskVersion? = if (isActiveOn(date)) versionOn(date) else null
}

enum class MarkState { DONE, SKIP, MISSED, BLANK }

data class Mark(
    val taskId: String,
    val date: LocalDate,
    val state: MarkState,
    val isOverride: Boolean = false,
    val note: String? = null,
)

/** Manual override of an X-per-week week result, keyed by the week's due date. */
data class WeekResultOverride(val taskId: String, val dueDate: LocalDate, val state: MarkState)

/** A value that takes effect from [from] (inclusive). */
data class Effective<T>(val from: LocalDate, val value: T)

/** Effective-dated setting. Dates before the first entry use [default]. */
class Timeline<T>(entries: List<Effective<T>>, private val default: T) {
    private val sorted = entries.sortedBy { it.from }

    fun at(date: LocalDate): T = sorted.lastOrNull { !it.from.isAfter(date) }?.value ?: default

    /** Start of the entry in effect on [date] ([BEGINNING] if the default applies). */
    fun startOf(date: LocalDate): LocalDate = sorted.lastOrNull { !it.from.isAfter(date) }?.from ?: BEGINNING

    /** First change strictly after [date], or null. */
    fun nextChangeAfter(date: LocalDate): LocalDate? = sorted.firstOrNull { it.from.isAfter(date) }?.from

    companion object {
        val BEGINNING: LocalDate = LocalDate.of(1900, 1, 1)
    }
}

data class ScoringSettings(
    val weekStart: Timeline<DayOfWeek> = Timeline(emptyList(), DayOfWeek.MONDAY),
    val dashCountsAsEarned: Timeline<Boolean> = Timeline(emptyList(), true),
)
