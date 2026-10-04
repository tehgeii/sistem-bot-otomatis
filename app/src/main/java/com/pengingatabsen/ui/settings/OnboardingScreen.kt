package com.pengingatabsen.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Wizard sekali jalan. Setiap langkah boleh dilewati; bisa diatur lagi di Pengaturan. */
@Composable
fun OnboardingScreen(vm: SetupViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Izin", "Aplikasi tujuan", "Telegram")

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            "Langkah ${step + 1} dari 3: ${titles[step]}",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> {
                    Text("Supaya pengingat muncul tepat waktu walau HP sedang tidur.")
                    PermissionsSection(autoRequest = true)
                }
                1 -> {
                    Text("Aplikasi yang dibuka saat menekan \"Absen sekarang\". Dinusverse dipilih otomatis bila terpasang.")
                    Spacer(Modifier.padding(4.dp))
                    TargetAppSection(vm)
                }
                else -> TelegramSection(vm)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (step > 0) step-- else vm.finishOnboarding(context) }) {
                Text(if (step > 0) "Kembali" else "Lewati semua")
            }
            Button(onClick = { if (step < 2) step++ else vm.finishOnboarding(context) }) {
                Text(if (step < 2) "Lanjut" else "Selesai")
            }
        }
    }
}
