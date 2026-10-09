package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoryExportTest {
    private val wed = LocalDate.of(2026, 10, 7)
    private val thu = LocalDate.of(2026, 10, 8)

    private fun row(date: LocalDate, name: String, h: Int, status: String, kind: SummaryKind, done: Boolean = true, note: String? = null) =
        ExportRow(date, name, date.atTime(h, 30), date.atTime(h + 2, 30), status, kind, if (done) date.atTime(h, 41, 5) else null, note)

    @Test
    fun csvHasBomHeaderAndSortedRows() {
        val rows = listOf(
            row(thu, "Sistem Terdistribusi 4512", 12, "terkirim", SummaryKind.DONE),
            row(wed, "MPTI 4515", 12, "terlewat", SummaryKind.MISSED, done = false),
            row(wed, "Pemrograman Sisi Klien 4702", 9, "terkirim", SummaryKind.DONE),
        )
        val lines = HistoryExport.csv(rows).lines().filter { it.isNotEmpty() }
        assertTrue(lines[0].startsWith("﻿Tanggal;Hari;Mata kuliah"))
        assertEquals("2026-10-07;Rabu;Pemrograman Sisi Klien 4702;09:30;11:30;terkirim;2026-10-07 09:41:05;", lines[1])
        assertEquals("2026-10-07;Rabu;MPTI 4515;12:30;14:30;terlewat;;", lines[2])
        assertEquals("2026-10-08;Kamis;Sistem Terdistribusi 4512;12:30;14:30;terkirim;2026-10-08 12:41:05;", lines[3])
    }

    @Test
    fun fieldsAreQuotedAndFormulaSafe() {
        assertEquals("MPTI", HistoryExport.field("MPTI"))
        assertEquals("\"a;b\"", HistoryExport.field("a;b"))
        assertEquals("\"kata \"\"kutip\"\"\"", HistoryExport.field("kata \"kutip\""))
        assertEquals("\"baris\nbaru\"", HistoryExport.field("baris\nbaru"))
        assertEquals("'=HYPERLINK(1)", HistoryExport.field("=HYPERLINK(1)"))
        assertEquals("'-gagal", HistoryExport.field("-gagal"))
        assertEquals("\"1,5\"", HistoryExport.field("1,5"))
    }

    @Test
    fun summaryLinesPerCourse() {
        val rows = listOf(
            row(wed, "MPTI 4515", 12, "terkirim", SummaryKind.DONE),
            row(thu, "MPTI 4515", 12, "terlewat", SummaryKind.MISSED),
            row(thu, "MPTI 4515", 12, "libur", SummaryKind.HOLIDAY),
        )
        assertEquals(listOf("MPTI 4515 — 1/2 hadir (50%) · 1 terlewat · 1 libur"), HistoryExport.summaryLines(rows))
    }

    @Test
    fun pagesSplitRows() {
        assertEquals(listOf(0 until 30, 30 until 75, 75 until 100), HistoryExport.pages(100, 30, 45))
        assertEquals(listOf(0 until 5), HistoryExport.pages(5, 30, 45))
        assertEquals(listOf(IntRange.EMPTY), HistoryExport.pages(0, 30, 45))
        assertEquals(listOf(0 until 30), HistoryExport.pages(30, 30, 45))
    }
}
