package com.codebasemaker.habittracker.data

import androidx.room.withTransaction
import com.codebasemaker.habittracker.domain.MarkState
import com.codebasemaker.habittracker.domain.Schedule
import com.codebasemaker.habittracker.domain.ScheduleType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class Repository(private val db: AppDatabase) {
    private val dao = db.dao()
    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    val snapshot: Flow<AppSnapshot> = combine(
        listOf(
            dao.tasks(), dao.taskVersions(), dao.marks(), dao.weekOverrides(),
            dao.trackers(), dao.trackerVersions(), dao.values(), dao.settingVersions(), dao.settings(),
        ),
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val tasks = arr[0] as List<TaskEntity>
        @Suppress("UNCHECKED_CAST")
        val tv = arr[1] as List<TaskVersionEntity>
        @Suppress("UNCHECKED_CAST")
        val marks = arr[2] as List<TaskMarkEntity>
        @Suppress("UNCHECKED_CAST")
        val wo = arr[3] as List<WeekResultOverrideEntity>
        @Suppress("UNCHECKED_CAST")
        val trackers = arr[4] as List<TrackerEntity>
        @Suppress("UNCHECKED_CAST")
        val trv = arr[5] as List<TrackerVersionEntity>
        @Suppress("UNCHECKED_CAST")
        val values = arr[6] as List<TrackerValueEntity>
        @Suppress("UNCHECKED_CAST")
        val sv = arr[7] as List<SettingVersionEntity>
        @Suppress("UNCHECKED_CAST")
        val st = arr[8] as List<SettingEntity>
        buildSnapshot(tasks, tv, marks, wo, trackers, trv, values, sv, st)
    }

    suspend fun loadSnapshot(): AppSnapshot = buildSnapshot(
        dao.tasksNow(), dao.taskVersionsNow(), dao.marksNow(), dao.weekOverridesNow(),
        dao.trackersNow(), dao.trackerVersionsNow(), dao.valuesNow(), dao.settingVersionsNow(), dao.settingsNow(),
    )

    private fun buildSnapshot(
        tasks: List<TaskEntity>, tv: List<TaskVersionEntity>, marks: List<TaskMarkEntity>,
        wo: List<WeekResultOverrideEntity>, trackers: List<TrackerEntity>, trv: List<TrackerVersionEntity>,
        values: List<TrackerValueEntity>, sv: List<SettingVersionEntity>, st: List<SettingEntity>,
    ): AppSnapshot {
        val trvBy = trv.groupBy { it.trackerId }
        return AppSnapshot(
            tasks = buildTasks(tasks, tv),
            taskVersions = tv.groupBy { it.taskId }.mapValues { (_, l) -> l.sortedBy { it.effectiveFrom } },
            marks = marks.mapNotNull { runCatching { it.toDomain() }.getOrNull() },
            weekOverrides = wo.mapNotNull { runCatching { it.toDomain() }.getOrNull() },
            trackers = trackers.mapNotNull { t ->
                val vs = trvBy[t.id].orEmpty()
                if (vs.isEmpty()) null else TrackerDef(t.id, t.sortOrder, t.archivedOn?.let(LocalDate::parse), vs)
            },
            values = values,
            scoring = scoringSettings(sv),
            settings = st.associate { it.key to it.value },
            settingVersions = sv,
        )
    }

    // ---- marks ---------------------------------------------------------------------------

    /** state == null removes the mark (cell becomes unrecorded / derived again). */
    suspend fun setMark(taskId: String, date: LocalDate, state: MarkState?, isOverride: Boolean, note: String? = null) {
        if (state == null && note.isNullOrBlank()) {
            dao.deleteMark(taskId, date.toString())
            dao.upsertTombstone(TombstoneEntity("task_mark", "$taskId|$date", now()))
        } else {
            dao.upsertMark(TaskMarkEntity(taskId, date.toString(), (state ?: MarkState.BLANK).name, isOverride, note?.ifBlank { null }, now()))
        }
    }

    suspend fun setWeekOverride(taskId: String, due: LocalDate, state: MarkState?) {
        if (state == null) {
            dao.deleteWeekOverride(taskId, due.toString())
            dao.upsertTombstone(TombstoneEntity("week_result_override", "$taskId|$due", now()))
        } else {
            dao.upsertWeekOverride(WeekResultOverrideEntity(taskId, due.toString(), state.name, now()))
        }
    }

    /** Turns the remaining blank daily tasks of [date] into manual X. */
    suspend fun closeOutDay(date: LocalDate, snap: AppSnapshot) {
        val t = now()
        val list = snap.tasks.filter { it.activeVersionOn(date)?.schedule?.type == ScheduleType.DAILY }
            .filter { snap.marksByKey[it.id to date] == null }
            .map { TaskMarkEntity(it.id, date.toString(), MarkState.MISSED.name, false, null, t) }
        dao.upsertMarks(list)
    }

    // ---- tasks ---------------------------------------------------------------------------

    suspend fun createTask(version: TaskVersionEntity): String = db.withTransaction {
        val id = newId()
        val order = (dao.tasksNow().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsertTask(TaskEntity(id, order, null, now()))
        dao.upsertTaskVersion(version.copy(id = newId(), taskId = id, updatedAt = now()))
        id
    }

    /**
     * Saves [version]. If another version of the task already starts on the same date it is
     * replaced, so there is never more than one version per effective date.
     */
    suspend fun saveTaskVersion(version: TaskVersionEntity) = db.withTransaction {
        val clash = dao.versionsOf(version.taskId).filter { it.effectiveFrom == version.effectiveFrom && it.id != version.id }
        clash.forEach { deleteTaskVersionRow(it.id) }
        dao.upsertTaskVersion(version.copy(updatedAt = now()))
        touchTask(version.taskId)
    }

    /**
     * Moves the task's start (its first version's effective date). An every-N first due date that
     * matched the old start moves with it. Returns false if [start] is not before the next version.
     */
    suspend fun moveTaskStart(taskId: String, start: LocalDate): Boolean = db.withTransaction {
        val vs = dao.versionsOf(taskId).sortedBy { it.effectiveFrom }
        val first = vs.firstOrNull() ?: return@withTransaction false
        if (vs.getOrNull(1)?.let { !start.isBefore(LocalDate.parse(it.effectiveFrom)) } == true) return@withTransaction false
        val anchor = if (first.anchorDate == first.effectiveFrom) start.toString() else first.anchorDate
        dao.upsertTaskVersion(first.copy(effectiveFrom = start.toString(), anchorDate = anchor, updatedAt = now()))
        touchTask(taskId)
        true
    }

    suspend fun deleteTaskVersion(id: String, taskId: String) = db.withTransaction {
        if (dao.versionsOf(taskId).size > 1) deleteTaskVersionRow(id)
        touchTask(taskId)
    }

    private suspend fun deleteTaskVersionRow(id: String) {
        dao.deleteTaskVersion(id)
        dao.upsertTombstone(TombstoneEntity("task_version", id, now()))
    }

    private suspend fun touchTask(id: String) {
        dao.tasksNow().firstOrNull { it.id == id }?.let { dao.upsertTask(it.copy(updatedAt = now())) }
    }

    suspend fun setTaskArchived(id: String, on: LocalDate?) {
        dao.tasksNow().firstOrNull { it.id == id }?.let { dao.upsertTask(it.copy(archivedOn = on?.toString(), updatedAt = now())) }
    }

    suspend fun deleteTaskPermanently(id: String) = db.withTransaction {
        val t = now()
        dao.versionsOf(id).forEach { dao.deleteTaskVersion(it.id); dao.upsertTombstone(TombstoneEntity("task_version", it.id, t)) }
        dao.marksOf(id).forEach { dao.deleteMark(id, it.date); dao.upsertTombstone(TombstoneEntity("task_mark", "$id|${it.date}", t)) }
        dao.weekOverridesOf(id).forEach {
            dao.deleteWeekOverride(id, it.dueDate); dao.upsertTombstone(TombstoneEntity("week_result_override", "$id|${it.dueDate}", t))
        }
        dao.deleteTask(id)
        dao.upsertTombstone(TombstoneEntity("task", id, t))
    }

    suspend fun moveTask(id: String, delta: Int) = db.withTransaction {
        val list = dao.tasksNow().sortedBy { it.sortOrder }.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@withTransaction
        list.add(j, list.removeAt(i))
        dao.upsertTasks(list.mapIndexed { idx, t -> t.copy(sortOrder = idx, updatedAt = now()) })
    }

    // ---- trackers ------------------------------------------------------------------------

    suspend fun createTracker(version: TrackerVersionEntity): String = db.withTransaction {
        val id = newId()
        val order = (dao.trackersNow().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsertTracker(TrackerEntity(id, order, null, now()))
        dao.upsertTrackerVersion(version.copy(id = newId(), trackerId = id, updatedAt = now()))
        id
    }

    suspend fun saveTrackerVersion(version: TrackerVersionEntity) = db.withTransaction {
        dao.trackerVersionsOf(version.trackerId)
            .filter { it.effectiveFrom == version.effectiveFrom && it.id != version.id }
            .forEach { dao.deleteTrackerVersion(it.id); dao.upsertTombstone(TombstoneEntity("tracker_version", it.id, now())) }
        dao.upsertTrackerVersion(version.copy(updatedAt = now()))
    }

    /** Moves the tracker's start (its first version's effective date); false if not before the next version. */
    suspend fun moveTrackerStart(trackerId: String, start: LocalDate): Boolean = db.withTransaction {
        val vs = dao.trackerVersionsOf(trackerId).sortedBy { it.effectiveFrom }
        val first = vs.firstOrNull() ?: return@withTransaction false
        if (vs.getOrNull(1)?.let { !start.isBefore(LocalDate.parse(it.effectiveFrom)) } == true) return@withTransaction false
        dao.upsertTrackerVersion(first.copy(effectiveFrom = start.toString(), updatedAt = now()))
        true
    }

    suspend fun deleteTrackerVersion(id: String, trackerId: String) = db.withTransaction {
        if (dao.trackerVersionsOf(trackerId).size > 1) {
            dao.deleteTrackerVersion(id)
            dao.upsertTombstone(TombstoneEntity("tracker_version", id, now()))
        }
    }

    suspend fun setTrackerArchived(id: String, on: LocalDate?) {
        dao.trackersNow().firstOrNull { it.id == id }?.let { dao.upsertTracker(it.copy(archivedOn = on?.toString(), updatedAt = now())) }
    }

    suspend fun deleteTrackerPermanently(id: String) = db.withTransaction {
        val t = now()
        dao.trackerVersionsOf(id).forEach { dao.deleteTrackerVersion(it.id); dao.upsertTombstone(TombstoneEntity("tracker_version", it.id, t)) }
        dao.valuesOf(id).forEach {
            dao.deleteValue(id, it.date, it.seq); dao.upsertTombstone(TombstoneEntity("tracker_value", "$id|${it.date}|${it.seq}", t))
        }
        dao.deleteTracker(id)
        dao.upsertTombstone(TombstoneEntity("tracker", id, t))
    }

    suspend fun moveTracker(id: String, delta: Int) = db.withTransaction {
        val list = dao.trackersNow().sortedBy { it.sortOrder }.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@withTransaction
        list.add(j, list.removeAt(i))
        dao.upsertTrackers(list.mapIndexed { idx, t -> t.copy(sortOrder = idx, updatedAt = now()) })
    }

    suspend fun saveValue(v: TrackerValueEntity) = dao.upsertValue(v.copy(updatedAt = now()))

    suspend fun deleteValue(trackerId: String, date: String, seq: Int) {
        dao.deleteValue(trackerId, date, seq)
        dao.upsertTombstone(TombstoneEntity("tracker_value", "$trackerId|$date|$seq", now()))
    }

    /** Moves a sleep entry to another attributed date (keeps naps on their own date). */
    suspend fun moveValue(v: TrackerValueEntity, newDate: String, newSeq: Int) = db.withTransaction {
        deleteValue(v.trackerId, v.date, v.seq)
        dao.upsertValue(v.copy(date = newDate, seq = newSeq, updatedAt = now()))
    }

    suspend fun nextSeq(trackerId: String, date: String): Int =
        (dao.valuesAt(trackerId, date).maxOfOrNull { it.seq } ?: 0) + 1

    // ---- settings ------------------------------------------------------------------------

    suspend fun setSetting(key: String, value: String?) {
        if (value == null) dao.upsertSetting(SettingEntity(key, "", now()))
        else dao.upsertSetting(SettingEntity(key, value, now()))
    }

    suspend fun getSetting(key: String): String? = dao.setting(key)?.value?.ifEmpty { null }

    /** [from] == null means "apply to all history". */
    suspend fun setVersionedSetting(key: String, value: String, from: LocalDate?) = db.withTransaction {
        if (from == null) {
            dao.settingVersionsOf(key).forEach {
                dao.upsertTombstone(TombstoneEntity("setting_version", "$key|${it.effectiveFrom}", now()))
            }
            dao.clearSettingVersions(key)
            dao.upsertSettingVersion(SettingVersionEntity(key, "1900-01-01", value, now()))
        } else {
            dao.upsertSettingVersion(SettingVersionEntity(key, from.toString(), value, now()))
        }
    }

    suspend fun deleteSettingVersion(key: String, from: String) {
        dao.deleteSettingVersion(key, from)
        dao.upsertTombstone(TombstoneEntity("setting_version", "$key|$from", now()))
    }

    // ---- first run -----------------------------------------------------------------------

    suspend fun seed(withExampleTasks: Boolean) = db.withTransaction {
        if (dao.setting(SettingKeys.SEEDED) != null) return@withTransaction
        val start = YearMonth.now().atDay(1).toString()
        fun tv(name: String, type: ScheduleType, points: Int, threshold: String? = null, times: Int? = null, n: Int? = null) =
            TaskVersionEntity("", "", start, name, type.name, points, threshold, times, n,
                if (type == ScheduleType.EVERY_N) start else null, null, null, 0)
        if (withExampleTasks) {
            listOf(
                tv("Water", ScheduleType.DAILY, 1, ">64 oz"),
                tv("Steps", ScheduleType.DAILY, 1, ">8000"),
                tv("Stretch", ScheduleType.DAILY, 1),
                tv("Calories", ScheduleType.DAILY, 1, "<2000"),
                tv("Read 5 min", ScheduleType.DAILY, 1),
                tv("Exercise", ScheduleType.X_PER_WEEK, 5, times = 3),
                tv("Clean office", ScheduleType.WEEKLY, 5),
                tv("Back up files", ScheduleType.WEEKLY, 5),
                tv("Trim fingernails", ScheduleType.WEEKLY, 5),
                tv("Water plants", ScheduleType.EVERY_N, 3, n = 4),
                tv("Clean car", ScheduleType.MONTHLY, 20),
                tv("Buy groceries", ScheduleType.MONTHLY, 20),
            ).forEach { createTask(it) }
        }
        fun trv(name: String, type: TrackerType, unit: String? = null, max: Int? = null) =
            TrackerVersionEntity("", "", start, name, type.name, unit, max, null, null, 0)
        listOf(
            trv("Energy Dip?", TrackerType.TIME),
            trv("Day Productivity", TrackerType.RATING, max = 10),
            trv("Calories", TrackerType.NUMBER, "kcal"),
            trv("Protein (g)", TrackerType.NUMBER, "g"),
            trv("Steps", TrackerType.NUMBER),
            trv("Med 1", TrackerType.CHECKBOX),
            trv("Med 2", TrackerType.CHECKBOX),
        ).forEach { createTracker(it) }
        val sleepId = createTracker(trv("Sleep", TrackerType.TIME_RANGE))
        createTracker(
            TrackerVersionEntity("", "", start, "Total Hours", TrackerType.COMPUTED.name, "h", null, FN_SLEEP_DURATION, sleepId, 0),
        )
        listOf(
            trv("Quality", TrackerType.RATING, max = 10),
            trv("Waking Energy", TrackerType.RATING, max = 10),
            trv("Weigh-in", TrackerType.NUMBER, "lb"),
        ).forEach { createTracker(it) }

        setSetting(SettingKeys.AUTO_BACKUP_DAYS, "7")
        setSetting(SettingKeys.BACKUP_KEEP, "10")
        setSetting(SettingKeys.BACKUP_REMINDER, "true")
        setSetting(SettingKeys.SEEDED, "true")
    }

    companion object {
        fun scheduleFields(s: Schedule): Triple<Int?, Int?, String?> = when (s) {
            is Schedule.XPerWeek -> Triple(s.times, null, null)
            is Schedule.EveryN -> Triple(null, s.days, s.anchor.toString())
            else -> Triple(null, null, null)
        }
    }
}
