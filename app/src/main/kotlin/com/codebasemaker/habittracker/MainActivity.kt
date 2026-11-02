package com.codebasemaker.habittracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.codebasemaker.habittracker.ui.AppRoot
import com.codebasemaker.habittracker.ui.HabitTheme
import com.codebasemaker.habittracker.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val size = calculateWindowSizeClass(this)
            HabitTheme { AppRoot(vm, size.widthSizeClass) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshToday()
    }
}
