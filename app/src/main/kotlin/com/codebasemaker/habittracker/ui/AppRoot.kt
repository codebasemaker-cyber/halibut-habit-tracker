package com.codebasemaker.habittracker.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.codebasemaker.habittracker.data.SettingKeys

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("month", "Month", Icons.Default.DateRange),
    Dest("day", "Day", Icons.Default.CheckCircle),
    Dest("tasks", "Tasks", Icons.AutoMirrored.Filled.List),
    Dest("trackers", "Trackers", Icons.Default.Edit),
    Dest("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel, width: WindowWidthSizeClass) {
    val snap by vm.snapshot.collectAsState()
    val nav = rememberNavController()
    // Inner (unfolded) screen opens on the month grid, cover screen on the day checklist.
    val start = rememberSaveable { if (width == WindowWidthSizeClass.Compact) "day" else "month" }
    val editor = remember { mutableStateOf<EditorRequest?>(null) }
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route?.substringBefore('/')
    val compact = width == WindowWidthSizeClass.Compact

    fun go(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    CompositionLocalProvider(LocalEditor provides editor) {
        Scaffold(
            bottomBar = {
                if (compact) NavigationBar {
                    DESTS.forEach { d ->
                        NavigationBarItem(selected = route == d.route || (route == "task" && d.route == "tasks") || (route == "tracker" && d.route == "trackers"),
                            onClick = { go(d.route) }, icon = { Icon(d.icon, null) }, label = { Text(d.label) })
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (!compact) NavigationRail {
                    DESTS.forEach { d ->
                        NavigationRailItem(selected = route == d.route || (route == "task" && d.route == "tasks") || (route == "tracker" && d.route == "trackers"),
                            onClick = { go(d.route) }, icon = { Icon(d.icon, null) }, label = { Text(d.label) })
                    }
                }
                Box(Modifier.weight(1f)) {
                    val s = snap
                    if (s == null) Text("Loading…", Modifier.padding(24.dp))
                    else Graph(vm, nav, start, s)
                }
            }
        }
        snap?.let { EditorHost(vm, it, editor) }
        snap?.let { if (it.setting(SettingKeys.SEEDED) == null) FirstRunDialog(vm) }
    }
}

@Composable
private fun Graph(vm: MainViewModel, nav: NavHostController, start: String, snap: com.codebasemaker.habittracker.data.AppSnapshot) {
    NavHost(nav, startDestination = start) {
        composable("month") { MonthScreen(vm, snap) }
        composable("day") { DayScreen(vm, snap) }
        composable("tasks") { TasksScreen(vm, snap) { nav.navigate("task/$it") } }
        composable("task/{id}") { e -> TaskEditorScreen(vm, snap, e.arguments?.getString("id") ?: "new") { nav.popBackStack() } }
        composable("trackers") { TrackersScreen(vm, snap) { nav.navigate("tracker/$it") } }
        composable("tracker/{id}") { e -> TrackerEditorScreen(vm, snap, e.arguments?.getString("id") ?: "new") { nav.popBackStack() } }
        composable("settings") { SettingsScreen(vm, snap) }
    }
}

@Composable
private fun FirstRunDialog(vm: MainViewModel) {
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Welcome") },
        text = {
            Column {
                Text("Your Section 2 trackers (sleep, steps, weigh-in…) are set up from your spreadsheet.")
                Text("\nStart with example habit tasks (water, steps, stretch, clean office, clean car…) that you can rename, edit or delete? " +
                    "They start on the 1st of this month.")
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.launch { vm.repo.seed(true) }; notif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Use examples") }
        },
        dismissButton = {
            TextButton(onClick = { vm.launch { vm.repo.seed(false) }; notif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Start empty") }
        },
    )
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
