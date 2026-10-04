package com.pengingatabsen.ui.schedule

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.pengingatabsen.data.Course
import com.pengingatabsen.logic.Formatters

/**
 * Dialog tambah/ubah matkul.
 * [onSaveAndNext] (hanya untuk matkul baru) menyimpan lalu langsung menyiapkan isian berikutnya.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseEditorDialog(
    initial: Course,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit,
    onSaveAndNext: (Course) -> Unit,
) {
    val context = LocalContext.current
    // Kunci state pada objek awal supaya "tambah lagi" mengosongkan isian.
    var name by remember(initial) { mutableStateOf(initial.name) }
    var day by remember(initial) { mutableIntStateOf(initial.dayOfWeek) }
    var open by remember(initial) { mutableIntStateOf(initial.openMinute) }
    var close by remember(initial) { mutableStateOf(initial.closeMinute) }
    var room by remember(initial) { mutableStateOf(initial.room.orEmpty()) }
    var active by remember(initial) { mutableStateOf(initial.active) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(initial) { runCatching { focus.requestFocus() } }

    val closeInvalid = close != null && close!! <= open
    val valid = name.isNotBlank() && !closeInvalid

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, true).show()
    }

    fun build() = initial.copy(
        name = name.trim(),
        dayOfWeek = day,
        openMinute = open,
        closeMinute = close,
        room = room.trim().ifBlank { null },
        active = active,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Tambah matkul" else "Ubah matkul") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama mata kuliah") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                Text("Hari", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in 1..7) {
                        FilterChip(
                            selected = day == d,
                            onClick = { day = d },
                            label = { Text(Formatters.dayName(d).take(3)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime(open) { open = it } }, modifier = Modifier.weight(1f)) {
                        Text("Buka ${Formatters.hm(open)}")
                    }
                    OutlinedButton(
                        onClick = { pickTime(close ?: (open + 30)) { close = it } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(close?.let { "Tutup ${Formatters.hm(it)}" } ?: "Tutup (opsional)")
                    }
                }
                if (close != null) {
                    TextButton(onClick = { close = null }) { Text("Hapus jam tutup") }
                }
                if (closeInvalid) {
                    Text(
                        "Jam tutup harus setelah jam buka.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (close == null) {
                    Text(
                        "Tanpa jam tutup, pengingat berhenti 30 menit setelah dibuka.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Ruang (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Aktif", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(enabled = valid, onClick = { onSave(build()) }) { Text("Simpan") }
                if (isNew) {
                    TextButton(enabled = valid, onClick = { onSaveAndNext(build()) }) { Text("Simpan & tambah lagi") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

/** Isian awal untuk matkul berikutnya pada mode tambah beruntun: hari sama, jam lanjut. */
fun nextDraftAfter(saved: Course): Course {
    val duration = saved.closeMinute?.let { it - saved.openMinute }
    val nextOpen = (saved.closeMinute ?: saved.openMinute).coerceAtMost(23 * 60)
    val nextClose = duration?.let { (nextOpen + it).takeIf { c -> c < 24 * 60 } }
    return Course(dayOfWeek = saved.dayOfWeek, name = "", openMinute = nextOpen, closeMinute = nextClose)
}
