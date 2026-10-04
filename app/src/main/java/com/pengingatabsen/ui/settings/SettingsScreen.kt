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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: SetupViewModel, contentPadding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        SectionTitle("Izin")
        PermissionsSection()
        HorizontalDivider()
        SectionTitle("SiAdin web")
        SiadinWebSection(vm)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Aplikasi tujuan")
        TargetAppSection(vm)
        HorizontalDivider()
        SectionTitle("Pengingat")
        VibrateOnlySection(vm)
        IntervalSection(vm)
        Text(
            "Pengingat berhenti begitu kamu menekan \"Sudah, kirim bukti\" atau \"Libur\". " +
                "Jika tidak, berhenti saat absen ditutup (atau 30 menit setelah dibuka bila jam tutup kosong). " +
                "Notifikasi terakhir muncul 5 menit sebelum ditutup.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle("Telegram")
        TelegramSection(vm)
        Spacer(Modifier.height(32.dp))
    }
}
