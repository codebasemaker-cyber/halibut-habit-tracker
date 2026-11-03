package com.codebasemaker.habittracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Storage format notes (DESIGN.md §6):
 * - Dates are ISO strings (yyyy-MM-dd), date-times ISO local (yyyy-MM-ddTHH:mm).
 * - Every row carries updatedAt (epoch millis) so merge-restore can pick the newer edit.
 * - IDs are UUID strings so backups from different devices never collide.
 */

@Entity(tableName = "task")
data class TaskEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val archivedOn: String?,
    val updatedAt: Long,
)

@Entity(tableName = "task_version", indices = [Index("taskId")])
data class TaskVersionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val effectiveFrom: String,
    val name: String,
    val scheduleType: String,
    val points: Int,
    val thresholdLabel: String?,
    val timesPerWeek: Int?,
    val intervalDays: Int?,
    val anchorDate: String?,
    /** Minutes after midnight, or null for no reminder. */
    val reminderMinutes: Int?,
    /** Reserved: future "auto-complete from a Section 2 value" rule (JSON). */
    val autoRule: String?,
    val updatedAt: Long,
)

@Entity(tableName = "task_mark", primaryKeys = ["taskId", "date"], indices = [Index("date")])
data class TaskMarkEntity(
    val taskId: String,
    val date: String,
    val state: String,
    val isOverride: Boolean,
    val note: String?,
    val updatedAt: Long,
)

@Entity(tableName = "week_result_override", primaryKeys = ["taskId", "dueDate"])
data class WeekResultOverrideEntity(
    val taskId: String,
    val dueDate: String,
    val state: String,
    val updatedAt: Long,
)

@Entity(tableName = "tracker")
data class TrackerEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val archivedOn: String?,
    val updatedAt: Long,
)

@Entity(tableName = "tracker_version", indices = [Index("trackerId")])
data class TrackerVersionEntity(
    @PrimaryKey val id: String,
    val trackerId: String,
    val effectiveFrom: String,
    val name: String,
    val type: String,
    val unit: String?,
    val ratingMax: Int?,
    val computedFn: String?,
    val sourceTrackerId: String?,
    val updatedAt: Long,
)

@Entity(tableName = "tracker_value", primaryKeys = ["trackerId", "date", "seq"], indices = [Index("date")])
data class TrackerValueEntity(
    val trackerId: String,
    val date: String,
    /** 0 = main value; 1.. = naps (time-range trackers only). */
    val seq: Int,
    val kind: String,
    val num: Double?,
    val bool: Boolean?,
    val text: String?,
    val timeMinutes: Int?,
    val rangeStart: String?,
    val rangeEnd: String?,
    val note: String?,
    val isOverride: Boolean,
    val source: String,
    val updatedAt: Long,
)

@Entity(tableName = "setting_version", primaryKeys = ["key", "effectiveFrom"])
data class SettingVersionEntity(
    val key: String,
    val effectiveFrom: String,
    val value: String,
    val updatedAt: Long,
)

@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/** Record of a deleted row so a merge-restore doesn't bring it back. rowId = primary key values joined by '|'. */
@Entity(tableName = "tombstone", primaryKeys = ["tableName", "rowId"])
data class TombstoneEntity(
    val tableName: String,
    val rowId: String,
    val deletedAt: Long,
)
