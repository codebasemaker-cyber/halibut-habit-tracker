package com.codebasemaker.habittracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.TaskVersionEntity
import com.codebasemaker.habittracker.domain.ScheduleType
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@Composable
fun TasksScreen(vm: MainViewModel, snap: AppSnapshot, onOpen: (String) -> Unit) {
    val today = LocalDate.now()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val all = snap.tasks.sortedBy { it.sortOrder }
    val active = all.filter { it.archivedOn == null || it.archivedOn!!.isAfter(today) }
    val archived = all - active.toSet()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
            item { SectionTitle("Tasks", Modifier.padding(horizontal = 16.dp)) }
            if (active.isEmpty()) item { EmptyHint("No tasks yet. Tap + to add one.") }
            items(active, key = { it.id }) { t ->
                val v = t.versionOn(today) ?: t.sortedVersions.first()
                Row(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.name, fontWeight = FontWeight.Medium)
                        Text(scheduleLabel(snap, t.id, v.effectiveFrom) + " · ${v.points} pts" + (v.thresholdLabel?.let { " · $it" } ?: "") +
                            (if (t.firstEffective.isAfter(today)) " · starts ${Fmt.short(t.firstEffective)}" else ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.launch { vm.repo.moveTask(t.id, -1) } }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { vm.launch { vm.repo.moveTask(t.id, 1) } }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                }
                HorizontalDivider()
            }
            if (archived.isNotEmpty()) {
                item {
                    TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text((if (showArchived) "Hide" else "Show") + " archived (${archived.size})")
                    }
                }
                if (showArchived) items(archived, key = { "a-" + it.id }) { t ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(t.sortedVersions.last().name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Archived ${Fmt.short(t.archivedOn!!)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { onOpen("new") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add task") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

private fun scheduleLabel(snap: AppSnapshot, taskId: String, from: LocalDate): String {
    val e = snap.taskVersions[taskId]?.lastOrNull { !LocalDate.parse(it.effectiveFrom).isAfter(from) } ?: return ""
    return when (ScheduleType.valueOf(e.scheduleType)) {
        ScheduleType.X_PER_WEEK -> "${e.timesPerWeek}× per week"
        ScheduleType.EVERY_N -> "Every ${e.intervalDays} days"
        else -> Fmt.type(ScheduleType.valueOf(e.scheduleType))
    } + (e.reminderMinutes?.let { " · ⏰ ${Fmt.time(it)}" } ?: "")
}

/** Form state for one task version. */
private data class TaskForm(
    val name: String = "",
    val type: ScheduleType = ScheduleType.DAILY,
    val points: String = "1",
    val times: String = "3",
    val interval: String = "7",
    val anchor: LocalDate? = null,
    val threshold: String = "",
    val reminder: Int? = null,
) {
    fun toEntity(base: TaskVersionEntity, from: LocalDate) = base.copy(
        effectiveFrom = from.toString(),
        name = name.trim(),
        scheduleType = type.name,
        points = points.toIntOrNull()?.coerceAtLeast(0) ?: Fmt.defaultPoints(type),
        thresholdLabel = threshold.trim().ifEmpty { null },
        timesPerWeek = if (type == ScheduleType.X_PER_WEEK) (times.toIntOrNull() ?: 3).coerceIn(1, 7) else null,
        intervalDays = if (type == ScheduleType.EVERY_N) (interval.toIntOrNull() ?: 7).coerceAtLeast(1) else null,
        anchorDate = if (type == ScheduleType.EVERY_N) (anchor ?: from).toString() else null,
        reminderMinutes = reminder,
    )

    companion object {
        fun of(e: TaskVersionEntity) = TaskForm(
            e.name, ScheduleType.valueOf(e.scheduleType), e.points.toString(), (e.timesPerWeek ?: 3).toString(),
            (e.intervalDays ?: 7).toString(), e.anchorDate?.let(LocalDate::parse), e.thresholdLabel.orEmpty(), e.reminderMinutes,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScreen(vm: MainViewModel, snap: AppSnapshot, taskId: String, onDone: () -> Unit) {
    val isNew = taskId == "new"
    val task = snap.tasks.firstOrNull { it.id == taskId }
    val versions = snap.taskVersions[taskId].orEmpty()
    val monthStart = YearMonth.now().atDay(1)

    var selectedId by rememberSaveable { mutableStateOf(versions.lastOrNull()?.id) }
    val selected = versions.firstOrNull { it.id == selectedId } ?: versions.lastOrNull()
    var form by androidx.compose.runtime.remember { mutableStateOf<TaskForm?>(null) }
    LaunchedEffect(selected?.id) { form = selected?.let { TaskForm.of(it) } ?: (form ?: TaskForm()) }
    val f = form ?: TaskForm()
    var inPlace by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(monthStart.toString()) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberBool()

    if (!isNew && task == null) { onDone(); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(if (isNew) "New task" else f.name.ifBlank { "Task" }, style = MaterialTheme.typography.headlineSmall)
        if (!isNew && selected != null) {
            Text(if (inPlace) "Editing the version that starts ${Fmt.short(LocalDate.parse(selected.effectiveFrom))}"
                else "Changes apply from the date below; earlier days keep their old settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(f.name, { form = f.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        SectionTitle("Schedule")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScheduleType.entries.forEach { t ->
                FilterChip(selected = f.type == t, onClick = {
                    form = f.copy(type = t, points = if (f.points == Fmt.defaultPoints(f.type).toString()) Fmt.defaultPoints(t).toString() else f.points)
                }, label = { Text(Fmt.type(t)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            NumberField("Points", f.points, { form = f.copy(points = it) }, Modifier.weight(1f))
            when (f.type) {
                ScheduleType.X_PER_WEEK -> NumberField("Times per week", f.times, { form = f.copy(times = it) }, Modifier.weight(1f))
                ScheduleType.EVERY_N -> NumberField("Every N days", f.interval, { form = f.copy(interval = it) }, Modifier.weight(1f))
                else -> Spacer(Modifier.weight(1f))
            }
        }
        if (f.type == ScheduleType.EVERY_N) {
            val anchor = f.anchor ?: LocalDate.parse(if (inPlace && selected != null) selected.effectiveFrom else from)
            PickerField("First due date", Fmt.long(anchor), { pick = "anchor" })
        }
        OutlinedTextField(f.threshold, { form = f.copy(threshold = it) }, label = { Text("Threshold label (e.g. >64 oz)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Reminder", Modifier.weight(1f))
            if (f.reminder != null) TextButton(onClick = { pick = "reminder" }) { Text(Fmt.time(f.reminder)) }
            Switch(f.reminder != null, { on -> form = f.copy(reminder = if (on) 9 * 60 else null) })
        }

        SectionTitle(if (isNew) "Starts" else "Apply")
        if (!isNew) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!inPlace, { inPlace = false }); Text("As a change starting…")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(inPlace, { inPlace = true }); Text("Edit the selected version in place")
            }
        }
        if (isNew || !inPlace) PickerField(if (isNew) "Effective from (can be in the past)" else "Change starts", Fmt.long(LocalDate.parse(from)), { pick = "from" })
        if (inPlace && selected != null) PickerField("Version starts", Fmt.long(LocalDate.parse(selected.effectiveFrom)), { pick = "versionFrom" })

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = f.name.isNotBlank(), onClick = {
                vm.launch {
                    if (isNew) {
                        vm.repo.createTask(f.toEntity(blankVersion(), LocalDate.parse(from)))
                    } else if (inPlace && selected != null) {
                        vm.repo.saveTaskVersion(f.toEntity(selected, LocalDate.parse(selected.effectiveFrom)))
                    } else {
                        val base = blankVersion().copy(id = java.util.UUID.randomUUID().toString(), taskId = taskId, autoRule = selected?.autoRule)
                        vm.repo.saveTaskVersion(f.toEntity(base, LocalDate.parse(from)))
                    }
                }
                onDone()
            }) { Text("Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }

        if (!isNew && task != null) {
            SectionTitle("Version history")
            versions.reversed().forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { selectedId = e.id; inPlace = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected?.id == e.id, { selectedId = e.id; inPlace = true })
                    Column(Modifier.weight(1f)) {
                        Text("From ${Fmt.long(LocalDate.parse(e.effectiveFrom))}")
                        Text("${e.name} · ${scheduleLabel(snap, taskId, LocalDate.parse(e.effectiveFrom))} · ${e.points} pts" +
                            (e.thresholdLabel?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                    }
                    if (versions.size > 1) IconButton(onClick = { vm.launch { vm.repo.deleteTaskVersion(e.id, taskId) } }) {
                        Icon(Icons.Default.Delete, "Delete version")
                    }
                }
            }
            SectionTitle("Archive & delete")
            if (task.archivedOn != null) {
                Text("Archived on ${Fmt.long(task.archivedOn!!)}. It stays in months before that date.")
                OutlinedButton(onClick = { vm.launch { vm.repo.setTaskArchived(taskId, null) } }) { Text("Unarchive") }
            } else {
                Text("Archiving hides the task from that day on and keeps it in past months.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { pick = "archive" }) { Text("Archive…") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
        }
    }

    when (pick) {
        "from" -> DatePickDialog(LocalDate.parse(from), { from = it.toString() }, { pick = null }, "Effective from")
        "versionFrom" -> selected?.let { s ->
            DatePickDialog(LocalDate.parse(s.effectiveFrom), { d ->
                vm.launch { vm.repo.saveTaskVersion(f.toEntity(s, d)) }
            }, { pick = null }, "Version starts")
        }
        "anchor" -> DatePickDialog(f.anchor ?: LocalDate.parse(from), { form = f.copy(anchor = it) }, { pick = null }, "First due date")
        "reminder" -> TimePickDialog(LocalTime.of((f.reminder ?: 540) / 60, (f.reminder ?: 540) % 60), "Reminder", { form = f.copy(reminder = it.hour * 60 + it.minute) }, { pick = null })
        "archive" -> DatePickDialog(LocalDate.now(), { d -> vm.launch { vm.repo.setTaskArchived(taskId, d) } }, { pick = null }, "Archive from")
    }
    if (confirmDelete) ConfirmDialog(
        "Delete permanently?",
        "This removes the task, its history and all its marks. Past scores will change. This can't be undone (except by restoring a backup).",
        "Delete", { vm.launch { vm.repo.deleteTaskPermanently(taskId) }; onDone() }, { confirmDelete = false }, destructive = true,
    )
}

private fun blankVersion() = TaskVersionEntity("", "", "", "", ScheduleType.DAILY.name, 1, null, null, null, null, null, null, 0)
