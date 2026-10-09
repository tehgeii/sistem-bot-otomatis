package com.pengingatabsen.logic

import java.time.LocalDate
import java.time.LocalDateTime

/** Satu baris riwayat untuk diekspor (tanpa ketergantungan Room). */
data class ExportRow(
    val date: LocalDate,
    val courseName: String,
    val open: LocalDateTime,
    val end: LocalDateTime,
    /** Label status berbahasa Indonesia, mis. "terkirim", "terlewat". */
    val status: String,
    val kind: SummaryKind,
    /** Waktu presensi / bukti (bila ada). */
    val doneAt: LocalDateTime?,
    val note: String?,
)

/** Ekspor riwayat ke CSV (murni & teruji di HistoryExportTest). PDF digambar dari baris yang sama. */
object HistoryExport {
    /** Pemisah titik koma: Excel berbahasa Indonesia memakai ";" (koma = desimal); Google Sheets mendeteksi sendiri. */
    const val SEPARATOR = ';'
    val HEADER = listOf("Tanggal", "Hari", "Mata kuliah", "Jam buka", "Jam tutup", "Status", "Waktu presensi", "Keterangan")

    fun fileName(date: LocalDate, ext: String): String = "NgiBsen-riwayat-$date.$ext"

    /** Urut tanggal lalu jam buka (lama → baru). */
    fun sorted(rows: List<ExportRow>): List<ExportRow> = rows.sortedWith(compareBy({ it.date }, { it.open }, { it.courseName }))

    fun csv(rows: List<ExportRow>): String = buildString {
        // BOM supaya Excel membaca huruf non-ASCII (UTF-8) dengan benar.
        append('﻿')
        appendLine(HEADER.joinToString(SEPARATOR.toString()) { field(it) })
        for (r in sorted(rows)) {
            val cells = listOf(
                r.date.toString(),
                Formatters.dayName(r.date.dayOfWeek.value),
                r.courseName,
                Formatters.hm(r.open),
                Formatters.hm(r.end),
                r.status,
                r.doneAt?.let { "${it.toLocalDate()} ${Formatters.hms(it)}" }.orEmpty(),
                r.note.orEmpty(),
            )
            appendLine(cells.joinToString(SEPARATOR.toString()) { field(it) })
        }
    }

    /**
     * Satu sel CSV: diberi tanda kutip bila memuat pemisah/kutip/baris baru; kutip digandakan (RFC 4180).
     * Sel yang diawali = + - @ diberi awalan ' supaya tidak dijalankan sebagai rumus di Excel/Sheets.
     */
    fun field(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        val needsQuote = safe.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' || it == ',' }
        return if (needsQuote) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** Ringkasan per matkul untuk kepala PDF: "MPTI 4515 — 5/6 hadir (83%) · 1 terlewat". */
    fun summaryLines(rows: List<ExportRow>): List<String> =
        AttendanceStats.perCourse(rows.map { it.courseName to it.kind }).map { s ->
            val pct = s.percent?.let { " ($it%)" }.orEmpty()
            val extra = buildList {
                if (s.missed > 0) add("${s.missed} terlewat")
                if (s.holiday > 0) add("${s.holiday} libur")
                if (s.noSession > 0) add("${s.noSession} tidak dibuka")
            }
            "${s.courseName} — ${s.present}/${s.counted} hadir$pct" + extra.joinToString("") { " · $it" }
        }

    /** Pembagian baris tabel PDF ke halaman: [first] baris di halaman 1 (ada kepala & ringkasan), [other] di berikutnya. */
    fun pages(rowCount: Int, first: Int, other: Int): List<IntRange> {
        require(first > 0 && other > 0)
        if (rowCount <= 0) return listOf(IntRange.EMPTY)
        val out = mutableListOf(0 until minOf(first, rowCount))
        var start = first
        while (start < rowCount) {
            out += start until minOf(start + other, rowCount)
            start += other
        }
        return out
    }
}
