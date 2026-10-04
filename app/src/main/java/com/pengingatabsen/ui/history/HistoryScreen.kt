package com.pengingatabsen.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pengingatabsen.data.AttendanceRecord
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.data.toLocalDateTime
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.ui.MainViewModel

@Composable
fun HistoryScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val history by vm.history.collectAsState()
    val list = history ?: return
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Belum ada riwayat absen.", textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
    ) {
        items(list, key = { it.id }) { record -> HistoryItem(record, onResend = { vm.resend(record) }) }
    }
}

@Composable
private fun HistoryItem(record: AttendanceRecord, onResend: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.courseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(record.status.label, color = statusColor(record.status)) })
            }
            val time = (record.doneAtMillis ?: record.openAtMillis).toLocalDateTime()
            Text(
                if (record.doneAtMillis != null) Formatters.dateTime(time) else "${Formatters.date(time.toLocalDate())} ${Formatters.hm(time)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (record.photoPath != null) Text("📷 dengan screenshot", style = MaterialTheme.typography.bodySmall)
            record.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val canResend = record.doneAtMillis != null &&
                (record.status == RecordStatus.FAILED || record.status == RecordStatus.QUEUED)
            if (canResend) {
                TextButton(onClick = onResend) { Text("Kirim ulang") }
            }
        }
    }
}

@Composable
private fun statusColor(status: RecordStatus): Color = when (status) {
    RecordStatus.SENT -> MaterialTheme.colorScheme.primary
    RecordStatus.FAILED, RecordStatus.MISSED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
