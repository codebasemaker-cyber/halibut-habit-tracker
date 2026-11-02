package com.codebasemaker.habittracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.TrackerDef
import com.codebasemaker.habittracker.data.TrackerType
import com.codebasemaker.habittracker.data.TrackerValueEntity
import com.codebasemaker.habittracker.data.toLocalDateTimeOrNull
import com.codebasemaker.habittracker.domain.Cell
import com.codebasemaker.habittracker.domain.CellDisplay
import com.codebasemaker.habittracker.domain.CellSource
import com.codebasemaker.habittracker.domain.Instance
import com.codebasemaker.habittracker.domain.MarkState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Which editor is open; shared by every screen through [LocalEditor]. */
sealed interface EditorRequest {
    data class CellEdit(val taskId: String, val date: LocalDate, val cell: Cell) : EditorRequest
    data class WeekResult(val instance: Instance) : EditorRequest
    data class ValueEdit(val trackerId: String, val date: LocalDate) : EditorRequest
}

val LocalEditor = compositionLocalOf<MutableState<EditorRequest?>> { error("no editor host") }

@Composable
fun EditorHost(vm: MainViewModel, snap: AppSnapshot, state: MutableState<EditorRequest?>) {
    when (val r = state.value) {
        is EditorRequest.CellEdit -> CellEditorSheet(vm, snap, r) { state.value = null }
        is EditorRequest.WeekResult -> WeekResultSheet(vm, snap, r.instance) { state.value = null }
        is EditorRequest.ValueEdit -> {
            val tracker = snap.trackers.firstOrNull { it.id == r.trackerId }
            if (tracker == null) state.value = null
            else ValueEditorDialog(vm, snap, tracker, r.date) { state.value = null }
        }
        null -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CellEditorSheet(vm: MainViewModel, snap: AppSnapshot, r: EditorRequest.CellEdit, onClose: () -> Unit) {
    val task = snap.tasks.firstOrNull { it.id == r.taskId } ?: return onClose()
    val v = task.versionOn(r.date)
    val existing = snap.marksByKey[r.taskId to r.date]
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    val hadDerived = r.cell.source == CellSource.DERIVED || r.cell.source == CellSource.OVERRIDE

    fun set(state: MarkState?) {
        vm.setMark(r.taskId, r.date, state, hadDerived && state != MarkState.DONE, note)
        onClose()
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(v?.name ?: "Task", style = MaterialTheme.typography.titleLarge)
            Text(Fmt.long(r.date) + (v?.thresholdLabel?.let { " · $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = when (r.cell.source) {
                CellSource.DERIVED -> "Automatic: ${r.cell.display.word()}. Choosing a mark overrides it."
                CellSource.OVERRIDE -> "Manual override (${r.cell.display.word()})."
                CellSource.MANUAL -> "Entered: ${r.cell.display.word()}."
                CellSource.NONE -> "Unrecorded."
            }
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { set(MarkState.DONE) }) { Text("●  Done") }
                FilledTonalButton(onClick = { set(MarkState.SKIP) }) { Text("–  Skip") }
                FilledTonalButton(onClick = { set(MarkState.MISSED) }) { Text("✕  Missed") }
                OutlinedButton(onClick = { set(MarkState.BLANK) }) { Text("Force blank") }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (existing != null) TextButton(onClick = { vm.setMark(r.taskId, r.date, null, false, null); onClose() }) {
                    Text(if (hadDerived) "Clear (back to automatic)" else "Clear entry")
                }
                if (existing != null) TextButton(onClick = {
                    vm.setMark(r.taskId, r.date, existing.state, existing.isOverride, note); onClose()
                }) { Text("Save note") }
            }
        }
    }
}

private fun CellDisplay.word() = when (this) {
    CellDisplay.DONE -> "done ●"; CellDisplay.SKIP -> "skipped –"; CellDisplay.MISSED -> "missed ✕"; CellDisplay.EMPTY -> "blank"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WeekResultSheet(vm: MainViewModel, snap: AppSnapshot, inst: Instance, onClose: () -> Unit) {
    val name = snap.tasks.firstOrNull { it.id == inst.taskId }?.versionOn(inst.due)?.name ?: ""
    fun set(s: MarkState?) { vm.setWeekOverride(inst.taskId, inst.due, s); onClose() }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Text("Week ${Fmt.short(inst.period.start)} – ${Fmt.short(inst.due)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${inst.done ?: 0} of ${inst.goal ?: 0} done · ${Fmt.pts(inst.earned)} / ${Fmt.pts(inst.possible)} pts" +
                    if (inst.overridden) " (overridden)" else "",
                modifier = Modifier.padding(vertical = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { set(MarkState.DONE) }) { Text("Full credit") }
                FilledTonalButton(onClick = { set(MarkState.SKIP) }) { Text("–  Skip week") }
                FilledTonalButton(onClick = { set(MarkState.MISSED) }) { Text("✕  No credit") }
                OutlinedButton(onClick = { set(null) }) { Text("Automatic") }
            }
        }
    }
}

// ---- Section 2 value editors ------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ValueEditorDialog(vm: MainViewModel, snap: AppSnapshot, tracker: TrackerDef, date: LocalDate, onClose: () -> Unit) {
    val v = tracker.versionOn(date) ?: return onClose()
    val type = TrackerType.valueOf(v.type)
    if (type == TrackerType.TIME_RANGE) return SleepEditorDialog(vm, snap, tracker, date, onClose)

    val main = snap.valuesByKey[Triple(tracker.id, date.toString(), 0)]
    fun base() = main ?: TrackerValueEntity(tracker.id, date.toString(), 0, "MAIN", null, null, null, null, null, null, null, false, "MANUAL", 0)
    val weightKg = Values.isWeight(v) && snap.weightUnit == "kg"

    var text by remember {
        mutableStateOf(
            when (type) {
                TrackerType.NUMBER -> main?.num?.let { Fmt.num(if (weightKg) it / LB_PER_KG else it, 2) } ?: ""
                TrackerType.TEXT -> main?.text ?: ""
                TrackerType.COMPUTED -> main?.takeIf { it.isOverride }?.num?.let { Fmt.num(it, 2) } ?: ""
                else -> ""
            },
        )
    }
    var time by remember { mutableStateOf(main?.timeMinutes?.let { LocalTime.of(it / 60, it % 60) } ?: LocalTime.of(16, 0)) }
    var pickTime by rememberBool()

    fun save(e: TrackerValueEntity) { vm.saveValue(e); onClose() }
    fun clear() { main?.let { vm.deleteValue(it) }; onClose() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text(v.name); Text(Fmt.long(date), style = MaterialTheme.typography.bodyMedium) } },
        text = {
            Column {
                when (type) {
                    TrackerType.NUMBER -> NumberField("Value", text, { text = it }, decimal = true, suffix = Values.unitLabel(snap, v))
                    TrackerType.TEXT -> OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Text") })
                    TrackerType.CHECKBOX -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { save(base().copy(bool = true)) }) { Text("✓ Yes") }
                        FilledTonalButton(onClick = { save(base().copy(bool = false)) }) { Text("✗ No") }
                    }
                    TrackerType.RATING -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..(v.ratingMax ?: 10)).forEach { n ->
                            FilterChip(selected = main?.num?.toInt() == n, onClick = { save(base().copy(num = n.toDouble())) }, label = { Text("$n") })
                        }
                    }
                    TrackerType.TIME -> TextButton(onClick = { pickTime = true }) {
                        Text(Fmt.timeFull(time), style = MaterialTheme.typography.headlineSmall)
                    }
                    TrackerType.COMPUTED -> {
                        val c = Values.computed(snap, v, date)
                        Text("Computed: " + (c?.let { Fmt.hours(it) + " (" + Fmt.num(it, 2) + " h)" } ?: "no data"))
                        NumberField("Override (hours)", text, { text = it }, decimal = true, modifier = Modifier.padding(top = 8.dp))
                    }
                    TrackerType.TIME_RANGE -> {}
                }
            }
        },
        confirmButton = {
            when (type) {
                TrackerType.NUMBER -> TextButton(onClick = {
                    val n = text.toDoubleOrNull()
                    if (n == null) clear() else save(base().copy(num = if (weightKg) n * LB_PER_KG else n))
                }) { Text("Save") }
                TrackerType.TEXT -> TextButton(onClick = { if (text.isBlank()) clear() else save(base().copy(text = text.trim())) }) { Text("Save") }
                TrackerType.TIME -> TextButton(onClick = { save(base().copy(timeMinutes = time.hour * 60 + time.minute)) }) { Text("Save") }
                TrackerType.COMPUTED -> TextButton(onClick = {
                    val n = text.toDoubleOrNull()
                    if (n == null) clear() else save(base().copy(num = n, isOverride = true))
                }) { Text("Save") }
                else -> TextButton(onClick = onClose) { Text("Close") }
            }
        },
        dismissButton = {
            Row {
                if (main != null) TextButton(onClick = { clear() }) {
                    Text(if (type == TrackerType.COMPUTED) "Use computed" else "Clear")
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
    if (pickTime) TimePickDialog(time, v.name, { time = it }, { pickTime = false })
}

/** Sleep: one main sleep (bed + wake together) plus optional naps, each with a note. */
@Composable
private fun SleepEditorDialog(vm: MainViewModel, snap: AppSnapshot, tracker: TrackerDef, date: LocalDate, onClose: () -> Unit) {
    val all = snap.valuesAt(tracker.id, date)
    val main = all.firstOrNull { it.seq == 0 }
    val naps = all.filter { it.seq > 0 }
    var editing by remember { mutableStateOf<TrackerValueEntity?>(null) }
    var isNew by remember { mutableStateOf(false) }

    val e = editing
    if (e != null) {
        SleepEntryEditor(e, date, onSave = { saved ->
            if (isNew && saved.seq > 0) vm.addNap(saved) else vm.saveValue(saved)
            editing = null
        }, onDelete = { if (!isNew) vm.deleteValue(e); editing = null }, onCancel = { editing = null })
        return
    }

    fun blank(seq: Int, kind: String) = TrackerValueEntity(tracker.id, date.toString(), seq, kind, null, null, null, null, null, null, null, false, "MANUAL", 0)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text(tracker.versionOn(date)?.name ?: "Sleep"); Text(Fmt.long(date), style = MaterialTheme.typography.bodyMedium) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Main sleep")
                val ms = main?.let { with(Values) { it.toSleep() } }
                TextButton(onClick = { isNew = main == null; editing = main ?: blank(0, "MAIN") }) {
                    Text(ms?.let { Fmt.range(it.bed, it.wake) + "  ·  " + Fmt.hours(it.duration(Values.zone()).toMinutes() / 60.0) } ?: "Add main sleep")
                }
                main?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Naps")
                naps.forEach { n ->
                    val s = with(Values) { n.toSleep() }
                    TextButton(onClick = { isNew = false; editing = n }) {
                        Text(s?.let { Fmt.range(it.bed, it.wake) } ?: "Nap")
                    }
                    n.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = { isNew = true; editing = blank(1, "NAP") }) { Text("+ Add nap") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun SleepEntryEditor(
    e: TrackerValueEntity,
    date: LocalDate,
    onSave: (TrackerValueEntity) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val isNap = e.kind == "NAP"
    val start = e.rangeStart?.toLocalDateTimeOrNull()
    val end = e.rangeEnd?.toLocalDateTimeOrNull()
    var bed by remember { mutableStateOf(start?.toLocalTime() ?: if (isNap) LocalTime.of(14, 0) else LocalTime.of(22, 30)) }
    var wake by remember { mutableStateOf(end?.toLocalTime() ?: if (isNap) LocalTime.of(15, 0) else LocalTime.of(7, 0)) }
    // Main sleep counts on the wake date by default; this switch counts it on the bed date instead.
    var onBedDate by remember { mutableStateOf(!isNap && start != null && end != null && start.toLocalDate() == date && end.toLocalDate().isAfter(date)) }
    var note by remember { mutableStateOf(e.note.orEmpty()) }
    var pick by remember { mutableStateOf<String?>(null) }

    fun build(): TrackerValueEntity {
        val crosses = !wake.isAfter(bed)
        val (s, en) = if (isNap || onBedDate) {
            LocalDateTime.of(date, bed) to LocalDateTime.of(if (crosses) date.plusDays(1) else date, wake)
        } else {
            LocalDateTime.of(if (crosses) date.minusDays(1) else date, bed) to LocalDateTime.of(date, wake)
        }
        return e.copy(rangeStart = s.toString(), rangeEnd = en.toString(), note = note.ifBlank { null })
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (isNap) "Nap" else "Main sleep") },
        text = {
            Column {
                PickerField(if (isNap) "Start" else "Bed time", Fmt.timeFull(bed), { pick = "bed" })
                PickerField(if (isNap) "End" else "Wake time", Fmt.timeFull(wake), { pick = "wake" })
                if (!isNap) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Count on bed date instead of wake date", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(onBedDate, { onBedDate = it })
                    }
                }
                val b = build()
                Text(
                    Fmt.range(LocalDateTime.parse(b.rangeStart), LocalDateTime.parse(b.rangeEnd)) + "  ·  " +
                        Fmt.hours(java.time.Duration.between(
                            LocalDateTime.parse(b.rangeStart).atZone(Values.zone()), LocalDateTime.parse(b.rangeEnd).atZone(Values.zone()),
                        ).toMinutes() / 60.0),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (e.rangeStart != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
    when (pick) {
        "bed" -> TimePickDialog(bed, if (isNap) "Start" else "Bed time", { bed = it }, { pick = null })
        "wake" -> TimePickDialog(wake, if (isNap) "End" else "Wake time", { wake = it }, { pick = null })
    }
}
