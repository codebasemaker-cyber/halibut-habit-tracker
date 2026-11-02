package com.codebasemaker.habittracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codebasemaker.habittracker.BuildConfig
import com.codebasemaker.habittracker.backup.BackupContent
import com.codebasemaker.habittracker.backup.BackupWorker
import com.codebasemaker.habittracker.backup.RestoreMode
import com.codebasemaker.habittracker.backup.RestorePreview
import com.codebasemaker.habittracker.data.AppSnapshot
import com.codebasemaker.habittracker.data.SettingKeys
import com.codebasemaker.habittracker.notify.Notifier
import com.codebasemaker.habittracker.notify.ReminderScheduler
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snap: AppSnapshot) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dashDialog by remember { mutableStateOf<Boolean?>(null) }
    var weekDialog by remember { mutableStateOf<DayOfWeek?>(null) }
    var pickEod by rememberBool()
    var restoreContent by remember { mutableStateOf<BackupContent?>(null) }
    var notifGranted by remember { mutableStateOf(Notifier.canPost(ctx)) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.launch { vm.repo.setSetting(SettingKeys.BACKUP_TREE_URI, uri.toString()) }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { vm.app.backup.writeZip(it) }; "Exported."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.use { vm.app.backup.read(it) } }
                .onSuccess { restoreContent = it }
                .onFailure { message = "Can't read that file: ${it.message}" }
        }
    }

    val versions = snap.settingVersions
    val dashOn = snap.scoring.dashCountsAsEarned.at(LocalDate.now())
    val weekStart = snap.scoring.weekStart.at(LocalDate.now())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionTitle("Scoring")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dashes count as earned points")
                Text("A skipped (–) item earns its points", style = MaterialTheme.typography.bodySmall)
            }
            Switch(dashOn, { dashDialog = it })
        }
        versions.filter { it.key == SettingKeys.DASH_COUNTS }.sortedBy { it.effectiveFrom }.forEach { sv ->
            HistoryRow((if (sv.effectiveFrom == "1900-01-01") "All history" else "From ${Fmt.long(LocalDate.parse(sv.effectiveFrom))}") +
                ": " + (if (sv.value.toBoolean()) "count" else "don't count")) { vm.launch { vm.repo.deleteSettingVersion(sv.key, sv.effectiveFrom) } }
        }
        Text("Week starts on", Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { d ->
                FilterChip(selected = weekStart == d, onClick = { if (d != weekStart) weekDialog = d },
                    label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
            }
        }
        versions.filter { it.key == SettingKeys.WEEK_START }.sortedBy { it.effectiveFrom }.forEach { sv ->
            HistoryRow((if (sv.effectiveFrom == "1900-01-01") "All history" else "From ${Fmt.long(LocalDate.parse(sv.effectiveFrom))}") +
                ": weeks start ${sv.value.lowercase().replaceFirstChar(Char::uppercase)}") { vm.launch { vm.repo.deleteSettingVersion(sv.key, sv.effectiveFrom) } }
        }

        SectionTitle("Units")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("lb", "kg").forEach { u ->
                FilterChip(selected = snap.weightUnit == u, onClick = { vm.launch { vm.repo.setSetting(SettingKeys.WEIGHT_UNIT, u) } }, label = { Text("Weight in $u") })
            }
        }

        SectionTitle("Notifications")
        if (!notifGranted) {
            Text("Notifications are off for this app.")
            Button(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
        }
        if (!ReminderScheduler.canExact(ctx)) Text("Exact alarms are unavailable; reminders may be a few minutes late.", style = MaterialTheme.typography.bodySmall)
        Text("Per-task reminder times are set on each task.", style = MaterialTheme.typography.bodySmall)
        val eodOn = snap.setting(SettingKeys.EOD_ENABLED)?.toBoolean() ?: false
        val eodMin = snap.setting(SettingKeys.EOD_MINUTES)?.toIntOrNull() ?: (21 * 60)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("End-of-day summary", Modifier.weight(1f))
            if (eodOn) TextButton(onClick = { pickEod = true }) { Text(Fmt.time(eodMin)) }
            Switch(eodOn, { vm.launch { vm.repo.setSetting(SettingKeys.EOD_ENABLED, it.toString()) } })
        }
        val nag = snap.setting(SettingKeys.BACKUP_REMINDER)?.toBoolean() ?: true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remind me when a backup is overdue", Modifier.weight(1f))
            Switch(nag, { vm.launch { vm.repo.setSetting(SettingKeys.BACKUP_REMINDER, it.toString()) } })
        }

        SectionTitle("Backup")
        val tree = snap.setting(SettingKeys.BACKUP_TREE_URI)?.ifEmpty { null }
        Text("Folder: " + (tree?.let { Uri.decode(Uri.parse(it).lastPathSegment ?: it) } ?: "not chosen"))
        OutlinedButton(onClick = { treeLauncher.launch(null) }) { Text(if (tree == null) "Choose backup folder" else "Change folder") }
        var days by remember(snap) { mutableStateOf(snap.setting(SettingKeys.AUTO_BACKUP_DAYS) ?: "7") }
        var keep by remember(snap) { mutableStateOf(snap.setting(SettingKeys.BACKUP_KEEP) ?: "10") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            NumberField("Auto every N days (0 = off)", days, { days = it; it.toIntOrNull()?.let { n -> vm.launch { vm.repo.setSetting(SettingKeys.AUTO_BACKUP_DAYS, n.toString()) } } }, Modifier.weight(1f))
            NumberField("Keep last", keep, { keep = it; it.toIntOrNull()?.let { n -> vm.launch { vm.repo.setSetting(SettingKeys.BACKUP_KEEP, n.coerceAtLeast(1).toString()) } } }, Modifier.weight(1f))
        }
        val last = snap.setting(SettingKeys.LAST_BACKUP_AT)?.toLongOrNull()
        Text("Last backup: " + (last?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).let { t -> Fmt.long(t.toLocalDate()) + " " + Fmt.timeFull(t.toLocalTime()) } } ?: "never"),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        snap.setting(SettingKeys.LAST_BACKUP_ERROR)?.ifEmpty { null }?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("Automatic backups run in the background once due. Android may delay them by a few hours while the phone is idle; " +
            "they also run when you open the app. You'll be notified if one fails.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(enabled = tree != null && !busy, onClick = {
                busy = true
                scope.launch {
                    message = runCatching { "Saved " + BackupWorker.runBackup(vm.app) }.getOrElse { "Backup failed: ${it.message}" }
                    busy = false
                }
            }) { Text("Back up now") }
            OutlinedButton(onClick = { exportLauncher.launch("habit-backup-${LocalDate.now()}.zip") }) { Text("Export to file…") }
            OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Restore…") }
        }

        SectionTitle("About")
        Text("Halibut Habit Tracker ${BuildConfig.VERSION_NAME} · works fully offline", style = MaterialTheme.typography.bodySmall)
    }

    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, text = { Text(m) })
    }
    dashDialog?.let { value ->
        ScopeDialog("Dashes ${if (value) "count" else "don't count"} as earned", null, onDismiss = { dashDialog = null }) { from ->
            vm.launch { vm.repo.setVersionedSetting(SettingKeys.DASH_COUNTS, value.toString(), from) }
        }
    }
    weekDialog?.let { d ->
        ScopeDialog("Weeks start on ${d.getDisplayName(TextStyle.FULL, Locale.getDefault())}", d, onDismiss = { weekDialog = null }) { from ->
            vm.launch { vm.repo.setVersionedSetting(SettingKeys.WEEK_START, d.name, from) }
        }
    }
    if (pickEod) {
        val m = snap.setting(SettingKeys.EOD_MINUTES)?.toIntOrNull() ?: (21 * 60)
        TimePickDialog(LocalTime.of(m / 60, m % 60), "Summary time", { t -> vm.launch { vm.repo.setSetting(SettingKeys.EOD_MINUTES, (t.hour * 60 + t.minute).toString()) } }, { pickEod = false })
    }
    restoreContent?.let { c -> RestoreDialog(vm, c, onDone = { restoreContent = null; message = it }) }
}

@Composable
private fun HistoryRow(text: String, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
    }
}

/** Asks whether a scoring setting applies to all history or from a date. [weekDay] restricts the date to that weekday. */
@Composable
private fun ScopeDialog(title: String, weekDay: DayOfWeek?, onDismiss: () -> Unit, onApply: (LocalDate?) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val default = weekDay?.let { LocalDate.now().with(TemporalAdjusters.nextOrSame(it)) } ?: LocalDate.now().withDayOfMonth(1)
    var from by remember { mutableStateOf(default) }
    var pick by rememberBool()
    val valid = all || weekDay == null || from.dayOfWeek == weekDay
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!all, { all = false }); Text("From a date forward") }
                if (!all) PickerField("Starting", Fmt.long(from), { pick = true }, Modifier.padding(start = 48.dp))
                if (!valid) Text("Pick a ${weekDay!!.getDisplayName(TextStyle.FULL, Locale.getDefault())}.", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(all, { all = true }); Text("All history (rescores past months)") }
                if (weekDay != null) Text("The week running before the change becomes a short transition week.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(if (all) null else from); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pick) DatePickDialog(from, { from = it }, { pick = false })
}

@Composable
private fun RestoreDialog(vm: MainViewModel, content: BackupContent, onDone: (String?) -> Unit) {
    var mode by remember { mutableStateOf(RestoreMode.REPLACE) }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(mode) { preview = vm.app.backup.preview(content, mode) }
    AlertDialog(
        onDismissRequest = { if (!running) onDone(null) },
        title = { Text("Restore backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Made ${content.createdAt.take(16).replace('T', ' ')} · app ${content.appVersion}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == RestoreMode.REPLACE, { mode = RestoreMode.REPLACE }); Text("Replace everything (a safety backup is made first)") }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == RestoreMode.MERGE, { mode = RestoreMode.MERGE }); Text("Merge (newer edits win)") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                preview?.tables?.filter { it.inBackup > 0 || it.deletes > 0 || it.updates > 0 }?.forEach { t ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(t.table, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (mode == RestoreMode.REPLACE) "${t.inBackup} rows" else "+${t.adds}  ~${t.updates}  −${t.deletes}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } ?: Text("Checking…")
            }
        },
        confirmButton = {
            TextButton(enabled = !running && preview != null, onClick = {
                running = true
                scope.launch {
                    val msg = runCatching {
                        val safety = if (mode == RestoreMode.REPLACE) vm.app.backup.safetyBackup() else null
                        vm.app.backup.restore(content, mode)
                        "Restore complete." + (safety?.let { "\nSafety backup: ${it.substringAfterLast('/')}" } ?: "")
                    }.getOrElse { "Restore failed: ${it.message}" }
                    running = false
                    onDone(msg)
                }
            }) { Text(if (running) "Restoring…" else "Restore") }
        },
        dismissButton = { TextButton(enabled = !running, onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
