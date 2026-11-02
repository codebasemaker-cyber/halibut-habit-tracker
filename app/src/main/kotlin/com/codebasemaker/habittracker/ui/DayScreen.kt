package com.codebasemaker.habittracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.TrackerDef
import com.codebasemaker.habittracker.data.TrackerType
import com.codebasemaker.habittracker.data.TrackerValueEntity
import com.codebasemaker.habittracker.domain.Cell
import com.codebasemaker.habittracker.domain.CellDisplay
import com.codebasemaker.habittracker.domain.DateRange
import com.codebasemaker.habittracker.domain.Instance
import com.codebasemaker.habittracker.domain.InstanceStatus
import com.codebasemaker.habittracker.domain.MarkState
import com.codebasemaker.habittracker.domain.ScheduleType
import com.codebasemaker.habittracker.domain.Task
import com.codebasemaker.habittracker.domain.TaskEvaluator
import com.codebasemaker.habittracker.domain.WeekCalendar
import java.time.LocalDate
import java.time.YearMonth

private data class DayItem(
    val task: Task,
    val name: String,
    val sub: String,
    /** Date the mark is stored on (the day itself, or the week/month due date). */
    val markDate: LocalDate,
    val cell: Cell,
    val weekInstance: Instance? = null,
)

@Composable
fun DayScreen(vm: MainViewModel, snap: AppSnapshot) {
    val date by vm.date.collectAsState()
    val today by vm.today.collectAsState()
    var pick by rememberBool()
    var confirmClose by rememberBool()
    val items = remember(snap, date, today) { dayItems(snap, date, today) }
    val editor = LocalEditor.current

    Column(
        Modifier.fillMaxSize().pointerInput(date) {
            var total = 0f
            detectHorizontalDragGestures(onDragEnd = {
                if (total > 120) vm.setDate(date.minusDays(1)) else if (total < -120) vm.setDate(date.plusDays(1))
                total = 0f
            }) { _, dx -> total += dx }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StepperHeader(Fmt.long(date), { vm.setDate(date.minusDays(1)) }, { vm.setDate(date.plusDays(1)) }, { pick = true })
            Spacer(Modifier.weight(1f))
            if (date != today) TextButton(onClick = { vm.setDate(today) }) { Text("Today") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            val groups = items.groupBy { it.task.versionOn(date)!!.schedule.type }
            listOf(
                ScheduleType.DAILY to "Daily",
                ScheduleType.X_PER_WEEK to "This week (X per week)",
                ScheduleType.EVERY_N to "Every N days",
                ScheduleType.WEEKLY to "Weekly — due ${Fmt.short(WeekCalendar(snap.scoring.weekStart).weekContaining(date).endInclusive)}",
                ScheduleType.MONTHLY to "Monthly — due ${Fmt.short(YearMonth.from(date).atEndOfMonth())}",
            ).forEach { (type, title) ->
                val list = groups[type].orEmpty()
                if (list.isNotEmpty()) {
                    item(key = "h-$type") { SectionTitle(title, Modifier.padding(horizontal = 16.dp)) }
                    items(list, key = { "t-" + it.task.id }) { it ->
                        TaskRow(it,
                            onSet = { s ->
                                val hadDerived = it.cell.source == com.codebasemaker.habittracker.domain.CellSource.DERIVED ||
                                    it.cell.source == com.codebasemaker.habittracker.domain.CellSource.OVERRIDE
                                vm.setMark(it.task.id, it.markDate, s, hadDerived && s != null && s != MarkState.DONE)
                            },
                            onTap = { vm.cycle(it.task.id, it.markDate, it.cell) },
                            onLong = { editor.value = EditorRequest.CellEdit(it.task.id, it.markDate, it.cell) },
                            onWeek = it.weekInstance?.let { w -> { editor.value = EditorRequest.WeekResult(w) } })
                    }
                }
            }
            if (items.isEmpty()) item { EmptyHint("No tasks on this day.") }
            if (items.any { it.task.versionOn(date)?.schedule?.type == ScheduleType.DAILY }) item {
                OutlinedButton(onClick = { confirmClose = true }, modifier = Modifier.padding(16.dp)) { Text("Close out day (blank dailies → ✕)") }
            }
            val trackers = snap.trackers.sortedBy { it.sortOrder }.filter { it.isActiveOn(date) }
            if (trackers.isNotEmpty()) {
                item { SectionTitle("Values & info", Modifier.padding(horizontal = 16.dp)) }
                items(trackers, key = { "v-" + it.id }) { t -> TrackerRow(vm, snap, t, date) { editor.value = EditorRequest.ValueEdit(t.id, date) } }
            }
        }
    }
    if (pick) DatePickDialog(date, { vm.setDate(it) }, { pick = false })
    if (confirmClose) ConfirmDialog("Close out ${Fmt.short(date)}?", "Every daily task still blank on this day will be marked ✕.", "Close out",
        { vm.closeOutDay(date) }, { confirmClose = false })
}

private fun dayItems(snap: AppSnapshot, date: LocalDate, today: LocalDate): List<DayItem> {
    val weeks = WeekCalendar(snap.scoring.weekStart)
    val week = weeks.weekContaining(date)
    val monthEnd = YearMonth.from(date).atEndOfMonth()
    val window = DateRange(minOf(week.start, date.withDayOfMonth(1)), maxOf(week.endInclusive, monthEnd))
    val evaluator = TaskEvaluator(snap.scoring, today)
    val recorded = snap.marks.filter { it.state != MarkState.BLANK }.map { it.date }.toSet()
    return snap.tasks.sortedBy { it.sortOrder }.mapNotNull { t ->
        val v = t.activeVersionOn(date) ?: return@mapNotNull null
        val eval = evaluator.evaluate(
            t, snap.marks.filter { it.taskId == t.id }.associateBy { it.date },
            snap.weekOverrides.filter { it.taskId == t.id }.associateBy { it.dueDate }, recorded, window,
        )
        val markDate = when (v.schedule.type) {
            ScheduleType.WEEKLY -> week.endInclusive
            ScheduleType.MONTHLY -> monthEnd
            else -> date
        }
        if ((v.schedule.type == ScheduleType.WEEKLY && !t.isActiveOn(week.endInclusive)) ||
            (v.schedule.type == ScheduleType.MONTHLY && !t.isActiveOn(monthEnd))) return@mapNotNull null
        val cell = eval.cells[markDate] ?: Cell.EMPTY
        val pts = "${v.points} pt" + if (v.points == 1) "" else "s"
        var weekInst: Instance? = null
        val sub = when (v.schedule.type) {
            ScheduleType.X_PER_WEEK -> {
                weekInst = eval.instances.firstOrNull { it.due == week.endInclusive }
                "${weekInst?.done ?: 0}/${weekInst?.goal ?: 0} this week · $pts"
            }
            ScheduleType.EVERY_N -> {
                val next = eval.instances.firstOrNull { it.status == InstanceStatus.PENDING }
                val overdue = cell.display == CellDisplay.MISSED && cell.source == com.codebasemaker.habittracker.domain.CellSource.DERIVED
                when {
                    overdue -> "Overdue"
                    next != null && next.due == date -> "Due today"
                    next != null -> "Next due ${Fmt.short(next.due)}"
                    else -> ""
                } + " · $pts"
            }
            else -> pts
        }
        DayItem(t, v.name + (v.thresholdLabel?.let { "  $it" } ?: ""), sub, markDate, cell, weekInst)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(item: DayItem, onSet: (MarkState?) -> Unit, onTap: () -> Unit, onLong: () -> Unit, onWeek: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkCell(item.cell, 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name, fontWeight = FontWeight.Medium)
            Text(item.sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (onWeek != null) Modifier.clickable(onClick = onWeek) else Modifier)
        }
        val manual = item.cell.display.takeIf { item.cell.source != com.codebasemaker.habittracker.domain.CellSource.DERIVED }?.toState()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(MarkState.DONE to "●", MarkState.SKIP to "–", MarkState.MISSED to "✕").forEach { (s, l) ->
                FilterChip(selected = manual == s, onClick = { onSet(if (manual == s) null else s) }, label = { Text(l) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackerRow(vm: MainViewModel, snap: AppSnapshot, t: TrackerDef, date: LocalDate, onEdit: () -> Unit) {
    val v = t.versionOn(date) ?: return
    val type = TrackerType.valueOf(v.type)
    val main = snap.valuesByKey[Triple(t.id, date.toString(), 0)]
    fun base() = main ?: TrackerValueEntity(t.id, date.toString(), 0, "MAIN", null, null, null, null, null, null, null, false, "MANUAL", 0)
    Column(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.name, Modifier.weight(1f))
            when (type) {
                TrackerType.CHECKBOX -> Switch(main?.bool == true, { on -> vm.saveValue(base().copy(bool = on)) })
                TrackerType.RATING -> {}
                else -> Text(Values.display(snap, t, date).ifEmpty { "—" } +
                    (Values.unitLabel(snap, v)?.takeIf { main?.num != null && type == TrackerType.NUMBER }?.let { " $it" } ?: ""),
                    color = if (main == null && type != TrackerType.COMPUTED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (type == TrackerType.RATING) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..(v.ratingMax ?: 10)).forEach { n ->
                    FilterChip(selected = main?.num?.toInt() == n, onClick = {
                        if (main?.num?.toInt() == n) vm.deleteValue(main) else vm.saveValue(base().copy(num = n.toDouble()))
                    }, label = { Text("$n") }, modifier = Modifier.width(44.dp))
                }
            }
        }
        if (type == TrackerType.TIME_RANGE) {
            snap.valuesAt(t.id, date).forEach { e -> e.note?.let { Text((if (e.seq > 0) "Nap: " else "") + it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(0.dp))
}
