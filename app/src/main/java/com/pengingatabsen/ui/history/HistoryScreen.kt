package com.pengingatabsen.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pengingatabsen.data.AttendanceRecord
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.data.toLocalDateTime
import com.pengingatabsen.logic.Allowance
import com.pengingatabsen.logic.AllowanceLevel
import com.pengingatabsen.logic.Allowances
import com.pengingatabsen.logic.AttendanceRule
import com.pengingatabsen.logic.AttendanceStats
import com.pengingatabsen.logic.SummaryItem
import com.pengingatabsen.logic.WeeklyChart
import java.time.LocalDate
import com.pengingatabsen.logic.CourseStats
import com.pengingatabsen.logic.OfficialAttendance
import com.pengingatabsen.logic.OfficialStatus
import androidx.compose.runtime.produceState
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.ui.MainViewModel

/** Riwayat: statistik kehadiran per matkul, filter per matkul, dan daftar kemunculan dengan ikon status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val history by vm.history.collectAsState()
    val appSettings by vm.settings.collectAsState()
    val list = history ?: return
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Belum ada riwayat absen.", textAlign = TextAlign.Center)
        }
        return
    }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    // Hanya kemunculan matkul terjadwal (bukan screenshot "Tanpa matkul") yang masuk statistik,
    // dan hanya sejak awal semester bila diatur (Pengaturan → Kehadiran).
    val semesterStart = appSettings?.semesterStart
    val scheduled = list.filter { it.courseId > 0 }
    val semester = scheduled.filter { Allowances.inSemester(LocalDate.ofEpochDay(it.epochDay), semesterStart) }
    val pairs = semester.map { it.courseName to it.status.toSummaryKind() }
    val appStats = AttendanceStats.perCourse(pairs)
    val rule = appSettings?.attendanceRule ?: AttendanceRule()
    val allowances = Allowances.perCourse(appStats, rule).associateBy { it.courseName }
    // Kehadiran resmi SiAdin (persentase kartu KRS/Presensi Online), dihitung ulang saat riwayat/pengaturan berubah.
    val official by produceState(emptyList<OfficialStatus>(), list, appSettings) {
        value = runCatching { com.pengingatabsen.data.OfficialSync.statuses() }.getOrDefault(emptyList())
    }
    val officialByName = official.associateBy { it.courseName }
    // Matkul yang hanya punya angka resmi (belum ada catatan NgiBsen) tetap ditampilkan.
    val statsByName = appStats.associateBy { it.courseName.orEmpty() }
    val perCourse = (statsByName.keys + officialByName.keys).distinct().sortedBy { it.lowercase() }
        .map { statsByName[it] ?: CourseStats(it, 0, 0, 0, 0) }
    val weeks = WeeklyChart.buckets(
        scheduled.map { SummaryItem(it.courseName, LocalDate.ofEpochDay(it.epochDay), it.status.toSummaryKind()) },
        LocalDate.now(),
    )
    val shown = if (filter == null) list else list.filter { it.courseName == filter }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
    ) {
        item(key = "stats") {
            StatsCard(
                overall = AttendanceStats.overall(pairs),
                perCourse = perCourse,
                allowances = allowances,
                official = officialByName,
                since = semesterStart,
                selected = filter,
                onSelect = { filter = if (filter == it) null else it },
            )
        }
        item(key = "weeks") { WeeklyChartCard(weeks) }
        item(key = "filter") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Semua") })
                perCourse.forEach { s ->
                    val name = s.courseName.orEmpty()
                    FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
                }
            }
        }
        items(shown, key = { it.id }) { record -> HistoryItem(record, onResend = { vm.resend(record) }) }
    }
}

@Composable
private fun StatsCard(
    overall: CourseStats,
    perCourse: List<CourseStats>,
    allowances: Map<String, Allowance>,
    official: Map<String, OfficialStatus>,
    since: LocalDate?,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (since != null) "Kehadiran sejak ${Formatters.date(since)}" else "Kehadiran",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                overall.percent?.let { "$it% hadir · ${overall.present} dari ${overall.counted} pertemuan (catatan NgiBsen)" }
                    ?: "Belum ada pertemuan yang dihitung",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                official.isEmpty() -> Text(
                    "Kehadiran resmi SiAdin belum ada: menu ⋮ → Sinkronkan kehadiran resmi (atau tunggu pengecekan kuliah berikutnya).",
                    style = MaterialTheme.typography.bodySmall,
                )
                since == null -> Text(
                    "Atur awal semester di Pengaturan → Kehadiran supaya jatah tidak hadir bisa diperkirakan dari data SiAdin.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            perCourse.forEach { s ->
                val name = s.courseName.orEmpty()
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onSelect(name) }, modifier = Modifier.weight(1f)) {
                            Text(
                                (if (selected == name) "▶ " else "") + name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            s.percent?.let { "$it% · ${s.present}/${s.counted}" } ?: "—",
                            style = MaterialTheme.typography.labelLarge,
                            color = percentColor(s.percent),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (s.percent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = percentColor(s.percent),
                    )
                    val extra = buildList {
                        if (s.missed > 0) add("${s.missed} terlewat")
                        if (s.holiday > 0) add("${s.holiday} libur")
                        if (s.noSession > 0) add("${s.noSession} tidak dibuka dosen")
                    }
                    if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    val o = official[name]
                    val estimate = o?.let { OfficialAttendance.estimate(it) }
                    if (o != null) {
                        Text(
                            OfficialAttendance.summary(o),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (estimate != null) {
                            val bad = o.reachable == false || (o.remaining ?: 99) <= 1
                            Text(
                                estimate,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    // Jatah dari catatan NgiBsen hanya bila belum ada perkiraan dari data resmi.
                    if (estimate == null) {
                        allowances[name]?.let { a ->
                            Text(
                                (if (o != null) "Catatan NgiBsen · " else "") + Allowances.label(a),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (a.level == AllowanceLevel.SAFE) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun percentColor(percent: Int?): Color = when {
    percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
    percent >= 75 -> Color(0xFF2E7D32)
    percent >= 50 -> Color(0xFFF9A825)
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun HistoryItem(record: AttendanceRecord, onResend: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.courseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${statusIcon(record.status)} ${record.status.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor(record.status),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            val time = (record.doneAtMillis ?: record.openAtMillis).toLocalDateTime()
            Text(
                if (record.doneAtMillis != null) Formatters.dateTime(time) else "${Formatters.date(time.toLocalDate())} ${Formatters.hm(time)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (record.photoPath != null) Text("📷 dengan foto bukti", style = MaterialTheme.typography.bodySmall)
            record.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val canResend = record.doneAtMillis != null &&
                (record.status == RecordStatus.FAILED || record.status == RecordStatus.QUEUED)
            if (canResend) {
                TextButton(onClick = onResend) { Text("Kirim ulang") }
            }
        }
    }
}

private fun statusIcon(status: RecordStatus): String = when (status) {
    RecordStatus.SENT -> "✅"
    RecordStatus.QUEUED -> "📤"
    RecordStatus.FAILED -> "⚠️"
    RecordStatus.MISSED -> "❌"
    RecordStatus.HOLIDAY -> "🏖"
    RecordStatus.NO_SESSION -> "⏸"
    RecordStatus.ACTIVE -> "⏳"
}

@Composable
private fun statusColor(status: RecordStatus): Color = when (status) {
    RecordStatus.SENT, RecordStatus.QUEUED -> Color(0xFF2E7D32)
    RecordStatus.FAILED, RecordStatus.MISSED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
