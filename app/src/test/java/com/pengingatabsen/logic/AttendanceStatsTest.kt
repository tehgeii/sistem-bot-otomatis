package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceStatsTest {
    private val items = listOf(
        "MPTI 4515" to SummaryKind.DONE,
        "MPTI 4515" to SummaryKind.DONE,
        "MPTI 4515" to SummaryKind.MISSED,
        "MPTI 4515" to SummaryKind.HOLIDAY,
        "MPTI 4515" to SummaryKind.NO_SESSION,
        "MPTI 4515" to SummaryKind.ACTIVE,
        "Kriptografi 4502" to SummaryKind.FAILED,
        "Kriptografi 4502" to SummaryKind.DONE,
    )

    @Test
    fun perCourse_countsOnlyHeldMeetings() {
        val stats = AttendanceStats.perCourse(items).associateBy { it.courseName }
        val mpti = stats.getValue("MPTI 4515")
        assertEquals(2, mpti.present)
        assertEquals(1, mpti.missed)
        assertEquals(1, mpti.holiday)
        assertEquals(1, mpti.noSession)
        assertEquals(3, mpti.counted)
        assertEquals(67, mpti.percent)
        // Bukti gagal terkirim tetap terhitung hadir.
        assertEquals(100, stats.getValue("Kriptografi 4502").percent)
        assertEquals(listOf("Kriptografi 4502", "MPTI 4515"), AttendanceStats.perCourse(items).map { it.courseName })
    }

    @Test
    fun overall_andEmpty() {
        val all = AttendanceStats.overall(items)
        assertEquals(4, all.present)
        assertEquals(80, all.percent)
        assertNull(AttendanceStats.overall(listOf("X" to SummaryKind.HOLIDAY)).percent)
    }
}
