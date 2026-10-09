package com.pengingatabsen.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pengingatabsen.data.MEETINGS_RANGE
import com.pengingatabsen.logic.AttendanceRule
import com.pengingatabsen.logic.Formatters
import java.time.Instant
import java.time.ZoneOffset

/**
 * Aturan "jatah tidak hadir": jumlah pertemuan per semester, minimal hadir (%), dan awal semester (riwayat sebelum
 * tanggal itu tidak dihitung). Bawaan 14 pertemuan & 75% → boleh tidak hadir 3 kali. Sesuaikan dengan aturan kampus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceSection(vm: SetupViewModel) {
    val settings by vm.settings.collectAsState()
    val meetings = settings?.meetingsPerSemester ?: AttendanceRule.DEFAULT_MEETINGS
    val percent = settings?.minAttendancePercent ?: AttendanceRule.DEFAULT_MIN_PERCENT
    val start = settings?.semesterStart
    var picking by remember { mutableStateOf(false) }

    Text(
        "Dipakai untuk \"sisa jatah tidak hadir\" di Riwayat dan peringatan bila jatah tinggal 1. " +
            "Hanya \"terlewat\" yang mengurangi jatah (libur & tidak dibuka dosen tidak).",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("Pertemuan per semester: $meetings", modifier = Modifier.weight(1f))
        OutlinedButton(enabled = meetings > MEETINGS_RANGE.first, onClick = { vm.setMeetings(meetings - 1) }) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = meetings < MEETINGS_RANGE.last, onClick = { vm.setMeetings(meetings + 1) }) { Text("+") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Minimal hadir: $percent%", modifier = Modifier.weight(1f))
        OutlinedButton(enabled = percent > 0, onClick = { vm.setMinPercent(percent - 5) }) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = percent < 100, onClick = { vm.setMinPercent(percent + 5) }) { Text("+") }
    }
    Text(
        "→ Boleh tidak hadir paling banyak ${AttendanceRule(meetings, percent).maxAbsent}× per matkul.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            "Hitung sejak: " + (start?.let { Formatters.date(it) } ?: "semua riwayat"),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { picking = true }) { Text("Ubah") }
        if (start != null) TextButton(onClick = { vm.setSemesterStart(null) }) { Text("Semua") }
    }
    Text(
        "Atur ke hari pertama kuliah semester ini, supaya riwayat semester lalu tidak ikut terhitung.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (start ?: java.time.LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { vm.setSemesterStart(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        picking = false
                    },
                ) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Batal") } },
        ) {
            DatePicker(state = state, title = { Text("Awal semester", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}
