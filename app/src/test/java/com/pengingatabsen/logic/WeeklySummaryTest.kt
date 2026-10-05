package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeeklySummaryTest {
    private val monday = LocalDate.of(2026, 9, 28)

    @Test
    fun build_countsEachKindAndListsMissed() {
        val items = listOf(
            SummaryItem("Pemrograman Game", monday, SummaryKind.DONE),
            SummaryItem("Technopreneurship", monday.plusDays(1), SummaryKind.DONE),
            SummaryItem("Basis Data", monday.plusDays(2), SummaryKind.MISSED),
            SummaryItem("Statistika", monday.plusDays(3), SummaryKind.NO_SESSION),
            SummaryItem("Kalkulus", monday.plusDays(4), SummaryKind.HOLIDAY),
            // Di luar minggu ini: diabaikan.
            SummaryItem("Kalkulus", monday.minusDays(3), SummaryKind.MISSED),
        )
        val text = WeeklySummary.build(monday, items)!!
        assertTrue(text.startsWith("📊 Ringkasan NgiBsen 28 Sep – 4 Okt 2026"))
        assertTrue(text.contains("✅ Berhasil presensi: 2"))
        assertTrue(text.contains("❌ Terlewat: 1"))
        assertTrue(text.contains("⏸ Tidak dibuka dosen: 1"))
        assertTrue(text.contains("🏖 Libur: 1"))
        assertTrue(text.contains("• Basis Data (Rabu, 30 September 2026)"))
        assertTrue(!text.contains("gagal terkirim"))
    }

    @Test
    fun build_emptyWeekIsNotSent() {
        assertNull(WeeklySummary.build(monday, emptyList()))
        assertNull(WeeklySummary.build(monday, listOf(SummaryItem("X", monday.minusDays(1), SummaryKind.DONE))))
    }

    @Test
    fun dueWeek_sundayEveningAndLateRuns() {
        val sunday = monday.plusDays(6)
        // Minggu 19.05 → minggu ini.
        assertEquals(monday, WeeklySummary.dueWeekStart(sunday.atTime(19, 5)))
        // Senin pagi (pengiriman telat) → tetap minggu kemarin.
        assertEquals(monday, WeeklySummary.dueWeekStart(sunday.plusDays(1).atTime(8, 0)))
        // Minggu 18.00 → belum waktunya; minggu sebelumnya.
        assertEquals(monday.minusWeeks(1), WeeklySummary.dueWeekStart(sunday.atTime(18, 0)))
    }

    @Test
    fun nextSendTime_isNextSundayAt19() {
        assertEquals(LocalDateTime.of(2026, 10, 4, 19, 0), WeeklySummary.nextSendTime(LocalDateTime.of(2026, 10, 1, 10, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 11, 19, 0), WeeklySummary.nextSendTime(LocalDateTime.of(2026, 10, 4, 19, 0)))
    }
}
