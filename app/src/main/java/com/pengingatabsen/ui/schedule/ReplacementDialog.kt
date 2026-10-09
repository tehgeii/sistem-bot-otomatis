package com.pengingatabsen.ui.schedule

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pengingatabsen.data.Course
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.logic.ScheduleMath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Data kelas pengganti yang dipilih di [ReplacementDialog]. */
data class ReplacementDraft(
    val date: LocalDate,
    val openMinute: Int,
    val closeMinute: Int?,
    val room: String?,
    /** Kemunculan jadwal biasa yang ikut diliburkan, atau null. */
    val skipRegularOn: LocalDate?,
)

/**
 * Kelas pengganti untuk [source]: dosen memindah jadwal ke tanggal/jam lain (sekali saja).
 * Nama matkul sama persis, jadi kartu presensi SiAdin tetap dikenali.
 */
@Composable
fun ReplacementDialog(source: Course, onDismiss: () -> Unit, onSave: (ReplacementDraft) -> Unit) {
    val context = LocalContext.current
    val now = remember { LocalDateTime.now() }
    var date by remember { mutableStateOf(now.toLocalDate()) }
    var open by remember { mutableIntStateOf(source.openMinute) }
    var close by remember { mutableStateOf(source.closeMinute) }
    var room by remember { mutableStateOf(source.room.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }

    val replaced = ScheduleMath.replacedOccurrence(source.toSlot(), date, now)
    // Kelas yang sedang berlangsung hari ini tidak dicentang otomatis (meliburkannya menghentikan pengingat
    // yang sedang berjalan); pengguna mencentang sendiri bila memang kelas hari ini yang dipindah.
    val ongoing = replaced != null && ScheduleMath.currentOccurrence(source.toSlot(), now)?.date == replaced
    var skipRegular by remember(replaced) { mutableStateOf(!ongoing) }
    val closeInvalid = close != null && close!! <= open
    val end = ScheduleMath.occurrenceOn(com.pengingatabsen.logic.Slot(date.dayOfWeek.value, open, close), date).end
    val alreadyOver = !end.isAfter(LocalDateTime.now())
    val valid = !closeInvalid && !alreadyOver

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kelas pengganti") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(source.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Sekali saja, pada tanggal yang dipilih. Pengingat & pengecekan SiAdin berjalan seperti biasa.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Tanggal: ${Formatters.date(date)}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime(open) { open = it } }, modifier = Modifier.weight(1f)) {
                        Text("Buka ${Formatters.hm(open)}")
                    }
                    OutlinedButton(
                        onClick = { pickTime(close ?: (open + 30).coerceAtMost(23 * 60 + 59)) { close = it } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(close?.let { "Tutup ${Formatters.hm(it)}" } ?: "Tutup (opsional)")
                    }
                }
                when {
                    closeInvalid -> Text(
                        "Jam tutup harus setelah jam buka.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    alreadyOver -> Text(
                        "Jam kuliah itu sudah lewat. Pilih tanggal/jam lain.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Ruang (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (replaced != null && replaced != date) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Checkbox(checked = skipRegular, onCheckedChange = { skipRegular = it })
                        Text(
                            "Liburkan jadwal biasa ${Formatters.date(replaced)}" +
                                if (ongoing) " (hari ini, sedang berlangsung)" else " (yang digantikan)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (replaced == date) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Checkbox(checked = skipRegular, onCheckedChange = { skipRegular = it })
                        Text(
                            "Liburkan jadwal biasa hari itu (jam ${Formatters.window(source.openMinute, source.closeMinute)})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        "Jadwal biasa tetap berjalan. Bila perlu diliburkan, pakai menu ⋮ → Lewati minggu ini pada minggunya.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        ReplacementDraft(
                            date = date,
                            openMinute = open,
                            closeMinute = close,
                            room = room.trim().ifBlank { null },
                            skipRegularOn = replaced?.takeIf { skipRegular },
                        ),
                    )
                },
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )

    if (pickingDate) {
        OneOffDatePicker(initial = date, onDismiss = { pickingDate = false }) {
            date = it
            pickingDate = false
        }
    }
}

/** Pemilih tanggal (hari ini atau setelahnya) untuk kelas pengganti. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneOffDatePicker(initial: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val todayUtc = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli().coerceAtLeast(todayUtc),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        ?: onDismiss()
                },
            ) { Text("Pilih") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    ) {
        DatePicker(
            state = state,
            title = { Text("Tanggal kelas pengganti", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}
