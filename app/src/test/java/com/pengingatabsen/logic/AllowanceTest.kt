package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AllowanceTest {
    @Test
    fun maxAbsentRoundsRequiredUp() {
        assertEquals(3, AttendanceRule(14, 75).maxAbsent) // wajib 11 (10,5 dibulatkan ke atas)
        assertEquals(4, AttendanceRule(16, 75).maxAbsent) // wajib 12
        assertEquals(2, AttendanceRule(14, 80).maxAbsent) // wajib 12 (11,2)
        assertEquals(0, AttendanceRule(14, 100).maxAbsent)
        assertEquals(14, AttendanceRule(14, 0).maxAbsent)
        assertEquals(0, AttendanceRule(0, 75).maxAbsent) // tidak boleh negatif / pembagian aneh
    }

    @Test
    fun onlyMissedCountsAgainstAllowance() {
        val kinds = listOf(SummaryKind.DONE, SummaryKind.MISSED, SummaryKind.HOLIDAY, SummaryKind.NO_SESSION, SummaryKind.FAILED)
        val a = Allowances.forCourse("MPTI 4515", kinds, AttendanceRule())
        assertEquals(1, a.missed)
        assertEquals(2, a.remaining)
        assertEquals(AllowanceLevel.SAFE, a.level)
        assertNull(Allowances.missedWarning(a))
        assertEquals("Jatah tidak hadir: sisa 2 dari 3", Allowances.label(a))
    }

    @Test
    fun warningsWhenRunningOut() {
        val rule = AttendanceRule()
        val last = Allowance("MPTI 4515", 2, rule.maxAbsent)
        assertEquals(AllowanceLevel.LAST, last.level)
        assertTrue(Allowances.missedWarning(last)!!.contains("tinggal 1 dari 3"))
        val limit = Allowance("MPTI 4515", 3, rule.maxAbsent)
        assertEquals(AllowanceLevel.LIMIT, limit.level)
        assertTrue(Allowances.missedWarning(limit)!!.contains("HABIS"))
        val over = Allowance("MPTI 4515", 5, rule.maxAbsent)
        assertEquals(AllowanceLevel.OVER, over.level)
        assertTrue(Allowances.label(over).contains("5×"))
    }

    @Test
    fun semesterStartFilter() {
        val start = LocalDate.of(2026, 9, 7)
        assertTrue(Allowances.inSemester(start, start))
        assertFalse(Allowances.inSemester(start.minusDays(1), start))
        assertTrue(Allowances.inSemester(start.minusYears(1), null))
    }

    @Test
    fun weeklyBucketsIncludeEmptyWeeks() {
        val today = LocalDate.of(2026, 10, 9) // Jumat
        val items = listOf(
            SummaryItem("A", LocalDate.of(2026, 10, 7), SummaryKind.DONE),
            SummaryItem("B", LocalDate.of(2026, 10, 8), SummaryKind.FAILED),
            SummaryItem("C", LocalDate.of(2026, 10, 8), SummaryKind.MISSED),
            SummaryItem("D", LocalDate.of(2026, 9, 21), SummaryKind.HOLIDAY),
            SummaryItem("E", LocalDate.of(2026, 9, 22), SummaryKind.DONE),
            SummaryItem("lama", LocalDate.of(2026, 1, 5), SummaryKind.MISSED),
        )
        val b = WeeklyChart.buckets(items, today, weeks = 4)
        assertEquals(listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5)), b.map { it.weekStart })
        assertEquals(listOf(0, 1, 0, 2), b.map { it.present })
        assertEquals(listOf(0, 0, 0, 1), b.map { it.missed })
    }
}
