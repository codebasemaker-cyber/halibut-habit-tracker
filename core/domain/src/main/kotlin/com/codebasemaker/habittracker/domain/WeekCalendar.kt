package com.codebasemaker.habittracker.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Splits time into weeks using an effective-dated week-start day.
 *
 * Under a rule effective from E, weeks begin at E and on every following week-start day.
 * When the next rule begins at E2, the running week is cut short at E2 - 1 (transition week).
 */
class WeekCalendar(private val weekStart: Timeline<DayOfWeek>) {

    fun weekContaining(date: LocalDate): DateRange {
        val startDay = weekStart.at(date)
        val ruleStart = weekStart.startOf(date)
        var start = date
        while (start.dayOfWeek != startDay && start.isAfter(ruleStart)) start = start.minusDays(1)

        var end = date
        val nextRule = weekStart.nextChangeAfter(date)
        while (true) {
            val next = end.plusDays(1)
            if (next.dayOfWeek == startDay) break
            if (nextRule != null && !next.isBefore(nextRule)) break
            end = next
        }
        return DateRange(start, end)
    }

    /** Weeks whose due date (last day) falls in [range], in order. */
    fun weeksDueIn(range: DateRange): List<DateRange> {
        val result = mutableListOf<DateRange>()
        var week = weekContaining(range.start)
        while (!week.endInclusive.isAfter(range.endInclusive)) {
            if (week.endInclusive in range) result += week
            week = weekContaining(week.endInclusive.plusDays(1))
        }
        return result
    }

    /** Weeks that overlap [range], in order (includes carry-over weeks at either edge). */
    fun weeksOverlapping(range: DateRange): List<DateRange> {
        val result = mutableListOf<DateRange>()
        var week = weekContaining(range.start)
        while (!week.start.isAfter(range.endInclusive)) {
            result += week
            week = weekContaining(week.endInclusive.plusDays(1))
        }
        return result
    }
}

fun YearMonth.toRange(): DateRange = DateRange(atDay(1), atEndOfMonth())
