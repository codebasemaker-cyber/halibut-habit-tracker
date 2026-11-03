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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.FN_SLEEP_DURATION
import com.codebasemaker.habittracker.data.TrackerType
import com.codebasemaker.habittracker.data.TrackerVersionEntity
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun TrackersScreen(vm: MainViewModel, snap: AppSnapshot, onOpen: (String) -> Unit) {
    val today = LocalDate.now()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val all = snap.trackers.sortedBy { it.sortOrder }
    val active = all.filter { it.archivedOn == null || it.archivedOn.isAfter(today) }
    val archived = all - active.toSet()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
            item { SectionTitle("Values & info trackers (not scored)", Modifier.padding(horizontal = 16.dp)) }
            items(active, key = { it.id }) { t ->
                val v = t.versionOn(today) ?: t.latest
                Row(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.name, fontWeight = FontWeight.Medium)
                        Text(TrackerType.valueOf(v.type).label + (v.ratingMax?.let { " 1–$it" } ?: "") +
                            (Values.unitLabel(snap, v)?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.launch { vm.repo.moveTracker(t.id, -1) } }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { vm.launch { vm.repo.moveTracker(t.id, 1) } }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
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
                        Text(t.latest.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Archived ${Fmt.short(t.archivedOn!!)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { onOpen("new") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add tracker") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

private data class TrackerForm(
    val name: String = "",
    val type: TrackerType = TrackerType.NUMBER,
    val unit: String = "",
    val ratingMax: String = "10",
    val source: String? = null,
) {
    fun toEntity(base: TrackerVersionEntity, from: LocalDate) = base.copy(
        effectiveFrom = from.toString(), name = name.trim(), type = type.name, unit = unit.trim().ifEmpty { null },
        ratingMax = if (type == TrackerType.RATING) (ratingMax.toIntOrNull() ?: 10).coerceIn(2, 100) else null,
        computedFn = if (type == TrackerType.COMPUTED) FN_SLEEP_DURATION else null,
        sourceTrackerId = if (type == TrackerType.COMPUTED) source else null,
    )

    companion object {
        fun of(e: TrackerVersionEntity) = TrackerForm(e.name, TrackerType.valueOf(e.type), e.unit.orEmpty(), (e.ratingMax ?: 10).toString(), e.sourceTrackerId)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerEditorScreen(vm: MainViewModel, snap: AppSnapshot, trackerId: String, onDone: () -> Unit) {
    val isNew = trackerId == "new"
    val tracker = snap.trackers.firstOrNull { it.id == trackerId }
    val versions = tracker?.versions.orEmpty().sortedBy { it.effectiveFrom }
    var selectedId by rememberSaveable { mutableStateOf(versions.lastOrNull()?.id) }
    val selected = versions.firstOrNull { it.id == selectedId } ?: versions.lastOrNull()
    var form by remember { mutableStateOf<TrackerForm?>(null) }
    LaunchedEffect(selected?.id) { form = selected?.let { TrackerForm.of(it) } ?: (form ?: TrackerForm()) }
    val f = form ?: TrackerForm()
    var inPlace by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberBool()
    if (!isNew && tracker == null) { onDone(); return }

    val originalType = selected?.let { TrackerType.valueOf(it.type) }
    val allowedTypes = TrackerType.entries.filter { originalType == null || originalType.compatibleWith(it) }
    val ranges = snap.trackers.filter { TrackerType.valueOf(it.latest.type) == TrackerType.TIME_RANGE }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(if (isNew) "New tracker" else f.name.ifBlank { "Tracker" }, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(f.name, { form = f.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        SectionTitle("Type")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            allowedTypes.forEach { t -> FilterChip(selected = f.type == t, onClick = { form = f.copy(type = t) }, label = { Text(t.label) }) }
        }
        if (!isNew && allowedTypes.size == 1) {
            Text("Existing values can't be converted to another type. Archive this tracker and add a new one instead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (f.type) {
            TrackerType.NUMBER -> OutlinedTextField(f.unit, { form = f.copy(unit = it) }, label = { Text("Unit (use lb for weight)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            TrackerType.RATING -> NumberField("Highest rating", f.ratingMax, { form = f.copy(ratingMax = it) }, Modifier.padding(top = 8.dp))
            TrackerType.COMPUTED -> {
                SectionTitle("Sleep duration from")
                if (ranges.isEmpty()) Text("Add a time-range tracker (e.g. Sleep) first.")
                ranges.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(f.source == r.id, { form = f.copy(source = r.id) }); Text(r.latest.name)
                    }
                }
            }
            else -> {}
        }
        SectionTitle(if (isNew) "Starts" else "Apply")
        if (!isNew) {
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!inPlace, { inPlace = false }); Text("As a change starting…") }
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(inPlace, { inPlace = true }); Text("Edit the selected version in place") }
        }
        if (isNew || !inPlace) PickerField("Effective from", Fmt.long(LocalDate.parse(from)), { pick = "from" })
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = f.name.isNotBlank() && (f.type != TrackerType.COMPUTED || f.source != null), onClick = {
                vm.launch {
                    val blank = TrackerVersionEntity(java.util.UUID.randomUUID().toString(), trackerId, "", "", "", null, null, null, null, 0)
                    when {
                        isNew -> vm.repo.createTracker(f.toEntity(blank, LocalDate.parse(from)))
                        inPlace && selected != null -> vm.repo.saveTrackerVersion(f.toEntity(selected, LocalDate.parse(selected.effectiveFrom)))
                        else -> vm.repo.saveTrackerVersion(f.toEntity(blank, LocalDate.parse(from)))
                    }
                }
                onDone()
            }) { Text("Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
        if (!isNew && tracker != null) {
            SectionTitle("Version history")
            versions.reversed().forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { selectedId = e.id; inPlace = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected?.id == e.id, { selectedId = e.id; inPlace = true })
                    Column(Modifier.weight(1f)) {
                        Text("From ${Fmt.long(LocalDate.parse(e.effectiveFrom))}")
                        Text("${e.name} · ${TrackerType.valueOf(e.type).label}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (versions.size > 1) IconButton(onClick = { vm.launch { vm.repo.deleteTrackerVersion(e.id, trackerId) } }) { Icon(Icons.Default.Delete, "Delete version") }
                }
            }
            SectionTitle("Archive & delete")
            if (tracker.archivedOn != null) {
                Text("Archived on ${Fmt.long(tracker.archivedOn)}.")
                OutlinedButton(onClick = { vm.launch { vm.repo.setTrackerArchived(trackerId, null) } }) { Text("Unarchive") }
            } else OutlinedButton(onClick = { pick = "archive" }) { Text("Archive…") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
        }
    }
    when (pick) {
        "from" -> DatePickDialog(LocalDate.parse(from), { from = it.toString() }, { pick = null }, "Effective from")
        "archive" -> DatePickDialog(LocalDate.now(), { d -> vm.launch { vm.repo.setTrackerArchived(trackerId, d) } }, { pick = null }, "Archive from")
    }
    if (confirmDelete) ConfirmDialog("Delete permanently?", "This removes the tracker and every value recorded for it.", "Delete",
        { vm.launch { vm.repo.deleteTrackerPermanently(trackerId) }; onDone() }, { confirmDelete = false }, destructive = true)
}
