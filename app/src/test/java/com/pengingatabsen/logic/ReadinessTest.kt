package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReadinessTest {
    // Jadwal asli semester 5 (sebagian): Rabu 09:30 & 12:30, Kamis 12:30, Jumat 07:00 & 09:30.
    private val psk = Slot(dayOfWeek = 3, openMinute = 9 * 60 + 30, closeMinute = 12 * 60)
    private val mpti = Slot(dayOfWeek = 3, openMinute = 12 * 60 + 30, closeMinute = 15 * 60)
    private val sister = Slot(dayOfWeek = 4, openMinute = 12 * 60 + 30, closeMinute = 15 * 60)
    private val si = Slot(dayOfWeek = 5, openMinute = 7 * 60, closeMinute = 9 * 60 + 30)
    private val slots = listOf(psk, mpti, sister, si)

    private val wednesday = LocalDate.of(2026, 10, 7)

    @Test
    fun preflight_is30MinutesBeforeFirstClassOfTheDay() {
        // Rabu pagi: matkul pertama 09:30 → cek 09:00.
        assertEquals(wednesday.atTime(9, 0), Readiness.nextPreflight(slots, wednesday.atTime(6, 0)))
        // Rabu 09:10 (cek hari ini sudah lewat) → Kamis 12:00, bukan MPTI 12:00 Rabu.
        assertEquals(wednesday.plusDays(1).atTime(12, 0), Readiness.nextPreflight(slots, wednesday.atTime(9, 10)))
        // Jumat 07:00 → cek 06:30.
        assertEquals(wednesday.plusDays(2).atTime(6, 30), Readiness.nextPreflight(slots, wednesday.plusDays(1).atTime(13, 0)))
    }

    @Test
    fun preflight_skipsHolidaysAndEmptySchedule() {
        val allPaused = slots.map { it.copy(skipUntil = wednesday.plusDays(10)) }
        val next = Readiness.nextPreflight(allPaused, wednesday.atTime(6, 0))!!
        assertTrue(next.toLocalDate().isAfter(wednesday.plusDays(10)))
        assertNull(Readiness.nextPreflight(emptyList(), wednesday.atTime(6, 0)))
    }

    @Test
    fun preflight_includesReplacementClassOnlyOnItsDate() {
        // Kelas pengganti Sabtu 10 Okt 08:00 → cek kesiapan Sabtu 07:30; Sabtu berikutnya tidak ada kuliah.
        val sat = wednesday.plusDays(3)
        val ganti = Slot(dayOfWeek = 6, openMinute = 8 * 60, closeMinute = 10 * 60, onlyDate = sat)
        assertEquals(sat.atTime(7, 30), Readiness.nextPreflight(slots + ganti, wednesday.plusDays(2).atTime(10, 0)))
        assertEquals(null, Readiness.firstOpenOn(slots + ganti, sat.plusWeeks(1)))
        assertEquals(sat.atTime(8, 0), Readiness.firstOpenOn(slots + ganti, sat))
    }

    @Test
    fun missedAlarm_onlyWhenArmedAndNoTrace() {
        val day = wednesday.toEpochDay()
        val armed = listOf(ArmedOccurrence(1, day), ArmedOccurrence(2, day))
        val map = mapOf(1L to psk, 2L to mpti)
        val now = wednesday.atTime(12, 40)
        // PSK berbunyi (ada catatan), MPTI 12:30 tidak meninggalkan jejak → terlewat.
        val missed = Readiness.missedAlarms(armed, map, { it.courseId == 1L }, now)
        assertEquals(listOf(2L), missed.map { it.first.courseId })
        // Masih dalam toleransi 3 menit → belum dianggap terlewat.
        assertTrue(Readiness.missedAlarms(armed, map, { it.courseId == 1L }, wednesday.atTime(12, 32)).isEmpty())
    }

    @Test
    fun missedAlarm_ignoresDeletedHolidayAndOld() {
        val day = wednesday.toEpochDay()
        val now = wednesday.atTime(16, 0)
        // Matkul dihapus/nonaktif (tidak ada di peta) → diabaikan.
        assertTrue(Readiness.missedAlarms(listOf(ArmedOccurrence(9, day)), mapOf(), { false }, now).isEmpty())
        // Diliburkan setelah alarm dipasang → diabaikan.
        val holiday = mapOf(2L to mpti.copy(skipUntil = wednesday))
        assertTrue(Readiness.missedAlarms(listOf(ArmedOccurrence(2, day)), holiday, { false }, now).isEmpty())
        // Lebih dari sehari lalu → diabaikan.
        val old = listOf(ArmedOccurrence(2, wednesday.minusDays(7).toEpochDay()))
        assertTrue(Readiness.missedAlarms(old, mapOf(2L to mpti), { false }, now).isEmpty())
    }

    @Test
    fun countdownText() {
        val now = LocalDateTime.of(2026, 10, 8, 10, 0)
        assertEquals("2 jam 30 menit lagi", Readiness.countdown(now, now.plusMinutes(150)))
        assertEquals("1 jam lagi", Readiness.countdown(now, now.plusMinutes(60)))
        assertEquals("12 menit lagi", Readiness.countdown(now, now.plusMinutes(12)))
        assertEquals("sebentar lagi", Readiness.countdown(now, now))
    }
}
