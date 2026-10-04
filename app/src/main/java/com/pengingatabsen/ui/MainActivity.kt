package com.pengingatabsen.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.lifecycleScope
import com.pengingatabsen.alarm.AlarmScheduler
import com.pengingatabsen.ui.history.HistoryScreen
import com.pengingatabsen.ui.schedule.ScheduleScreen
import com.pengingatabsen.ui.settings.OnboardingScreen
import com.pengingatabsen.ui.settings.SettingsScreen
import com.pengingatabsen.ui.settings.SetupViewModel
import com.pengingatabsen.ui.theme.PengingatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val setupVm: SetupViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PengingatTheme {
                val settings by vm.settings.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val current = settings ?: return@PengingatTheme
                val titles = listOf("Jadwal", "Riwayat", "Pengaturan")

                if (!current.onboardingDone) {
                    Scaffold(topBar = { TopAppBar(title = { Text("Selamat datang di Pengingat Absen") }) }) { padding ->
                        OnboardingScreen(setupVm, Modifier.padding(padding))
                    }
                    return@PengingatTheme
                }

                Scaffold(
                    topBar = { TopAppBar(title = { Text(if (tab == 0) "Pengingat Absen" else titles[tab]) }) },
                    bottomBar = {
                        NavigationBar {
                            val icons = listOf(Icons.Filled.DateRange, Icons.Filled.List, Icons.Filled.Settings)
                            titles.forEachIndexed { i, title ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icons[i], contentDescription = null) },
                                    label = { Text(title) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> ScheduleScreen(vm, padding)
                        1 -> HistoryScreen(vm, padding)
                        else -> SettingsScreen(setupVm, padding)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Jaga-jaga: pastikan semua alarm terpasang setiap aplikasi dibuka.
        val context = applicationContext
        lifecycleScope.launch { AlarmScheduler.rescheduleAll(context) }
    }
}
