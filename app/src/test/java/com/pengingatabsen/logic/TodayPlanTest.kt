package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TodayPlanTest {
    private val wed = LocalDate.of(2026, 10, 7)
    private val psk = TodayCourse(1, "Pemrograman Sisi Klien 4702", "D.2.A", Slot(3, 9 * 60 + 30, 12 * 60))
    private val mpti = TodayCourse(2, "MPTI 4515", "Kulino", Slot(3, 12 * 60 + 30, 15 * 60))
    private val sister = TodayCourse(3, "Sistem Terdistribusi 4512", "Kulino", Slot(4, 12 * 60 + 30, 15 * 60))
    private val all = listOf(psk, mpti, sister)

    private fun states(view: TodayView) = view.items.map { it.name.substringBefore(' ') to it.state }

    @Test
    fun morningBeforeClasses_upcomingWithOrder() {
        val v = TodayPlan.build(all, wed.atTime(8, 0), { _, _ -> null }, { _, _ -> false })
        assertEquals(listOf("Pemrograman" to TodayState.UPCOMING, "MPTI" to TodayState.UPCOMING), states(v))
        assertNull(v.next)
    }

    @Test
    fun duringClass_waitingThenOpen() {
        val waiting = TodayPlan.build(all, wed.atTime(10, 0), { _, _ -> SummaryKind.ACTIVE }, { _, _ -> false })
        assertEquals(TodayState.WAITING, waiting.items[0].state)
        val open = TodayPlan.build(all, wed.atTime(10, 0), { _, _ -> SummaryKind.ACTIVE }, { id, _ -> id == 1L })
        assertEquals(TodayState.OPEN, open.items[0].state)
    }

    @Test
    fun afterClasses_showsDoneAndNextCourse() {
        // 7 Okt: PSK & MPTI sudah presensi → layar menampilkan Kamis Sistem Terdistribusi berikutnya.
        val v = TodayPlan.build(all, wed.atTime(16, 0), { _, _ -> SummaryKind.DONE }, { _, _ -> false })
        assertEquals(listOf("Pemrograman" to TodayState.DONE, "MPTI" to TodayState.DONE), states(v))
        assertEquals("Sistem Terdistribusi 4512", v.next?.name)
        assertEquals(wed.plusDays(1).atTime(12, 30), v.next?.open)
    }

    @Test
    fun holidayMissedNoSessionAndGrace() {
        val skipped = mpti.copy(slot = mpti.slot.copy(skipUntil = wed))
        val v = TodayPlan.build(listOf(psk, skipped), wed.atTime(15, 5), { id, _ -> if (id == 1L) SummaryKind.MISSED else null }, { _, _ -> false })
        assertEquals(listOf("Pemrograman" to TodayState.MISSED, "MPTI" to TodayState.HOLIDAY), states(v))
        // Mode pintar: jendela MPTI diperpanjang 15 menit → 15:05 masih menunggu.
        val g = TodayPlan.build(listOf(mpti), wed.atTime(15, 5), { _, _ -> SummaryKind.ACTIVE }, { _, _ -> false }, graceMinutes = 15)
        assertEquals(TodayState.WAITING, g.items[0].state)
        val n = TodayPlan.build(listOf(mpti), wed.atTime(15, 20), { _, _ -> SummaryKind.NO_SESSION }, { _, _ -> false }, graceMinutes = 15)
        assertEquals(TodayState.NO_SESSION, n.items[0].state)
    }

    @Test
    fun dayWithoutClasses_onlyNext() {
        val v = TodayPlan.build(all, wed.minusDays(2).atTime(9, 0), { _, _ -> null }, { _, _ -> false })
        assertEquals(emptyList<TodayItem>(), v.items)
        assertEquals("Pemrograman Sisi Klien 4702", v.next?.name)
    }
}
