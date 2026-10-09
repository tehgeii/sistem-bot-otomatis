package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PresensiGuardTest {
    // Jumat 9 Okt 2026.
    private val friday = LocalDate.of(2026, 10, 9)

    @Test
    fun nudgeAfterFiveMinutes() {
        val opened = 1_000_000L
        assertFalse(PresensiNudge.due(null, opened + 10 * 60_000))
        assertFalse(PresensiNudge.due(opened, opened + 4 * 60_000 + 59_000))
        assertTrue(PresensiNudge.due(opened, opened + 5 * 60_000))
        val text = PresensiNudge.text("Kriptografi 4502", LocalTime.of(9, 32), "Kulino")
        assertTrue(text, text.contains("Kriptografi 4502") && text.contains("09:32") && text.contains("Kulino"))
        assertFalse(PresensiNudge.text("X", LocalTime.of(9, 0), " ").contains("ruang"))
    }

    private fun phone(dnd: Boolean = false, bypass: Boolean = false, allowed: Boolean = false, silent: Boolean = false, battery: Int? = 80, charging: Boolean = false) =
        PhoneState(dnd, bypass, allowed, silent, battery, charging)

    @Test
    fun quietAndBattery() {
        assertNull(PhoneCheck.quietIssue(phone()))
        assertEquals(QuietIssue.DND, PhoneCheck.quietIssue(phone(dnd = true)))
        assertNull(PhoneCheck.quietIssue(phone(dnd = true, bypass = true)))
        assertNull(PhoneCheck.quietIssue(phone(dnd = true, allowed = true)))
        assertEquals(QuietIssue.SILENT, PhoneCheck.quietIssue(phone(silent = true)))
        assertEquals(QuietIssue.SILENT, PhoneCheck.quietIssue(phone(dnd = true, bypass = true, silent = true)))
        assertNull(PhoneCheck.batteryText(phone(battery = 16)))
        assertTrue(PhoneCheck.batteryText(phone(battery = 15))!!.contains("15%"))
        assertNull(PhoneCheck.batteryText(phone(battery = 5, charging = true)))
        assertNull(PhoneCheck.batteryText(phone(battery = null)))
    }

    private val pgame = 1L to Slot(1, 9 * 60 + 30, 12 * 60)
    private val tekno = 2L to Slot(1, 12 * 60 + 30, 14 * 60 + 10)
    private val si = 3L to Slot(5, 7 * 60, 9 * 60 + 45)
    private val kripto = 4L to Slot(5, 9 * 60 + 30, 12 * 60 + 15)
    private val all = listOf(pgame, tekno, si, kripto)

    @Test
    fun preClassNext() {
        // Jumat 06:00 → SI 07:00, pengingat 06:45.
        val (at, ids) = PreClass.next(all, friday.atTime(6, 0), 15)!!
        assertEquals(friday.atTime(6, 45), at)
        assertEquals(listOf(3L), ids)
        // Jumat 06:50 (SI tinggal 10 menit) → tidak telat-telat, lanjut Kriptografi 09:15.
        assertEquals(friday.atTime(9, 15), PreClass.next(all, friday.atTime(6, 50), 15)!!.first)
        // Jumat sore → Senin 09:30 − 30 menit.
        val (mon, monIds) = PreClass.next(all, friday.atTime(15, 0), 30)!!
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), mon)
        assertEquals(listOf(1L), monIds)
        // Dua matkul buka bersamaan → satu pengingat untuk keduanya.
        val twin = listOf(1L to Slot(5, 9 * 60, null), 2L to Slot(5, 9 * 60, null))
        assertEquals(listOf(1L, 2L), PreClass.next(twin, friday.atTime(8, 0), 15)!!.second)
        // Libur & kelas pengganti.
        val skipped = listOf(3L to Slot(5, 7 * 60, null, skipUntil = friday))
        assertEquals(friday.plusDays(7).atTime(6, 45), PreClass.next(skipped, friday.atTime(6, 0), 15)!!.first)
        val oneOff = listOf(9L to Slot(6, 8 * 60, null, onlyDate = friday.plusDays(1)))
        assertEquals(friday.plusDays(1).atTime(7, 45), PreClass.next(oneOff, friday.atTime(20, 0), 15)!!.first)
        assertNull(PreClass.next(oneOff, friday.plusDays(2).atTime(0, 0), 15))
        assertEquals(listOf(4L), PreClass.startingAt(all, friday.atTime(9, 30)))
        assertTrue(PreClass.startingAt(all, friday.atTime(9, 31)).isEmpty())
        val title = PreClass.title(listOf("Kriptografi 4502"), friday.atTime(9, 30), friday.atTime(9, 15))
        assertEquals("🔔 Kriptografi 4502 mulai 09:30 (15 menit lagi)", title)
    }

    private fun card(name: String, klpk: String, status: String, date: String = "09 October 2026") =
        "$name\nKDMK: A11.64501\nKLPK: A11.$klpk\n$date\n21.43 %\n$status\nRealisasi RPS: Belum Dikonfirmasi"

    private fun course(name: String, today: Boolean = false, window: Boolean = false, done: Boolean = false) =
        RadarCourse(name, today, window, done)

    @Test
    fun radarFindsOpenOutsideSchedule() {
        val cards = listOf(card("KRIPTOGRAFI", "4502", "Presensi Sekarang"), card("SISTEM INFORMASI", "4507", "Belum Jadwalnya"))
        val courses = listOf(course("Kriptografi 4502", today = true), course("Sistem Informasi 4507", today = true, window = true))
        val found = Radar.evaluate(cards, courses, friday)
        assertEquals(listOf(RadarFinding(RadarKind.OPEN_OUTSIDE_SCHEDULE, "Kriptografi 4502", "KRIPTOGRAFI")), found)
        assertTrue(Radar.title(found[0]).contains("Kriptografi 4502"))
        assertTrue(Radar.telegram(found[0])!!.contains("DIBUKA"))
    }

    @Test
    fun radarSkipsHandledCourses() {
        val open = listOf(card("KRIPTOGRAFI", "4502", "Presensi Sekarang"))
        // Jendela sedang berjalan → alur biasa; sudah selesai → tidak diganggu.
        assertTrue(Radar.evaluate(open, listOf(course("Kriptografi 4502", today = true, window = true)), friday).isEmpty())
        assertTrue(Radar.evaluate(open, listOf(course("Kriptografi 4502", today = true, done = true)), friday).isEmpty())
        // Sudah berhasil di SiAdin → bukan temuan.
        val done = listOf(card("KRIPTOGRAFI", "4502", "Berhasil Presensi"))
        assertTrue(Radar.evaluate(done, listOf(course("Kriptografi 4502")), friday).isEmpty())
        // Kartu tanggal lain diabaikan.
        val old = listOf(card("KRIPTOGRAFI", "4502", "Presensi Sekarang", "02 October 2026"))
        assertTrue(Radar.evaluate(old, listOf(course("Kriptografi 4502")), friday).isEmpty())
    }

    @Test
    fun radarSessionNotScheduledAndUnknownCourse() {
        val cards = listOf(
            card("KRIPTOGRAFI", "4502", "Belum Jadwalnya"),
            card("SISTEM TERDISTRIBUSI", "4512", "Presensi Sekarang"),
            card("SISTEM INFORMASI", "4507", "Belum Jadwalnya"),
        )
        val courses = listOf(course("Kriptografi 4502", today = false), course("Sistem Informasi 4507", today = true))
        val found = Radar.evaluate(cards, courses, friday)
        assertEquals(
            listOf(
                RadarFinding(RadarKind.SESSION_NOT_SCHEDULED_TODAY, "Kriptografi 4502", "KRIPTOGRAFI"),
                RadarFinding(RadarKind.UNKNOWN_COURSE_OPEN, null, "SISTEM TERDISTRIBUSI"),
            ),
            found,
        )
        assertNull(Radar.telegram(found[0]))
        assertEquals("radar|SESSION_NOT_SCHEDULED_TODAY|Kriptografi 4502|${friday.toEpochDay()}", found[0].key(friday))
    }

    @Test
    fun radarIgnoresNavbarWordsOnSingleCardPage() {
        // Halaman satu kartu yang ikut memuat navbar ("Sistem Informasi Akademik"): bukan kartu SI.
        val page = "SiAdin\nSistem Informasi Akademik\nMasa studi: 4 th 1 bl\nPresensi Kuliah Online\n" +
            card("KRIPTOGRAFI", "4502", "Presensi Sekarang") + "\nSiAdin | Copyright © Udinus"
        val found = Radar.evaluate(listOf(page), listOf(course("Sistem Informasi 4507"), course("Kriptografi 4502", window = true)), friday)
        assertTrue(found.toString(), found.isEmpty())
    }

    @Test
    fun cardDateAndTitle() {
        assertEquals(friday, Radar.cardDate("KLPK: A11.4502\n09 October 2026\n21.43 %"))
        assertEquals(friday, Radar.cardDate("9 Oktober 2026"))
        assertNull(Radar.cardDate("21.43 %"))
        assertNull(Radar.cardDate("31 February 2026"))
        assertEquals("KRIPTOGRAFI", Radar.cardTitle("KRIPTOGRAFI\nKDMK: A11"))
        assertEquals("TECHNOPRENEURSHIP 2 SKS", Radar.cardTitle("\nTECHNOPRENEURSHIP 2 SKS KDMK: AF201703"))
    }

    @Test
    fun radarActiveOnlyOnClassDaysDuringDaytime() {
        val slots = all.map { it.second }
        assertTrue(Radar.activeNow(slots, friday.atTime(10, 0)))
        assertFalse(Radar.activeNow(slots, friday.atTime(6, 59)))
        assertFalse(Radar.activeNow(slots, friday.atTime(17, 31)))
        // Sabtu tidak ada kuliah.
        assertFalse(Radar.activeNow(slots, friday.plusDays(1).atTime(10, 0)))
    }
}
