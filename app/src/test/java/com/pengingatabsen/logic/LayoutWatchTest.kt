package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutWatchTest {
    private val day = 20_370L

    @Test
    fun oneCourseOneDay_isNotEnough() {
        // SiAdin bermasalah sesaat selama satu kuliah: tidak memicu peringatan.
        var list = emptyList<SuspectCheck>()
        repeat(10) { list = LayoutWatch.add(list, SuspectCheck(1, day)) }
        assertFalse(LayoutWatch.shouldWarn(list))
    }

    @Test
    fun twoCoursesOrTwoDays_afterFourChecks() {
        val twoCourses = listOf(SuspectCheck(1, day), SuspectCheck(1, day), SuspectCheck(2, day))
        assertFalse(LayoutWatch.shouldWarn(twoCourses))
        assertTrue(LayoutWatch.shouldWarn(twoCourses + SuspectCheck(2, day)))
        val twoDays = listOf(SuspectCheck(1, day), SuspectCheck(1, day), SuspectCheck(1, day), SuspectCheck(1, day + 7))
        assertTrue(LayoutWatch.shouldWarn(twoDays))
    }

    @Test
    fun encodeDecodeAndKeepLast20() {
        var list = emptyList<SuspectCheck>()
        for (i in 1..25) list = LayoutWatch.add(list, SuspectCheck(i.toLong(), day))
        assertEquals(20, list.size)
        assertEquals(6L, list.first().courseId)
        assertEquals(list, LayoutWatch.decode(LayoutWatch.encode(list)))
        assertEquals(emptyList<SuspectCheck>(), LayoutWatch.decode(null))
        assertEquals(listOf(SuspectCheck(3, 4)), LayoutWatch.decode("rusak,3:4,x:y,5"))
    }

    @Test
    fun messageNamesCourses() {
        val list = listOf(SuspectCheck(1, day), SuspectCheck(2, day), SuspectCheck(1, day + 1), SuspectCheck(9, day))
        val names = mapOf(1L to "MPTI 4515", 2L to "Sistem Terdistribusi 4512")
        val msg = LayoutWatch.message(list, names::get)
        assertTrue(msg, msg.contains("4 pengecekan terakhir (MPTI 4515, Sistem Terdistribusi 4512)"))
        assertTrue(msg.contains("log diagnosis"))
    }
}
