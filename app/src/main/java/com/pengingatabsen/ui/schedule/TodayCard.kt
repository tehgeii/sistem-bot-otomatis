package com.pengingatabsen.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pengingatabsen.launch.LaunchTargetActivity
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.logic.Readiness
import com.pengingatabsen.logic.ScheduleMath
import com.pengingatabsen.logic.TodayCourse
import com.pengingatabsen.logic.TodayItem
import com.pengingatabsen.logic.TodayPlan
import com.pengingatabsen.logic.TodayState
import com.pengingatabsen.ui.MainViewModel
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * Layar "Hari ini" (di atas daftar jadwal): matkul hari ini dengan status langsung, hitung mundur, dan tombol
 * Buka presensi; hasil cek kesiapan & alarm terlewat. Hari tanpa kuliah → matkul berikutnya.
 */
@Composable
fun TodayCard(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val courses by vm.courses.collectAsState()
    val history by vm.history.collectAsState()
    val settings by vm.settings.collectAsState()
    val missed by vm.missedAlarms.collectAsState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }

    val active = courses?.filter { it.active } ?: return
    if (active.isEmpty()) return
    val smart = settings?.smartModeActive == true
    val records = history.orEmpty().associateBy { it.courseId to it.epochDay }
    val openKeys = settings?.presensiOpenKeys.orEmpty()
    val view = TodayPlan.build(
        courses = active.map { TodayCourse(it.id, it.name, it.room, it.toSlot()) },
        now = now,
        record = { id, day -> records[id to day]?.status?.toSummaryKind() },
        presensiOpen = { id, day -> "$id:$day" in openKeys },
        graceMinutes = if (smart) ScheduleMath.SMART_GRACE_MINUTES else 0,
    )

    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Hari ini · ${Formatters.date(now.toLocalDate())}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (view.items.isEmpty()) {
                Text("Tidak ada kuliah hari ini 🎉", style = MaterialTheme.typography.titleMedium)
            }
            view.items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                TodayRow(item, now) {
                    context.startActivity(LaunchTargetActivity.intent(context, item.courseId, item.epochDay))
                }
            }
            view.next?.let { next ->
                val day = when (next.open.toLocalDate()) {
                    now.toLocalDate().plusDays(1) -> "Besok"
                    else -> Formatters.dayName(next.open.dayOfWeek.value)
                }
                Text(
                    "Berikutnya: ${next.name} · $day ${Formatters.hm(next.open)} (${Readiness.countdown(now, next.open)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Kesiapan & alarm terlewat: hanya bila relevan, tap → Pengaturan.
            val readinessText = settings?.readinessText
            if (smart && readinessText != null) {
                val ok = settings?.readinessOk == true
                Text(
                    (if (ok) "✅ Kesiapan: " else "⚠️ Kesiapan: ") + readinessText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error,
                )
            }
            if (missed.isNotEmpty()) {
                Text(
                    "⚠️ Alarm terlewat: ${missed.joinToString()} — HP sempat menahan NgiBsen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if ((smart && settings?.readinessOk == false) || missed.isNotEmpty()) {
                OutlinedButton(onClick = onOpenSettings) { Text("Perbaiki di Pengaturan") }
            }
        }
    }
}

@Composable
private fun TodayRow(item: TodayItem, now: LocalDateTime, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val room = item.room?.let { " · $it" }.orEmpty()
                Text("${Formatters.hm(item.open)}–${Formatters.hm(item.end)}$room", style = MaterialTheme.typography.bodySmall)
            }
            StatusChip(item.state)
        }
        when (item.state) {
            TodayState.UPCOMING -> Text(
                "Dibuka ${Formatters.hm(item.open)} · ${Readiness.countdown(now, item.open)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            TodayState.OPEN -> Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Presensi sekarang") }
            TodayState.WAITING -> OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Buka halaman presensi") }
            else -> Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun StatusChip(state: TodayState) {
    val (label, color) = when (state) {
        TodayState.UPCOMING -> "🕒 Nanti" to MaterialTheme.colorScheme.onPrimaryContainer
        TodayState.WAITING -> "⏳ Menunggu" to MaterialTheme.colorScheme.onPrimaryContainer
        TodayState.OPEN -> "🔵 Dibuka!" to MaterialTheme.colorScheme.primary
        TodayState.DONE -> "✅ Berhasil" to Color(0xFF2E7D32)
        TodayState.FAILED -> "⚠️ Bukti gagal" to MaterialTheme.colorScheme.error
        TodayState.HOLIDAY -> "🏖 Libur" to MaterialTheme.colorScheme.onPrimaryContainer
        TodayState.MISSED -> "❌ Terlewat" to MaterialTheme.colorScheme.error
        TodayState.NO_SESSION -> "⏸ Tidak dibuka" to MaterialTheme.colorScheme.onPrimaryContainer
        TodayState.ENDED -> "Selesai" to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Text(label, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(start = 8.dp))
}
