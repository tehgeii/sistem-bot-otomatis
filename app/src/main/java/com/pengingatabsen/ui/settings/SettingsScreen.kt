package com.pengingatabsen.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: SetupViewModel, contentPadding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        val settings by vm.settings.collectAsState()
        val smart = settings?.smartModeActive == true
        // Urutan: ringkasan status, yang sering dipakai, izin; bagian teknis dilipat di "Lanjutan".
        StatusSummary(vm)
        SectionTitle("SiAdin web")
        SiadinWebSection(vm)
        RadarSection(vm)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Pengingat")
        VibrateOnlySection(vm)
        IntervalSection(vm)
        Text(
            if (smart) {
                "Mode pintar: SiAdin dicek otomatis dan HP baru bergetar saat presensi dibuka dosen. " +
                    "Pengingat berhenti sendiri begitu SiAdin menampilkan \"Berhasil Presensi\", " +
                    "atau saat kamu menekan \"Libur\"."
            } else {
                "Pengingat berhenti begitu kamu menekan \"Sudah, kirim bukti\" atau \"Libur\". " +
                    "Jika tidak, berhenti saat absen ditutup (atau 30 menit setelah dibuka bila jam tutup kosong). " +
                    "Notifikasi terakhir muncul 5 menit sebelum ditutup."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        BeforeClassSection(vm)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Telegram (bukti absen)")
        TelegramSection(vm)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Kehadiran")
        AttendanceSection(vm)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Izin HP")
        PermissionsSection()
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Cadangan data")
        BackupSection(vm)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        var advanced by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (advanced) "▲ Sembunyikan lanjutan" else "▼ Lanjutan: diagnosis & aplikasi tujuan")
        }
        if (advanced) {
            SectionTitle("Diagnosis")
            DiagnosisSection(vm)
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle("Aplikasi tujuan")
            TargetAppSection(vm)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Tentang & versi")
        AboutSection(vm)
        Spacer(Modifier.height(32.dp))
    }
}
