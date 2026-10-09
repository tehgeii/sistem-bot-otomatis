package com.pengingatabsen.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pengingatabsen.logic.WeekBucket

/**
 * Grafik kolom bertumpuk kehadiran per minggu: hadir (bawah) + terlewat (atas). Warna dari palet kategorikal yang
 * lolos uji buta warna (biru/oranye; hijau/merah tidak lolos untuk deutan), tiap mode punya langkahnya sendiri.
 * Identitas tidak hanya dari warna: legenda berikon, angka total di ujung kolom, dan rincian minggu yang dipilih.
 */
@Composable
fun WeeklyChartCard(buckets: List<WeekBucket>) {
    if (buckets.isEmpty()) return
    val dark = isSystemInDarkTheme()
    val presentColor = if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
    val missedColor = if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    var selected by remember(buckets) { mutableIntStateOf(buckets.lastIndex) }
    val maxTotal = buckets.maxOf { it.present + it.missed }.coerceAtLeast(1)
    val description = buckets.joinToString("; ") {
        "minggu ${weekLabel(it)}: ${it.present} hadir, ${it.missed} terlewat"
    }

    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Kehadiran per minggu", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                LegendItem(presentColor, "✅ Hadir")
                LegendItem(missedColor, "❌ Terlewat")
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "Grafik kehadiran per minggu. $description" }
                    .pointerInput(buckets) {
                        detectTapGestures { pos ->
                            val band = size.width / buckets.size.toFloat()
                            selected = (pos.x / band).toInt().coerceIn(0, buckets.lastIndex)
                        }
                    },
            ) {
                val labelSpace = 16.dp.toPx()
                val baseline = size.height
                val plotTop = labelSpace
                val band = size.width / buckets.size
                val barWidth = minOf(24.dp.toPx(), band * 0.6f)
                val gap = 2.dp.toPx()
                val radius = CornerRadius(4.dp.toPx())
                fun column(x: Float, top: Float, bottom: Float, color: Color, roundTop: Boolean) {
                    if (bottom - top <= 0f) return
                    val rect = androidx.compose.ui.geometry.Rect(x, top, x + barWidth, bottom)
                    val rr = if (roundTop) {
                        RoundRect(rect, topLeft = radius, topRight = radius, bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero)
                    } else {
                        RoundRect(rect, CornerRadius.Zero)
                    }
                    drawPath(Path().apply { addRoundRect(rr) }, color)
                }
                buckets.forEachIndexed { i, b ->
                    val total = b.present + b.missed
                    val x = band * i + (band - barWidth) / 2
                    val unit = (baseline - plotTop) / maxTotal
                    val presentTop = baseline - b.present * unit
                    column(x, presentTop, baseline, presentColor, roundTop = b.missed == 0)
                    if (b.missed > 0) {
                        // Celah 2dp warna permukaan memisahkan dua segmen (tanpa garis tepi).
                        val missedBottom = if (b.present > 0) presentTop - gap else baseline
                        column(x, baseline - total * unit, missedBottom, missedColor, roundTop = true)
                    }
                    if (total > 0) {
                        val text = measurer.measure(total.toString(), labelStyle.copy(fontWeight = if (i == selected) FontWeight.Bold else null))
                        drawText(
                            text,
                            topLeft = Offset(band * i + (band - text.size.width) / 2, baseline - total * unit - text.size.height - 2.dp.toPx()),
                        )
                    }
                }
                drawLine(axis, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                buckets.forEachIndexed { i, b ->
                    Text(
                        "${b.weekStart.dayOfMonth}/${b.weekStart.monthValue}",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (i == selected) FontWeight.Bold else null,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else labelColor,
                    )
                }
            }
            val b = buckets[selected]
            Text(
                "Minggu ${weekLabel(b)}: " + if (b.present + b.missed == 0) "tidak ada kuliah tercatat" else "${b.present} hadir · ${b.missed} terlewat",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Tap kolom untuk melihat rinciannya. Libur & presensi yang tidak dibuka dosen tidak dihitung.",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

/** "5–11 Okt" atau "29 Sep–5 Okt". */
private fun weekLabel(b: WeekBucket): String {
    val start = b.weekStart
    val end = start.plusDays(6)
    return if (start.month == end.month) "${start.dayOfMonth}–${end.dayOfMonth} ${MONTHS[end.monthValue - 1]}"
    else "${start.dayOfMonth} ${MONTHS[start.monthValue - 1]}–${end.dayOfMonth} ${MONTHS[end.monthValue - 1]}"
}
