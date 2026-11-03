package com.codebasemaker.habittracker.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.TrackerType
import com.codebasemaker.habittracker.domain.Cell
import com.codebasemaker.habittracker.domain.InstanceStatus
import com.codebasemaker.habittracker.domain.MonthGrid
import com.codebasemaker.habittracker.domain.ScheduleType
import com.codebasemaker.habittracker.domain.WeekCalendar
import com.codebasemaker.habittracker.domain.toRange
import java.time.LocalDate

private val CELL_SIZES = listOf(32.dp, 40.dp, 48.dp)
private val DATE_COL = 52.dp

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MonthScreen(vm: MainViewModel, snap: AppSnapshot) {
    val grid by vm.grid.collectAsState()
    val month by vm.month.collectAsState()
    val today by vm.today.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    var pickMonth by rememberBool()
    val cellSize = CELL_SIZES[zoom]

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StepperHeader(Fmt.month(month), { vm.setMonth(month.minusMonths(1)) }, { vm.setMonth(month.plusMonths(1)) }, { pickMonth = true })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { zoom = (zoom + 1) % CELL_SIZES.size }) { Text("Zoom ${zoom + 1}/3") }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Daily", "Weekly & Monthly", "Values", "Summary").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
            }
        }
        val g = grid
        Box(Modifier.weight(1f)) {
            if (g == null || g.month != month) {
                Text("Loading…", Modifier.padding(24.dp))
            } else when (tab) {
                0 -> DailyGrid(vm, snap, g, today, cellSize)
                1 -> PeriodPanels(vm, g, today, cellSize)
                2 -> ValuesGrid(vm, snap, g, today, cellSize)
                3 -> SummaryPanel(snap, g)
            }
        }
        if (g != null && g.month == month) ScoreFooter(g)
    }
    if (pickMonth) MonthPickerDialog(month, { vm.setMonth(it) }, { pickMonth = false })
}

@Composable
private fun ScoreFooter(g: MonthGrid) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val t = g.score.toDate; val m = g.score.month
            Text("To date  ${Fmt.pts(t.earned)} / ${Fmt.pts(t.possible)}  ·  ${Fmt.pct(t.percent)}", fontWeight = FontWeight.SemiBold)
            Text("Month  ${Fmt.pts(m.earned)} / ${Fmt.pts(m.possible)}  ·  ${Fmt.pct(m.percent)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Column header text rotated 90° so columns stay narrow. */
@Composable
private fun RotatedHeader(text: String, width: Dp, height: Dp = 112.dp, sub: String? = null) {
    Box(Modifier.size(width, height), contentAlignment = Alignment.BottomCenter) {
        Text(
            text + (sub?.let { "  $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = height.roundToPx(), minHeight = 0, maxHeight = width.roundToPx()))
                    layout(p.height, p.width) { p.place(-(p.width - p.height) / 2, (p.width - p.height) / 2) }
                }
                .rotate(-90f)
                .padding(start = 6.dp),
        )
    }
}

@Composable
private fun DateLabel(d: LocalDate, today: LocalDate, height: Dp) {
    Row(
        Modifier.width(DATE_COL).height(height)
            .background(if (d == today) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${d.dayOfMonth}", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
        Text(Fmt.dow2(d), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DailyGrid(vm: MainViewModel, snap: AppSnapshot, g: MonthGrid, today: LocalDate, cell: Dp) {
    val editor = LocalEditor.current
    val h: ScrollState = rememberScrollState()
    val weeks = WeekCalendar(snap.scoring.weekStart)
    if (g.dayColumns.isEmpty()) return EmptyHint("No daily, X-per-week or every-N tasks this month. Add some under Tasks.")
    val xpw = g.dayColumns.filter { c -> c.weekResults.isNotEmpty() }

    Column(Modifier.fillMaxSize()) {
        Row {
            Spacer(Modifier.width(DATE_COL))
            Row(Modifier.horizontalScroll(h)) {
                g.dayColumns.forEach { c ->
                    val sub = c.header.thresholdLabel ?: when (c.header.type) {
                        ScheduleType.X_PER_WEEK -> "${c.weekResults.firstOrNull()?.goal ?: ""}×/wk"
                        ScheduleType.EVERY_N -> snap.taskVersions[c.header.taskId]
                            ?.lastOrNull { !LocalDate.parse(it.effectiveFrom).isAfter(g.month.atEndOfMonth()) }
                            ?.intervalDays?.let { "every ${it}d" }
                        else -> null
                    }
                    RotatedHeader(c.header.name, cell, sub = sub)
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            g.days.forEach { d ->
                Row {
                    DateLabel(d, today, cell)
                    Row(Modifier.horizontalScroll(h)) {
                        g.dayColumns.forEach { c ->
                            val cl: Cell? = c.cells[d]
                            MarkCell(
                                cl, cell, highlight = d == today,
                                onTap = { cl?.let { vm.cycle(c.header.taskId, d, it) } },
                                onLongPress = { cl?.let { editor.value = EditorRequest.CellEdit(c.header.taskId, d, it) } },
                            )
                        }
                    }
                }
                if (weeks.weekContaining(d).endInclusive == d && d != g.days.last()) {
                    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (xpw.isNotEmpty()) {
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                Text("Week results (X per week)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                g.weekColumns.forEach { wc ->
                    Row {
                        Column(Modifier.width(DATE_COL).height(cell).padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
                            Text("→${wc.due.monthValue}/${wc.due.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                            if (!wc.scoredInMonth) Text("next mo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Row(Modifier.horizontalScroll(h)) {
                            g.dayColumns.forEach { c ->
                                val inst = c.weekResults.firstOrNull { it.due == wc.due }
                                WeekBadge(inst?.let { "${it.done}/${it.goal}" }, inst?.status, inst?.overridden == true, !wc.scoredInMonth, cell) {
                                    inst?.let { editor.value = EditorRequest.WeekResult(it) }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WeekBadge(text: String?, status: InstanceStatus?, overridden: Boolean, carry: Boolean, size: Dp, onClick: () -> Unit) {
    val c = LocalMarkColors.current
    val color = when (status) {
        InstanceStatus.EARNED, InstanceStatus.SKIPPED -> c.done
        InstanceStatus.PARTIAL -> c.override
        InstanceStatus.MISSED -> c.missed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(size).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(if (carry) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface)
            .then(if (text != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) Text(text + if (overridden) "*" else "", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PeriodPanels(vm: MainViewModel, g: MonthGrid, today: LocalDate, cell: Dp) {
    val editor = LocalEditor.current
    val nameW = 160.dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionTitle("Weekly", Modifier.padding(horizontal = 12.dp))
        if (g.weeklyRows.isEmpty()) EmptyHint("No weekly tasks this month.")
        else {
            val h = rememberScrollState()
            Row {
                Text("Due", Modifier.width(nameW).padding(start = 12.dp), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(h)) {
                    g.weekColumns.forEach { wc ->
                        Column(Modifier.width(cell + 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${wc.due.monthValue}/${wc.due.dayOfMonth}", style = MaterialTheme.typography.labelMedium,
                                color = if (wc.scoredInMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
            g.weeklyRows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TaskLabel(row.header.name, "${row.header.points} pts", nameW)
                    Row(Modifier.horizontalScroll(h)) {
                        g.weekColumns.forEach { wc ->
                            val c = row.cells[wc.due]
                            Box(Modifier.width(cell + 8.dp), contentAlignment = Alignment.Center) {
                                MarkCell(c, cell, highlight = today in wc.period,
                                    onTap = { c?.let { vm.cycle(row.header.taskId, wc.due, it) } },
                                    onLongPress = { c?.let { editor.value = EditorRequest.CellEdit(row.header.taskId, wc.due, it) } })
                            }
                        }
                    }
                }
            }
            g.weekColumns.lastOrNull()?.takeIf { !it.scoredInMonth }?.let {
                Text("Week ending ${Fmt.short(it.due)} is shown for tracking only and is scored in ${it.due.month.name.lowercase().replaceFirstChar(Char::uppercase)}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(12.dp))
            }
        }
        SectionTitle("Monthly", Modifier.padding(horizontal = 12.dp))
        if (g.monthlyRows.isEmpty()) EmptyHint("No monthly tasks this month.")
        g.monthlyRows.forEach { row ->
            val due = g.month.atEndOfMonth()
            val c = row.cells[due]
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaskLabel(row.header.name, "${row.header.points} pts · due ${Fmt.short(due)}", nameW)
                MarkCell(c, cell, onTap = { c?.let { vm.cycle(row.header.taskId, due, it) } },
                    onLongPress = { c?.let { editor.value = EditorRequest.CellEdit(row.header.taskId, due, it) } })
            }
        }
    }
}

@Composable
private fun TaskLabel(name: String, sub: String, width: Dp) {
    Column(Modifier.width(width).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValuesGrid(vm: MainViewModel, snap: AppSnapshot, g: MonthGrid, today: LocalDate, cell: Dp) {
    val editor = LocalEditor.current
    val range = g.month.toRange()
    val trackers = snap.trackers.sortedBy { it.sortOrder }.filter { t -> range.days.any { t.isActiveOn(it) } }
    if (trackers.isEmpty()) return EmptyHint("No trackers this month. Add some under Trackers.")
    val h = rememberScrollState()
    fun width(t: com.codebasemaker.habittracker.data.TrackerDef): Dp = when (TrackerType.valueOf(t.latest.type)) {
        TrackerType.TIME_RANGE -> 156.dp
        TrackerType.TEXT -> 120.dp
        TrackerType.CHECKBOX, TrackerType.RATING -> maxOf(cell, 44.dp)
        else -> 64.dp
    }
    Column(Modifier.fillMaxSize()) {
        Row {
            Spacer(Modifier.width(DATE_COL))
            Row(Modifier.horizontalScroll(h)) {
                trackers.forEach { t -> RotatedHeader(t.latest.name, width(t), sub = Values.unitLabel(snap, t.latest)) }
            }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            g.days.forEach { d ->
                Row {
                    DateLabel(d, today, cell)
                    Row(Modifier.horizontalScroll(h)) {
                        trackers.forEach { t ->
                            val active = t.isActiveOn(d)
                            Box(
                                Modifier.size(width(t), cell).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .background(
                                        if (!active) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        else if (d == today) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        else MaterialTheme.colorScheme.surface,
                                    )
                                    .then(if (active) Modifier.clickable { editor.value = EditorRequest.ValueEdit(t.id, d) } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (active) Text(Values.display(snap, t, d), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryPanel(snap: AppSnapshot, g: MonthGrid) {
    val range = g.month.toRange()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Habits")
        g.score.perTask.filter { it.month.possible > 0 }.forEach { ts ->
            val t = snap.tasks.firstOrNull { it.id == ts.taskId } ?: return@forEach
            val v = t.versionOn(range.endInclusive) ?: t.sortedVersions.last()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(v.name)
                    Text(Fmt.type(v.schedule.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${Fmt.pts(ts.toDate.earned)} / ${Fmt.pts(ts.toDate.possible)}  ${Fmt.pct(ts.toDate.percent)}")
                    Text("month ${Fmt.pts(ts.month.possible)} possible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
        SectionTitle("Values")
        snap.trackers.filter { t -> range.days.any { t.isActiveOn(it) } }.forEach { t ->
            val type = TrackerType.valueOf(t.latest.type)
            val nums = range.days.mapNotNull { Values.numeric(snap, t, it) }.toList()
            val desc = when {
                type == TrackerType.TEXT -> "${range.days.count { snap.valuesByKey[Triple(t.id, it.toString(), 0)] != null }} entries"
                nums.isEmpty() -> "no entries"
                type == TrackerType.CHECKBOX -> "${nums.count { it > 0 }} of ${nums.size} days"
                type == TrackerType.TIME -> "avg ${Fmt.time(nums.average().toInt())} · ${nums.size} days"
                type == TrackerType.TIME_RANGE || type == TrackerType.COMPUTED ->
                    "avg ${Fmt.hours(nums.average())} · min ${Fmt.hours(nums.min())} · max ${Fmt.hours(nums.max())}"
                Values.isWeight(t.latest) -> {
                    val conv = if (snap.weightUnit == "kg") nums.map { it / LB_PER_KG } else nums
                    "latest ${Fmt.num(conv.last())} ${snap.weightUnit} · change ${Fmt.num(conv.last() - conv.first())}"
                }
                else -> "avg ${Fmt.num(nums.average())} · min ${Fmt.num(nums.min())} · max ${Fmt.num(nums.max())} · ${nums.size} days"
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(t.latest.name, Modifier.weight(1f))
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
