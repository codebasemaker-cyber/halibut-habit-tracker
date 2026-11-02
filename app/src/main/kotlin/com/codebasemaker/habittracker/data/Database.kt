package com.codebasemaker.habittracker.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // ---- tasks ----
    @Query("SELECT * FROM task ORDER BY sortOrder") fun tasks(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM task_version") fun taskVersions(): Flow<List<TaskVersionEntity>>
    @Query("SELECT * FROM task_mark") fun marks(): Flow<List<TaskMarkEntity>>
    @Query("SELECT * FROM week_result_override") fun weekOverrides(): Flow<List<WeekResultOverrideEntity>>

    @Query("SELECT * FROM task ORDER BY sortOrder") suspend fun tasksNow(): List<TaskEntity>
    @Query("SELECT * FROM task_version") suspend fun taskVersionsNow(): List<TaskVersionEntity>
    @Query("SELECT * FROM task_mark") suspend fun marksNow(): List<TaskMarkEntity>
    @Query("SELECT * FROM week_result_override") suspend fun weekOverridesNow(): List<WeekResultOverrideEntity>
    @Query("SELECT * FROM task_version WHERE taskId = :taskId") suspend fun versionsOf(taskId: String): List<TaskVersionEntity>
    @Query("SELECT * FROM task_mark WHERE taskId = :taskId") suspend fun marksOf(taskId: String): List<TaskMarkEntity>
    @Query("SELECT * FROM week_result_override WHERE taskId = :taskId") suspend fun weekOverridesOf(taskId: String): List<WeekResultOverrideEntity>

    @Upsert suspend fun upsertTask(t: TaskEntity)
    @Upsert suspend fun upsertTasks(t: List<TaskEntity>)
    @Upsert suspend fun upsertTaskVersion(v: TaskVersionEntity)
    @Upsert suspend fun upsertMark(m: TaskMarkEntity)
    @Upsert suspend fun upsertMarks(m: List<TaskMarkEntity>)
    @Upsert suspend fun upsertWeekOverride(o: WeekResultOverrideEntity)

    @Query("DELETE FROM task WHERE id = :id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM task_version WHERE id = :id") suspend fun deleteTaskVersion(id: String)
    @Query("DELETE FROM task_mark WHERE taskId = :taskId AND date = :date") suspend fun deleteMark(taskId: String, date: String)
    @Query("DELETE FROM week_result_override WHERE taskId = :taskId AND dueDate = :dueDate") suspend fun deleteWeekOverride(taskId: String, dueDate: String)

    // ---- trackers ----
    @Query("SELECT * FROM tracker ORDER BY sortOrder") fun trackers(): Flow<List<TrackerEntity>>
    @Query("SELECT * FROM tracker_version") fun trackerVersions(): Flow<List<TrackerVersionEntity>>
    @Query("SELECT * FROM tracker_value") fun values(): Flow<List<TrackerValueEntity>>
    @Query("SELECT * FROM tracker ORDER BY sortOrder") suspend fun trackersNow(): List<TrackerEntity>
    @Query("SELECT * FROM tracker_version") suspend fun trackerVersionsNow(): List<TrackerVersionEntity>
    @Query("SELECT * FROM tracker_value") suspend fun valuesNow(): List<TrackerValueEntity>
    @Query("SELECT * FROM tracker_version WHERE trackerId = :id") suspend fun trackerVersionsOf(id: String): List<TrackerVersionEntity>
    @Query("SELECT * FROM tracker_value WHERE trackerId = :id") suspend fun valuesOf(id: String): List<TrackerValueEntity>
    @Query("SELECT * FROM tracker_value WHERE trackerId = :id AND date = :date ORDER BY seq") suspend fun valuesAt(id: String, date: String): List<TrackerValueEntity>

    @Upsert suspend fun upsertTracker(t: TrackerEntity)
    @Upsert suspend fun upsertTrackers(t: List<TrackerEntity>)
    @Upsert suspend fun upsertTrackerVersion(v: TrackerVersionEntity)
    @Upsert suspend fun upsertValue(v: TrackerValueEntity)
    @Query("DELETE FROM tracker WHERE id = :id") suspend fun deleteTracker(id: String)
    @Query("DELETE FROM tracker_version WHERE id = :id") suspend fun deleteTrackerVersion(id: String)
    @Query("DELETE FROM tracker_value WHERE trackerId = :id AND date = :date AND seq = :seq") suspend fun deleteValue(id: String, date: String, seq: Int)

    // ---- settings ----
    @Query("SELECT * FROM setting_version") fun settingVersions(): Flow<List<SettingVersionEntity>>
    @Query("SELECT * FROM setting") fun settings(): Flow<List<SettingEntity>>
    @Query("SELECT * FROM setting_version WHERE `key` = :key") suspend fun settingVersionsOf(key: String): List<SettingVersionEntity>
    @Query("SELECT * FROM setting WHERE `key` = :key") suspend fun setting(key: String): SettingEntity?
    @Query("SELECT * FROM setting") suspend fun settingsNow(): List<SettingEntity>
    @Query("SELECT * FROM setting_version") suspend fun settingVersionsNow(): List<SettingVersionEntity>
    @Upsert suspend fun upsertSettingVersion(s: SettingVersionEntity)
    @Upsert suspend fun upsertSetting(s: SettingEntity)
    @Query("DELETE FROM setting_version WHERE `key` = :key") suspend fun clearSettingVersions(key: String)
    @Query("DELETE FROM setting_version WHERE `key` = :key AND effectiveFrom = :from") suspend fun deleteSettingVersion(key: String, from: String)

    // ---- tombstones ----
    @Upsert suspend fun upsertTombstone(t: TombstoneEntity)
}

@Database(
    entities = [
        TaskEntity::class, TaskVersionEntity::class, TaskMarkEntity::class, WeekResultOverrideEntity::class,
        TrackerEntity::class, TrackerVersionEntity::class, TrackerValueEntity::class,
        SettingVersionEntity::class, SettingEntity::class, TombstoneEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        const val SCHEMA_VERSION = 1

        /** Tables included in backups, in restore order. */
        val BACKUP_TABLES = listOf(
            "task", "task_version", "task_mark", "week_result_override",
            "tracker", "tracker_version", "tracker_value",
            "setting_version", "setting", "tombstone",
        )

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "habits.db").build()
    }
}
