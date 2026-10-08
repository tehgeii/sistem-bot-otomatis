package com.pengingatabsen.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import com.pengingatabsen.data.Course
import com.pengingatabsen.data.skipUntil
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.logic.ScheduleMath
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import java.time.Instant
import java.time.ZoneOffset
import com.pengingatabsen.ui.MainViewModel
import java.time.LocalDate

/** Menu bagikan/impor jadwal, dipasang di action TopAppBar untuk tab Jadwal. */
@Composable
fun ScheduleMenu(vm: MainViewModel) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }
    var resuming by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu jadwal") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Bagikan jadwal") }, onClick = {
                menu = false
                vm.exportSchedule { text ->
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                "Bagikan jadwal NgiBsen",
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            })
            DropdownMenuItem(text = { Text("Impor dari SiAdin (KRS)") }, onClick = { menu = false; vm.readKrs() })
            DropdownMenuItem(text = { Text("Impor jadwal (teks)") }, onClick = { menu = false; importing = true })
            DropdownMenuItem(text = { Text("Liburkan semua sampai…") }, onClick = { menu = false; pausing = true })
            DropdownMenuItem(text = { Text("Aktifkan semua lagi") }, onClick = { menu = false; resuming = true })
        }
    }

    if (importing) ImportDialog(vm) { importing = false }
    KrsImportDialog(vm)
    if (pausing) PauseAllDialog(vm) { pausing = false }
    if (resuming) {
        AlertDialog(
            onDismissRequest = { resuming = false },
            title = { Text("Aktifkan semua lagi?") },
            text = { Text("Semua libur yang belum lewat (per matkul maupun libur massal) dibatalkan.") },
            confirmButton = { TextButton(onClick = { vm.resumeAll(); resuming = false }) { Text("Aktifkan") } },
            dismissButton = { TextButton(onClick = { resuming = false }) { Text("Batal") } },
        )
    }
}

/** Libur massal: pilih tanggal terakhir libur (UTS/UAS/libur semester). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseAllDialog(vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val todayUtc = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = todayUtc + 6 * 24 * 60 * 60 * 1000L,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val until = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.pauseAll(until)
                        Toast.makeText(context, "Semua jadwal libur s/d ${Formatters.date(until)}", Toast.LENGTH_LONG).show()
                    }
                    onClose()
                },
            ) { Text("Liburkan") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Batal") } },
    ) {
        DatePicker(
            state = state,
            title = { Text("Libur semua sampai tanggal", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}

@Composable
private fun ImportDialog(vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var preview by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Impor jadwal") },
        text = {
            Column {
                Text("Tempel teks jadwal dari NgiBsen (hasil \"Bagikan jadwal\").", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it; vm.previewImport(it.text) { n -> preview = n } },
                    label = { Text("Teks jadwal") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                preview?.let { Text("$it matkul baru akan ditambahkan (yang sudah ada dilewati).", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (preview ?: 0) > 0,
                onClick = {
                    vm.importSchedule(field.text) { n ->
                        Toast.makeText(context, "$n matkul ditambahkan", Toast.LENGTH_LONG).show()
                    }
                    onClose()
                },
            ) { Text("Impor") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Batal") } },
    )
}

@Composable
fun ScheduleScreen(vm: MainViewModel, contentPadding: PaddingValues, onOpenSettings: () -> Unit = {}) {
    val courses by vm.courses.collectAsState()
    var editing by remember { mutableStateOf<Course?>(null) }
    var deleting by remember { mutableStateOf<Course?>(null) }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Course(name = "", dayOfWeek = LocalDate.now().dayOfWeek.value, openMinute = 7 * 60) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Tambah") },
            )
        },
    ) { inner ->
        val list = courses
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(inner).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Belum ada jadwal.\nTekan Tambah, lalu pakai \"Simpan & tambah lagi\" supaya semua matkul terisi sekali duduk.",
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            ) {
                item(key = "today") { TodayCard(vm, onOpenSettings) }
                val pausedUntil = ScheduleMath.allPausedUntil(list.filter { it.active }.map { it.skipUntil }, LocalDate.now())
                if (pausedUntil != null) {
                    item(key = "paused") {
                        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(
                                "🏖 Semua jadwal libur s/d ${Formatters.date(pausedUntil)}. " +
                                    "Batalkan lewat menu ⋮ → Aktifkan semua lagi.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                list.groupBy { it.dayOfWeek }.toSortedMap().forEach { (day, dayCourses) ->
                    item(key = "h$day") {
                        Text(
                            Formatters.dayName(day),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(dayCourses, key = { it.id }) { course ->
                        CourseCard(
                            course = course,
                            onClick = { editing = course },
                            onToggle = { vm.setActive(course, it) },
                            onDuplicate = { editing = course.copy(id = 0, name = course.name, skipUntilEpochDay = null) },
                            onHolidayToday = { vm.holidayToday(course) },
                            onSkipWeek = { vm.skipThisWeek(course) },
                            onClearSkip = { vm.clearSkip(course) },
                            onDelete = { deleting = course },
                        )
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        val isNew = draft.id == 0L
        CourseEditorDialog(
            initial = draft,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onSaveAndNext = { vm.save(it); editing = nextDraftAfter(it) },
        )
    }

    deleting?.let { course ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Hapus ${course.name}?") },
            text = { Text("Riwayat absen matkul ini tetap disimpan.") },
            confirmButton = { TextButton(onClick = { vm.delete(course); deleting = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun CourseCard(
    course: Course,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onHolidayToday: () -> Unit,
    onSkipWeek: () -> Unit,
    onClearSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    val today = LocalDate.now()
    val skipUntil = course.skipUntil?.takeIf { !it.isBefore(today) }
    var menu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                val detail = buildString {
                    append(Formatters.window(course.openMinute, course.closeMinute))
                    course.room?.let { append(" · Ruang ").append(it) }
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                if (skipUntil != null) {
                    Text(
                        "Libur s/d ${Formatters.date(skipUntil)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Switch(checked = course.active, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Duplikat") }, onClick = { menu = false; onDuplicate() })
                    if (course.dayOfWeek == today.dayOfWeek.value) {
                        DropdownMenuItem(text = { Text("Libur hari ini") }, onClick = { menu = false; onHolidayToday() })
                    }
                    DropdownMenuItem(text = { Text("Lewati minggu ini") }, onClick = { menu = false; onSkipWeek() })
                    if (skipUntil != null) {
                        DropdownMenuItem(text = { Text("Batalkan libur") }, onClick = { menu = false; onClearSkip() })
                    }
                    DropdownMenuItem(text = { Text("Hapus") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Impor jadwal dari KRS SiAdin: membaca → pratinjau → tambah yang baru / ganti semua. */
@Composable
private fun KrsImportDialog(vm: MainViewModel) {
    val state by vm.krsImport.collectAsState()
    when (val st = state) {
        MainViewModel.KrsImport.Idle -> Unit
        MainViewModel.KrsImport.Loading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Membaca KRS SiAdin…") },
            text = { Text("Login otomatis & membaca halaman Akademik → KRS. Bisa sampai ±1 menit.") },
            confirmButton = {},
        )
        is MainViewModel.KrsImport.Failed -> AlertDialog(
            onDismissRequest = { vm.closeKrs() },
            title = { Text("Gagal membaca KRS") },
            text = { Text(st.message) },
            confirmButton = { TextButton(onClick = { vm.readKrs() }) { Text("Coba lagi") } },
            dismissButton = { TextButton(onClick = { vm.closeKrs() }) { Text("Tutup") } },
        )
        is MainViewModel.KrsImport.Done -> AlertDialog(
            onDismissRequest = { vm.closeKrs() },
            title = { Text("Impor selesai") },
            text = { Text(if (st.added == 0) "Tidak ada jadwal baru — semuanya sudah ada." else "${st.added} jadwal ditambahkan. Alarm sudah dipasang.") },
            confirmButton = { TextButton(onClick = { vm.closeKrs() }) { Text("OK") } },
        )
        is MainViewModel.KrsImport.Ready -> AlertDialog(
            onDismissRequest = { vm.closeKrs() },
            title = { Text("Jadwal dari KRS") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "${st.all.size} jadwal terbaca, ${st.fresh.size} belum ada di NgiBsen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    st.all.sortedWith(compareBy({ it.dayOfWeek }, { it.openMinute })).forEach { c ->
                        val isNew = c in st.fresh
                        Text(
                            (if (isNew) "➕ " else "✓ ") + "${c.name} · ${Formatters.dayName(c.dayOfWeek)} " +
                                Formatters.window(c.openMinute, c.closeMinute) + (c.room?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        "➕ = akan ditambah · ✓ = sudah ada. \"Ganti semua\" menghapus jadwal lama (untuk semester baru); " +
                            "riwayat tetap tersimpan.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = st.fresh.isNotEmpty(), onClick = { vm.importKrs(replaceAll = false) }) {
                    Text("Tambah ${st.fresh.size} baru")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.importKrs(replaceAll = true) }) { Text("Ganti semua") }
                    TextButton(onClick = { vm.closeKrs() }) { Text("Batal") }
                }
            },
        )
    }
}
