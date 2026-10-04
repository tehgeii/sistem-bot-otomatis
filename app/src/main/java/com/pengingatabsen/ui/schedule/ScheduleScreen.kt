package com.pengingatabsen.ui.schedule

import androidx.compose.foundation.clickable
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
import com.pengingatabsen.data.Course
import com.pengingatabsen.data.skipUntil
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.ui.MainViewModel
import java.time.LocalDate

@Composable
fun ScheduleScreen(vm: MainViewModel, contentPadding: PaddingValues) {
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
