package com.codebasemaker.habittracker.ui

import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.FN_SLEEP_DURATION
import com.codebasemaker.habittracker.data.TrackerDef
import com.codebasemaker.habittracker.data.TrackerType
import com.codebasemaker.habittracker.data.TrackerValueEntity
import com.codebasemaker.habittracker.data.TrackerVersionEntity
import com.codebasemaker.habittracker.data.toLocalDateTimeOrNull
import com.codebasemaker.habittracker.domain.DateRange
import com.codebasemaker.habittracker.domain.ScheduleType
import com.codebasemaker.habittracker.domain.SleepEntry
import com.codebasemaker.habittracker.domain.SleepKind
import com.codebasemaker.habittracker.domain.totalSleepHours
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

const val LB_PER_KG = 2.20462262185

object Fmt {
    private val shortDate = DateTimeFormatter.ofPattern("MMM d")
    private val longDate = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy")

    fun time(minutes: Int): String = time(LocalTime.of(minutes / 60 % 24, minutes % 60))
    fun time(t: LocalTime): String {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        val m = if (t.minute == 0) "" else ":%02d".format(t.minute)
        return "$h$m${if (t.hour < 12) "am" else "pm"}"
    }
    fun timeFull(t: LocalTime): String {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "$h:%02d%s".format(t.minute, if (t.hour < 12) "am" else "pm")
    }
    fun range(start: LocalDateTime, end: LocalDateTime) = "${timeFull(start.toLocalTime())} – ${timeFull(end.toLocalTime())}"
    fun short(d: LocalDate): String = d.format(shortDate)
    fun long(d: LocalDate): String = d.format(longDate)
    fun month(ym: java.time.YearMonth): String = ym.atDay(1).format(monthYear)
    fun dow2(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)
    fun num(v: Double, decimals: Int = 1): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else "%.${decimals}f".format(v)
    fun hours(h: Double): String {
        val totalMin = Math.round(h * 60)
        return "${totalMin / 60}h%02d".format(totalMin % 60)
    }
    fun pct(p: Double?): String = p?.let { "%.1f%%".format(it) } ?: "–"
    fun pts(v: Double): String = num(v, 1)

    fun type(t: ScheduleType): String = when (t) {
        ScheduleType.DAILY -> "Daily"
        ScheduleType.WEEKLY -> "Weekly"
        ScheduleType.X_PER_WEEK -> "X per week"
        ScheduleType.EVERY_N -> "Every N days"
        ScheduleType.MONTHLY -> "Monthly"
    }

    fun defaultPoints(t: ScheduleType) = when (t) {
        ScheduleType.DAILY -> 1
        ScheduleType.WEEKLY, ScheduleType.X_PER_WEEK -> 5
        ScheduleType.EVERY_N -> 3
        ScheduleType.MONTHLY -> 20
    }
}

/** Section 2 value helpers. */
object Values {
    fun zone(): ZoneId = ZoneId.systemDefault()

    fun isWeight(v: TrackerVersionEntity) = v.unit.equals("lb", ignoreCase = true)

    fun sleepEntries(snap: AppSnapshot, trackerId: String): List<SleepEntry> =
        snap.values.filter { it.trackerId == trackerId }.mapNotNull { it.toSleep() }

    fun TrackerValueEntity.toSleep(): SleepEntry? {
        val s = rangeStart?.toLocalDateTimeOrNull() ?: return null
        val e = rangeEnd?.toLocalDateTimeOrNull() ?: return null
        if (!e.isAfter(s)) return null
        return SleepEntry(s, e, if (kind == "NAP") SleepKind.NAP else SleepKind.MAIN, LocalDate.parse(date), note)
    }

    /** Computed value (before any override) for [date]. */
    fun computed(snap: AppSnapshot, v: TrackerVersionEntity, date: LocalDate): Double? = when (v.computedFn) {
        FN_SLEEP_DURATION -> v.sourceTrackerId?.let { src ->
            val main = snap.values.filter { it.trackerId == src && it.date == date.toString() && it.seq == 0 }.mapNotNull { it.toSleep() }
            totalSleepHours(main, date, zone())
        }
        else -> null
    }

    /** Numeric value used for statistics, or null. Weight in stored unit (lb). */
    fun numeric(snap: AppSnapshot, tracker: TrackerDef, date: LocalDate): Double? {
        val v = tracker.versionOn(date) ?: return null
        val main = snap.valuesByKey[Triple(tracker.id, date.toString(), 0)]
        return when (TrackerType.valueOf(v.type)) {
            TrackerType.NUMBER, TrackerType.RATING -> main?.num
            TrackerType.CHECKBOX -> main?.bool?.let { if (it) 1.0 else 0.0 }
            TrackerType.TIME -> main?.timeMinutes?.toDouble()
            TrackerType.TIME_RANGE -> main?.toSleep()?.duration(zone())?.toMinutes()?.div(60.0)
            TrackerType.COMPUTED -> if (main?.isOverride == true) main.num else computed(snap, v, date)
            TrackerType.TEXT -> null
        }
    }

    /** Short text for a grid cell. */
    fun display(snap: AppSnapshot, tracker: TrackerDef, date: LocalDate): String {
        val v = tracker.versionOn(date) ?: return ""
        val all = snap.valuesAt(tracker.id, date)
        val main = all.firstOrNull { it.seq == 0 }
        return when (TrackerType.valueOf(v.type)) {
            TrackerType.NUMBER -> main?.num?.let { n ->
                if (isWeight(v) && snap.weightUnit == "kg") Fmt.num(n / LB_PER_KG) else Fmt.num(n)
            } ?: ""
            TrackerType.RATING -> main?.num?.let { Fmt.num(it) } ?: ""
            TrackerType.CHECKBOX -> when (main?.bool) { true -> "✓"; false -> "✗"; null -> "" }
            TrackerType.TIME -> main?.timeMinutes?.let { Fmt.time(it) } ?: ""
            TrackerType.TEXT -> main?.text ?: ""
            TrackerType.TIME_RANGE -> {
                val s = main?.toSleep()
                val naps = all.count { it.seq > 0 }
                (s?.let { Fmt.range(it.bed, it.wake) } ?: "") + (if (naps > 0) " +${naps} nap" + (if (naps > 1) "s" else "") else "")
            }
            TrackerType.COMPUTED -> {
                if (main?.isOverride == true && main.num != null) Fmt.hours(main.num) + "*"
                else computed(snap, v, date)?.let { Fmt.hours(it) } ?: ""
            }
        }.trim()
    }

    fun unitLabel(snap: AppSnapshot, v: TrackerVersionEntity): String? =
        if (isWeight(v)) snap.weightUnit else v.unit

    /** One-line statistic for [tracker] over [range], as on the Summary tab and in the month PDF. */
    fun monthSummary(snap: AppSnapshot, tracker: TrackerDef, range: DateRange): String {
        val t = tracker
        val type = TrackerType.valueOf(t.latest.type)
        val nums = range.days.mapNotNull { numeric(snap, t, it) }.toList()
        return when {
            type == TrackerType.TEXT -> "${range.days.count { snap.valuesByKey[Triple(t.id, it.toString(), 0)] != null }} entries"
            nums.isEmpty() -> "no entries"
            type == TrackerType.CHECKBOX -> "${nums.count { it > 0 }} of ${nums.size} days"
            type == TrackerType.TIME -> "avg ${Fmt.time(nums.average().toInt())} · ${nums.size} days"
            type == TrackerType.TIME_RANGE || type == TrackerType.COMPUTED ->
                "avg ${Fmt.hours(nums.average())} · min ${Fmt.hours(nums.min())} · max ${Fmt.hours(nums.max())}"
            isWeight(t.latest) -> {
                val conv = if (snap.weightUnit == "kg") nums.map { it / LB_PER_KG } else nums
                "latest ${Fmt.num(conv.last())} ${snap.weightUnit} · change ${Fmt.num(conv.last() - conv.first())}"
            }
            else -> "avg ${Fmt.num(nums.average())} · min ${Fmt.num(nums.min())} · max ${Fmt.num(nums.max())} · ${nums.size} days"
        }
    }
}
