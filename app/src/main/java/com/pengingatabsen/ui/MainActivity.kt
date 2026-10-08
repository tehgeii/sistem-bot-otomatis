package com.pengingatabsen.ui

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
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
import com.pengingatabsen.ui.schedule.ScheduleMenu
import com.pengingatabsen.ui.schedule.ScheduleScreen
import com.pengingatabsen.ui.settings.OnboardingScreen
import com.pengingatabsen.ui.settings.SettingsScreen
import com.pengingatabsen.ui.settings.SetupViewModel
import com.pengingatabsen.ui.theme.PengingatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val setupVm: SetupViewModel by viewModels()
    /** Tab yang diminta lewat intent (mis. notifikasi "Login SiAdin gagal" → Pengaturan). */
    private var requestedTab by mutableIntStateOf(-1)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab = intent.getIntExtra(EXTRA_TAB, -1)
        enableEdgeToEdge()
        setContent {
            PengingatTheme {
                val settings by vm.settings.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(requestedTab) {
                    if (requestedTab in 0..2) tab = requestedTab
                    requestedTab = -1
                }
                val current = settings ?: return@PengingatTheme
                val titles = listOf("Jadwal", "Riwayat", "Pengaturan")

                if (!current.onboardingDone) {
                    Scaffold(topBar = { TopAppBar(title = { Text("Selamat datang di NgiBsen UDINUS") }) }) { padding ->
                        OnboardingScreen(setupVm, Modifier.padding(padding))
                    }
                    return@PengingatTheme
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (tab == 0) "NgiBsen UDINUS" else titles[tab]) },
                            actions = { if (tab == 0) ScheduleMenu(vm) },
                        )
                    },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab = intent.getIntExtra(EXTRA_TAB, -1)
    }

    override fun onResume() {
        super.onResume()
        // Jaga-jaga: pastikan semua alarm terpasang setiap aplikasi dibuka.
        val context = applicationContext
        lifecycleScope.launch {
            AlarmScheduler.rescheduleAll(context)
            // Alarm jam buka yang tak pernah berbunyi (NgiBsen sempat ditahan sistem) → beri tahu.
            vm.setMissedAlarms(com.pengingatabsen.alarm.PresensiCheck.checkMissedAlarms(context))
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_SETTINGS = 2
    }
}
