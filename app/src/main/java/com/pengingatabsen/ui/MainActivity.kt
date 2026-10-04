package com.pengingatabsen.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.pengingatabsen.ui.schedule.ScheduleScreen
import com.pengingatabsen.ui.theme.PengingatTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PengingatTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Pengingat Absen") }) }) { padding ->
                    ScheduleScreen(vm, padding)
                }
            }
        }
    }
}
