package com.pengingatabsen.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pengingatabsen.logic.AppUpdate
import com.pengingatabsen.update.UpdateChecker

/**
 * Versi terpasang, sidik jari sertifikat (untuk memastikan APK asli dari Release repo), dan pemberitahuan versi baru.
 */
@Composable
fun AboutSection(vm: SetupViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val mine = remember { vm.installedVersion(context) }

    Text(
        mine?.let { "NgiBsen ${it.versionName} (kode ${it.versionCode})" } ?: "NgiBsen",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("Sidik jari sertifikat (SHA-256):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Text(AppUpdate.pretty(mine?.certSha256), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    when (vm.certMatches) {
        true -> Text("✅ Sama dengan rilis resmi di Release repo.", style = MaterialTheme.typography.bodySmall)
        false -> Text(
            "⚠️ BERBEDA dengan rilis resmi — APK ini bukan dari Release repo. Jangan isi password di aplikasi ini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        null -> Unit
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Beri tahu bila ada versi baru")
            Text(
                "Cek Release GitHub sehari sekali (±200 byte). Hanya memberi tahu; pasang tetap kamu yang lakukan.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = settings?.updateCheck ?: true, onCheckedChange = { vm.setUpdateCheck(it, context) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(enabled = !vm.checkingUpdate, onClick = { vm.checkUpdateNow(context) }, modifier = Modifier.weight(1f)) {
            Text("Cek sekarang")
        }
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("Halaman unduhan") }
    }
    vm.updateStatus?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (vm.updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
