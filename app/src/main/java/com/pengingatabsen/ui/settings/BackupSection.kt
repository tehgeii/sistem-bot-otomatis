package com.pengingatabsen.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pengingatabsen.logic.BackupCodec
import java.time.LocalDate

/**
 * Cadangan & pulihkan: jadwal, riwayat, dan pengaturan ke satu file JSON (pilih lokasi sendiri, mis. Drive).
 * NIM/password SiAdin & bot token TIDAK ikut. [restoreOnly] = versi ringkas di wizard HP baru.
 */
@Composable
fun BackupSection(vm: SetupViewModel, restoreOnly: Boolean = false) {
    val context = LocalContext.current
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportBackup(context, it) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.readBackup(context, it) }
    }

    if (restoreOnly) {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Pindah dari HP lama?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pulihkan file cadangan NgiBsen (jadwal, riwayat, pengaturan). Setelah itu lanjutkan langkah ini " +
                        "untuk izin, login SiAdin, dan bot Telegram.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Pulihkan dari cadangan")
                }
            }
        }
    } else {
        Text(
            "Simpan jadwal, riwayat, dan pengaturan ke satu file (mis. di Google Drive) untuk pindah HP atau instal " +
                "ulang. NIM/password SiAdin, bot Telegram, dan foto bukti tidak ikut.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { create.launch(BackupCodec.fileName(LocalDate.now())) }, modifier = Modifier.weight(1f)) {
                Text("Cadangkan")
            }
            OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                Text("Pulihkan")
            }
        }
        // Dibaca ulang setiap kali dialog berubah (mis. setelah pemulihan membuat salinan baru).
        val hasSafety = remember(vm.backupUi) { vm.hasSafetyCopy(context) }
        if (hasSafety) {
            TextButton(onClick = { vm.readSafetyCopy(context) }) { Text("Kembalikan data sebelum pemulihan terakhir") }
        }
    }
    BackupDialog(vm)
}

@Composable
private fun BackupDialog(vm: SetupViewModel) {
    val context = LocalContext.current
    when (val st = vm.backupUi) {
        SetupViewModel.BackupUi.Idle -> Unit
        is SetupViewModel.BackupUi.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Mohon tunggu") },
            text = { Text(st.message) },
            confirmButton = {},
        )
        is SetupViewModel.BackupUi.Preview -> AlertDialog(
            onDismissRequest = { vm.closeBackup() },
            title = { Text("Pulihkan cadangan?") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(st.summary, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmRestore(context) }) { Text("Ganti & pulihkan") } },
            dismissButton = { TextButton(onClick = { vm.closeBackup() }) { Text("Batal") } },
        )
        is SetupViewModel.BackupUi.Done -> AlertDialog(
            onDismissRequest = { vm.closeBackup() },
            title = { Text("Selesai") },
            text = { Text(st.message) },
            confirmButton = { TextButton(onClick = { vm.closeBackup() }) { Text("OK") } },
        )
        is SetupViewModel.BackupUi.Failed -> AlertDialog(
            onDismissRequest = { vm.closeBackup() },
            title = { Text("Tidak berhasil") },
            text = { Text(st.message) },
            confirmButton = { TextButton(onClick = { vm.closeBackup() }) { Text("Tutup") } },
        )
    }
}
