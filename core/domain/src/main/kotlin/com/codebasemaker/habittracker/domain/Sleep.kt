package com.codebasemaker.habittracker.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class SleepKind { MAIN, NAP }

data class SleepEntry(
    val bed: LocalDateTime,
    val wake: LocalDateTime,
    val kind: SleepKind = SleepKind.MAIN,
    /** Overrides the default attribution (wake date for main sleep, start date for naps). */
    val attributedDateOverride: LocalDate? = null,
    val note: String? = null,
) {
    init {
        require(wake.isAfter(bed)) { "Wake must be after bed" }
    }

    val attributedDate: LocalDate
        get() = attributedDateOverride ?: if (kind == SleepKind.MAIN) wake.toLocalDate() else bed.toLocalDate()

    /** Real elapsed time, so a DST night is counted correctly. */
    fun duration(zone: ZoneId): Duration = Duration.between(bed.atZone(zone), wake.atZone(zone))

    companion object {
        /**
         * Builds an entry from clock times as typed on the form. If [wakeTime] is not after
         * [bedTime] the sleep crossed midnight and wake falls on [wakeDate]; bed is the day before.
         */
        fun fromTimes(wakeDate: LocalDate, bedTime: LocalTime, wakeTime: LocalTime, kind: SleepKind = SleepKind.MAIN): SleepEntry {
            val bedDate = if (wakeTime.isAfter(bedTime)) wakeDate else wakeDate.minusDays(1)
            return SleepEntry(LocalDateTime.of(bedDate, bedTime), LocalDateTime.of(wakeDate, wakeTime), kind)
        }
    }
}

/** Total Hours: main sleep only (naps excluded). Null if no main sleep recorded. */
fun totalSleepHours(entries: List<SleepEntry>, date: LocalDate, zone: ZoneId): Double? =
    entries.filter { it.kind == SleepKind.MAIN && it.attributedDate == date }
        .takeIf { it.isNotEmpty() }
        ?.sumOf { it.duration(zone).toMinutes() }
        ?.div(60.0)
