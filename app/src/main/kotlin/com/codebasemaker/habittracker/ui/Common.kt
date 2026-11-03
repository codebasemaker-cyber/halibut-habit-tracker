package com.codebasemaker.habittracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codebasemaker.habittracker.domain.Cell
import com.codebasemaker.habittracker.domain.CellDisplay
import com.codebasemaker.habittracker.domain.CellSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset

private val CornerTriangle = GenericShape { size, _ ->
    moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, 0f); close()
}

/** A habit cell: ● – X or blank. Derived marks are faded; overrides get an amber corner. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkCell(
    cell: Cell?,
    size: Dp,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = LocalMarkColors.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    val bg = when {
        cell == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        highlight -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    val label = when (cell?.display) {
        CellDisplay.DONE -> "done"; CellDisplay.SKIP -> "skipped"; CellDisplay.MISSED -> "missed"
        else -> if (cell == null) "not applicable" else "unrecorded"
    }
    Box(
        modifier
            .size(size)
            .background(bg)
            .border(0.5.dp, outline)
            .semantics { contentDescription = label + if (cell?.source == CellSource.DERIVED) " (automatic)" else "" }
            .then(
                if (cell != null && (onTap != null || onLongPress != null))
                    Modifier.combinedClickable(onClick = { onTap?.invoke() }, onLongClick = onLongPress)
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (cell != null) MarkGlyph(cell.display, cell.source == CellSource.DERIVED, size * 0.42f)
        if (cell?.source == CellSource.OVERRIDE) {
            Box(Modifier.align(Alignment.TopEnd).size(size * 0.28f).clip(CornerTriangle).background(colors.override))
        }
    }
}

@Composable
fun MarkGlyph(display: CellDisplay, derived: Boolean, size: Dp) {
    val c = LocalMarkColors.current
    val a = if (derived) c.derivedAlpha else 1f
    when (display) {
        CellDisplay.DONE -> Box(Modifier.size(size).clip(CircleShape).background(c.done.copy(alpha = a)))
        CellDisplay.SKIP -> Box(Modifier.size(width = size, height = size * 0.2f).background(c.skip.copy(alpha = a)))
        CellDisplay.MISSED -> Text("✕", color = c.missed.copy(alpha = a), fontSize = (size.value * 1.15f).sp, fontWeight = FontWeight.Bold)
        CellDisplay.EMPTY -> {}
    }
}

@Composable
fun StepperHeader(title: String, onPrev: () -> Unit, onNext: () -> Unit, onTitle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
        TextButton(onClick = onTitle) { Text(title, style = MaterialTheme.typography.titleMedium) }
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
    }
}

@Composable
fun MonthPickerDialog(initial: YearMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by remember { mutableIntStateOf(initial.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(YearMonth.now()); onDismiss() }) { Text("This month") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous year") }
                Text("$year", Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next year") }
            }
        },
        text = {
            LazyVerticalGrid(GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items((1..12).toList()) { m ->
                    val ym = YearMonth.of(year, m)
                    FilterChip(
                        selected = ym == initial,
                        onClick = { onPick(ym); onDismiss() },
                        label = { Text(ym.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())) },
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit, title: String? = null) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state, title = title?.let { { Text(it, Modifier.padding(start = 24.dp, top = 16.dp)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: LocalTime, title: String, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Field that shows a value and opens a picker when tapped. */
@Composable
fun PickerField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(value.ifEmpty { "Not set" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> if (s.all { it.isDigit() || (decimal && (it == '.' || it == ',')) || it == '-' }) onChange(s.replace(',', '.')) },
        label = { Text(label) },
        singleLine = true,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
fun rememberBool(initial: Boolean = false) = remember { mutableStateOf(initial) }
