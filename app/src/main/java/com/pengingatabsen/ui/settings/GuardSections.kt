package com.pengingatabsen.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pengingatabsen.alarm.PhoneStatus
import com.pengingatabsen.logic.PreClass

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** Pengingat sebelum kuliah + cek mode senyap/Jangan Ganggu + baterai (bagian "Pengingat"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeforeClassSection(vm: SetupViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val s = settings ?: return

    ToggleRow(
        "Pengingat sebelum kuliah",
        "Notifikasi ${s.preClassLead} menit sebelum kuliah mulai (nama matkul & ruang).",
        s.preClassReminder,
    ) { vm.setPreClass(it, context) }
    if (s.preClassReminder) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Berapa menit sebelumnya:", style = MaterialTheme.typography.bodySmall)
            PreClass.LEAD_CHOICES.forEach { m ->
                FilterChip(selected = s.preClassLead == m, onClick = { vm.setPreClassLead(m, context) }, label = { Text("$m") })
            }
        }
    }

    ToggleRow(
        "Cek mode senyap & Jangan Ganggu",
        "Sebelum & saat kuliah mulai: bila HP mode Senyap (getar mati) atau Jangan Ganggu menahan NgiBsen, " +
            "kamu diberi tahu supaya getar presensi tetap terasa.",
        s.quietCheck,
    ) { vm.setQuietCheck(it, context) }
    if (s.quietCheck) {
        Text(
            "Supaya NgiBsen tetap bergetar saat Jangan Ganggu: buka pengaturan notifikasi presensi lalu aktifkan " +
                "\"Abaikan Jangan Ganggu\" (di Android baru bisa juga lewat Mode → Jangan Ganggu → Aplikasi → NgiBsen).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val channel = com.pengingatabsen.alarm.Notifications.presensiChannel(s.vibrateOnly)
            OutlinedButton(onClick = { runCatching { context.startActivity(PhoneStatus.dndSettingsIntent(context, channel)) } }) {
                Text("Atur Jangan Ganggu")
            }
            if (s.dndAllowed) {
                TextButton(onClick = { vm.setDndAllowed(false) }) { Text("Cek lagi Jangan Ganggu") }
            }
        }
        if (s.dndAllowed) {
            Text("✓ Kamu menandai NgiBsen sudah diizinkan menembus Jangan Ganggu.", style = MaterialTheme.typography.bodySmall)
        }
    }

    ToggleRow(
        "Cek baterai sebelum kuliah",
        "Baterai ≤${com.pengingatabsen.logic.PhoneCheck.LOW_BATTERY}% dan tidak dicas → diingatkan untuk mengecas, " +
            "supaya HP tidak mati saat presensi dibuka.",
        s.batteryCheck,
    ) { vm.setBatteryCheck(it, context) }
}

/** Radar presensi di luar jadwal & perubahan jadwal KRS (bagian "SiAdin web", butuh mode pintar). */
@Composable
fun RadarSection(vm: SetupViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val s = settings ?: return
    val smart = s.smartModeActive

    ToggleRow(
        "Radar presensi di luar jadwal",
        "Memberi tahu bila presensi matkulmu dibuka di luar jam jadwal NgiBsen (dimajukan, susulan, kelas pengganti " +
            "yang belum dicatat). Memakai pengecekan yang sudah berjalan + cek ringan tiap ±30 menit di hari kuliah " +
            "07.00–17.30 (data seluler: paling sering tiap ±1 jam)." + if (smart) "" else " Butuh login SiAdin tersimpan.",
        s.radar,
        enabled = smart,
    ) { vm.setRadar(it, context) }

    ToggleRow(
        "Beri tahu bila jadwal di KRS berubah",
        "Saat KRS dibaca (ringkasan mingguan / Sinkronkan), hari & jam kuliah dibandingkan dengan jadwal NgiBsen. " +
            "Bila berbeda, muncul pemberitahuan dan tombol Terapkan di layar Jadwal.",
        s.scheduleDiffCheck,
        enabled = smart,
    ) { vm.setScheduleDiffCheck(it) }
}

/** Pesan Telegram cadangan (bagian "Telegram"). */
@Composable
fun TelegramNudgeRow(vm: SetupViewModel) {
    val settings by vm.settings.collectAsState()
    val s = settings ?: return
    ToggleRow(
        "Pesan cadangan bila presensi belum ditekan",
        "Presensi sudah dibuka ±5 menit tapi belum kamu tekan → sekali kirim pesan ke Telegram (ikut muncul di laptop/" +
            "jam tangan yang login Telegram). Juga untuk radar presensi di luar jadwal. Butuh mode pintar SiAdin.",
        s.telegramNudge,
    ) { vm.setTelegramNudge(it) }
}
