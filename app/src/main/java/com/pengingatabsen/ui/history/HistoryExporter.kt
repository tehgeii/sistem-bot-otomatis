package com.pengingatabsen.ui.history

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.pengingatabsen.Graph
import com.pengingatabsen.data.AttendanceRecord
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.data.toLocalDateTime
import com.pengingatabsen.logic.ExportRow
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.logic.HistoryExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime

/** Ekspor riwayat ke CSV (Excel/Sheets) atau PDF (siap cetak/kirim), ke file yang dipilih pengguna. */
object HistoryExporter {
    /** Label status untuk laporan (lebih jelas daripada label singkat di aplikasi). */
    private fun statusText(s: RecordStatus): String = when (s) {
        RecordStatus.SENT -> "hadir (bukti terkirim)"
        RecordStatus.QUEUED -> "hadir (bukti antre)"
        RecordStatus.FAILED -> "hadir (bukti gagal terkirim)"
        RecordStatus.MISSED -> "terlewat"
        RecordStatus.HOLIDAY -> "libur"
        RecordStatus.NO_SESSION -> "tidak dibuka dosen"
        RecordStatus.ACTIVE -> "berlangsung"
    }

    /** Hanya kemunculan matkul terjadwal (screenshot "Tanpa matkul" tidak punya tanggal kuliah). */
    suspend fun rows(): List<ExportRow> = Graph.db.recordDao().all().filter { it.courseId > 0 }.map(::toRow)

    private fun toRow(r: AttendanceRecord) = ExportRow(
        date = LocalDate.ofEpochDay(r.epochDay),
        courseName = r.courseName,
        open = r.openAtMillis.toLocalDateTime(),
        end = r.endAtMillis.toLocalDateTime(),
        status = statusText(r.status),
        kind = r.status.toSummaryKind(),
        doneAt = r.doneAtMillis?.toLocalDateTime(),
        note = listOfNotNull(if (r.photoPath != null) "dengan foto bukti" else null, r.error).joinToString("; ").ifEmpty { null },
    )

    /** Tulis ke [uri]; mengembalikan jumlah baris. */
    suspend fun export(context: Context, uri: Uri, pdf: Boolean): Int {
        val rows = HistoryExport.sorted(rows())
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri)
            checkNotNull(out) { "file tidak bisa ditulis" }.use { stream ->
                if (pdf) writePdf(rows, stream) else stream.write(HistoryExport.csv(rows).toByteArray(Charsets.UTF_8))
            }
        }
        return rows.size
    }

    // ---------- PDF (A4, 72 dpi) ----------
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private const val ROW = 15f

    private fun writePdf(rows: List<ExportRow>, out: OutputStream) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val muted = Paint(text).apply { color = 0xFF555555.toInt() }
        val line = Paint().apply { color = 0xFFBBBBBB.toInt(); strokeWidth = 0.6f }

        val summary = HistoryExport.summaryLines(rows)
        val range = if (rows.isEmpty()) "belum ada riwayat" else "${Formatters.date(rows.first().date)} – ${Formatters.date(rows.last().date)}"
        // Tinggi kepala halaman 1: judul + keterangan + ringkasan; halaman lain hanya kepala tabel.
        val headTop = M + 20f + 14f * 2 + (if (summary.isEmpty()) 0f else 14f + summary.size * 13f) + 10f
        val tableHead = ROW + 4f
        val footer = 24f
        val firstRows = ((H - headTop - tableHead - footer - M) / ROW).toInt().coerceAtLeast(1)
        val otherRows = ((H - M - tableHead - footer - M) / ROW).toInt().coerceAtLeast(1)
        val pages = HistoryExport.pages(rows.size, firstRows, otherRows)

        // Kolom: Tanggal | Mata kuliah | Status | Waktu presensi
        val cols = floatArrayOf(M, M + 92f, M + 300f, M + 430f)
        val widths = floatArrayOf(88f, 204f, 126f, W - M - (M + 430f))
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { index, range0 ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, index + 1).create())
                val c = page.canvas
                var y = M
                if (index == 0) {
                    y += 16f
                    c.drawText("Riwayat presensi — NgiBsen UDINUS", M, y, title)
                    y += 16f
                    c.drawText("Rentang: $range · ${rows.size} catatan", M, y, muted)
                    y += 14f
                    c.drawText("Diekspor ${Formatters.dateTime(LocalDateTime.now())} dari catatan aplikasi (bukan data resmi SiAdin).", M, y, muted)
                    if (summary.isNotEmpty()) {
                        y += 16f
                        c.drawText("Ringkasan per mata kuliah", M, y, bold)
                        for (s in summary) {
                            y += 13f
                            c.drawText(fit("• $s", text, W - 2 * M), M, y, text)
                        }
                    }
                    y += 12f
                }
                y += ROW
                c.drawText("Tanggal", cols[0], y, bold)
                c.drawText("Mata kuliah", cols[1], y, bold)
                c.drawText("Status", cols[2], y, bold)
                c.drawText("Waktu presensi", cols[3], y, bold)
                c.drawLine(M, y + 4f, W - M, y + 4f, line)
                y += 4f
                for (i in range0) {
                    val r = rows[i]
                    y += ROW
                    c.drawText(fit("${Formatters.dayName(r.date.dayOfWeek.value).take(3)} ${r.date.dayOfMonth}/${r.date.monthValue}/${r.date.year}", text, widths[0]), cols[0], y, text)
                    c.drawText(fit("${r.courseName} (${Formatters.hm(r.open)})", text, widths[1]), cols[1], y, text)
                    c.drawText(fit(r.status, text, widths[2]), cols[2], y, text)
                    c.drawText(fit(r.doneAt?.let { Formatters.hms(it) } ?: "—", text, widths[3]), cols[3], y, text)
                }
                c.drawText("NgiBsen · halaman ${index + 1}/${pages.size}", M, H - M + 8f, muted)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Potong teks agar muat lebar [max] (diberi "…"). */
    private fun fit(s: String, paint: Paint, max: Float): String {
        if (paint.measureText(s) <= max) return s
        val n = paint.breakText(s, true, max - paint.measureText("…"), null)
        return s.take(n.coerceAtLeast(0)) + "…"
    }
}
