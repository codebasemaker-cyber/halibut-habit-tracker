package com.codebasemaker.habittracker.data

import com.codebasemaker.habittracker.domain.Effective
import com.codebasemaker.habittracker.domain.Mark
import com.codebasemaker.habittracker.domain.MarkState
import com.codebasemaker.habittracker.domain.Schedule
import com.codebasemaker.habittracker.domain.ScheduleType
import com.codebasemaker.habittracker.domain.ScoringSettings
import com.codebasemaker.habittracker.domain.Task
import com.codebasemaker.habittracker.domain.TaskVersion
import com.codebasemaker.habittracker.domain.Timeline
import com.codebasemaker.habittracker.domain.WeekResultOverride
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

object SettingKeys {
    // Effective-dated (setting_version)
    const val WEEK_START = "weekStart"
    const val DASH_COUNTS = "dashCounts"

    // Plain (setting)
    const val WEIGHT_UNIT = "weightUnit"            // lb | kg
    const val BACKUP_TREE_URI = "backupTreeUri"
    const val AUTO_BACKUP_DAYS = "autoBackupDays"    // 0 = off
    const val BACKUP_KEEP = "backupKeep"
    const val LAST_BACKUP_AT = "lastBackupAt"
    const val LAST_BACKUP_ERROR = "lastBackupError"
    const val EOD_ENABLED = "eodSummaryEnabled"
    const val EOD_MINUTES = "eodSummaryMinutes"
    const val BACKUP_REMINDER = "backupReminderEnabled"
    const val SEEDED = "seeded"

    /** Device-specific keys never overwritten by a restore. */
    val DEVICE_KEYS = setOf(BACKUP_TREE_URI, LAST_BACKUP_AT, LAST_BACKUP_ERROR)
}

enum class TrackerType(val label: String) {
    NUMBER("Number"), CHECKBOX("Checkbox"), TIME("Time"), TIME_RANGE("Time range"),
    RATING("Rating"), TEXT("Short text"), COMPUTED("Computed");

    /** Types whose stored values can be reinterpreted without loss. */
    fun compatibleWith(other: TrackerType): Boolean = this == other ||
        (this in NUMERIC && other in NUMERIC)

    companion object {
        private val NUMERIC = setOf(NUMBER, RATING)
    }
}

const val FN_SLEEP_DURATION = "SLEEP_DURATION"

data class TrackerDef(
    val id: String,
    val sortOrder: Int,
    val archivedOn: LocalDate?,
    val versions: List<TrackerVersionEntity>,
) {
    private val sorted = versions.sortedBy { it.effectiveFrom }
    val firstEffective: LocalDate get() = LocalDate.parse(sorted.first().effectiveFrom)
    val latest: TrackerVersionEntity get() = sorted.last()

    fun versionOn(date: LocalDate): TrackerVersionEntity? =
        sorted.lastOrNull { !LocalDate.parse(it.effectiveFrom).isAfter(date) }

    fun isActiveOn(date: LocalDate) = versionOn(date) != null && (archivedOn == null || date.isBefore(archivedOn))
}

/** Everything the UI needs, rebuilt whenever the database changes. */
data class AppSnapshot(
    val tasks: List<Task>,
    val taskVersions: Map<String, List<TaskVersionEntity>>,
    val marks: List<Mark>,
    val weekOverrides: List<WeekResultOverride>,
    val trackers: List<TrackerDef>,
    val values: List<TrackerValueEntity>,
    val scoring: ScoringSettings,
    val settings: Map<String, String>,
    val settingVersions: List<SettingVersionEntity>,
) {
    val marksByKey: Map<Pair<String, LocalDate>, Mark> by lazy { marks.associateBy { it.taskId to it.date } }
    val valuesByKey: Map<Triple<String, String, Int>, TrackerValueEntity> by lazy {
        values.associateBy { Triple(it.trackerId, it.date, it.seq) }
    }

    fun valuesAt(trackerId: String, date: LocalDate): List<TrackerValueEntity> =
        values.filter { it.trackerId == trackerId && it.date == date.toString() }.sortedBy { it.seq }

    fun setting(key: String): String? = settings[key]
    val weightUnit: String get() = settings[SettingKeys.WEIGHT_UNIT] ?: "lb"

    companion object {
        val EMPTY = AppSnapshot(emptyList(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(),
            ScoringSettings(), emptyMap(), emptyList())
    }
}

// ---- mapping ------------------------------------------------------------------------------

fun TaskVersionEntity.toDomain(): TaskVersion {
    val from = LocalDate.parse(effectiveFrom)
    val schedule = when (ScheduleType.valueOf(scheduleType)) {
        ScheduleType.DAILY -> Schedule.Daily
        ScheduleType.WEEKLY -> Schedule.Weekly
        ScheduleType.X_PER_WEEK -> Schedule.XPerWeek((timesPerWeek ?: 3).coerceIn(1, 7))
        ScheduleType.EVERY_N -> Schedule.EveryN((intervalDays ?: 7).coerceAtLeast(1), anchorDate?.let(LocalDate::parse) ?: from)
        ScheduleType.MONTHLY -> Schedule.Monthly
    }
    return TaskVersion(from, name, schedule, points, thresholdLabel)
}

fun buildTasks(tasks: List<TaskEntity>, versions: List<TaskVersionEntity>): List<Task> {
    val byTask = versions.groupBy { it.taskId }
    return tasks.mapNotNull { t ->
        val vs = byTask[t.id].orEmpty()
        if (vs.isEmpty()) return@mapNotNull null
        // Defensive: drop duplicate effective dates (keep newest edit) so a bad merge can't crash the engine.
        val unique = vs.groupBy { it.effectiveFrom }.map { (_, l) -> l.maxBy { it.updatedAt } }
        Task(t.id, unique.map { it.toDomain() }, t.archivedOn?.let(LocalDate::parse), t.sortOrder)
    }
}

fun TaskMarkEntity.toDomain() = Mark(taskId, LocalDate.parse(date), MarkState.valueOf(state), isOverride, note)
fun WeekResultOverrideEntity.toDomain() = WeekResultOverride(taskId, LocalDate.parse(dueDate), MarkState.valueOf(state))

fun scoringSettings(versions: List<SettingVersionEntity>): ScoringSettings {
    val byKey = versions.groupBy { it.key }
    val week = byKey[SettingKeys.WEEK_START].orEmpty().map {
        Effective(LocalDate.parse(it.effectiveFrom), runCatching { DayOfWeek.valueOf(it.value) }.getOrDefault(DayOfWeek.MONDAY))
    }
    val dash = byKey[SettingKeys.DASH_COUNTS].orEmpty().map { Effective(LocalDate.parse(it.effectiveFrom), it.value.toBoolean()) }
    return ScoringSettings(Timeline(week, DayOfWeek.MONDAY), Timeline(dash, true))
}

fun String.toLocalDateTimeOrNull(): LocalDateTime? = runCatching { LocalDateTime.parse(this) }.getOrNull()
