package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Teks kartu asli dari screenshot SiAdin 9 Okt 2026 (halaman KRS & Presensi Online). */
class OfficialAttendanceTest {
    private fun krs(title: String, sks: Int, kdmk: String, klpk: String, day: String, time: String, room: String, pct: String) =
        "$title\n$sks SKS\nKDMK: A11.$kdmk\nKLPK: A11.$klpk\n$day\n$time\n$room\n-\n-\n$pct %"

    private val krsCards = listOf(
        krs("SISTEM TERDISTRIBUSI", 3, "64501", "4512", "KAMIS", "12.30-15.00", "Kulino", "28.57"),
        krs("PENAMBANGAN DATA", 3, "64502", "4502", "SELASA", "09.30-12.00", "Kulino", "21.43"),
        krs("SISTEM INFORMASI", 3, "64503", "4507", "JUMAT", "07.00-09.30", "Kulino", "28.57"),
        krs("MANAJEMEN PROYEK TEKNOLOGI INFORMASI", 3, "64504", "4515", "RABU", "12.30-15.00", "Kulino", "28.57"),
        krs("KRIPTOGRAFI", 3, "64506", "4502", "JUMAT", "09.30-12.00", "Kulino", "28.57"),
        krs("PEMROGRAMAN SISI KLIEN", 3, "64706", "4702", "RABU", "09.30-12.00", "D.2.A", "28.57"),
        krs("PEMROGRAMAN GAME", 3, "64710", "4703", "SENIN", "09.30-12.00", "D.2.I", "28.57"),
        "TECHNOPRENEURSHIP\n2 SKS\nKDMK: AF201703\nKLPK: A11.4502\nSENIN\n12.30-14.10\nH.5.9\n-\n-\n28.57 %",
    )
    private val myCourses = listOf(
        "Sistem Terdistribusi 4512", "Penambangan Data 4502", "Sistem Informasi 4507", "MPTI 4515",
        "Kriptografi 4502", "Pemrograman Sisi Klien 4702", "Pemrograman Game 4703", "Technopreneurship 4502",
    )

    @Test
    fun parsesPercentFromRealCards() {
        assertEquals(28.57, OfficialAttendance.parsePercent(krsCards[0])!!, 0.0)
        assertEquals(21.43, OfficialAttendance.parsePercent(krsCards[1])!!, 0.0)
        // Kartu Presensi Online: tanggal "2026" tidak boleh terbaca sebagai persen.
        val presensi = "SISTEM INFORMASI\nKDMK: A11.64503\nKLPK: A11.4507\n09 October 2026\n28.57 %\nBelum Jadwalnya\nRealisasi RPS:\nBelum Dikonfirmasi"
        assertEquals(28.57, OfficialAttendance.parsePercent(presensi)!!, 0.0)
        assertEquals(7.5, OfficialAttendance.parsePercent("7,5%")!!, 0.0)
        assertNull(OfficialAttendance.parsePercent("KAMIS 12.30-15.00 Kulino"))
    }

    @Test
    fun presentCountFromPercent() {
        assertEquals(4, OfficialAttendance.presentCount(28.57, 14))
        assertEquals(3, OfficialAttendance.presentCount(21.43, 14))
        assertEquals(14, OfficialAttendance.presentCount(100.0, 14))
        assertEquals(0, OfficialAttendance.presentCount(0.0, 14))
    }

    @Test
    fun matchesEveryCourseEvenWithSharedKlpk() {
        // KLPK 4502 dipakai Penambangan Data, Kriptografi, Technopreneurship: harus tetap terpisah.
        val m = OfficialAttendance.match(krsCards, myCourses)
        assertEquals(8, m.size)
        assertEquals(21.43, m["Penambangan Data 4502"]!!, 0.0)
        assertEquals(28.57, m["Kriptografi 4502"]!!, 0.0)
        assertEquals(28.57, m["Technopreneurship 4502"]!!, 0.0)
        assertEquals(28.57, m["MPTI 4515"]!!, 0.0) // singkatan
        // Halaman Presensi Online hanya berisi kartu hari itu: matkul lain tidak ikut.
        val today = OfficialAttendance.match(listOf(krsCards[2], krsCards[4]), myCourses)
        assertEquals(setOf("Sistem Informasi 4507", "Kriptografi 4502"), today.keys)
    }

    @Test
    fun heldEstimateFromScheduleMinusHolidays() {
        val start = LocalDate.of(2026, 9, 14) // Senin minggu pertama
        val selasa = Slot(2, 9 * 60 + 30, 12 * 60)
        val jumat = Slot(5, 9 * 60 + 30, 12 * 60 + 15)
        val libur = setOf(LocalDate.of(2026, 9, 22))
        val fri = LocalDate.of(2026, 10, 9)
        // Selasa 15, 22(libur), 29 Sep, 6 Okt → 3.
        assertEquals(3, OfficialAttendance.heldEstimate(listOf(selasa to libur), start, fri.atTime(15, 0)))
        // Jumat 18, 25 Sep, 2 Okt, + 9 Okt hanya bila sudah selesai.
        assertEquals(3, OfficialAttendance.heldEstimate(listOf(jumat to emptySet()), start, fri.atTime(8, 0)))
        assertEquals(4, OfficialAttendance.heldEstimate(listOf(jumat to emptySet()), start, fri.atTime(15, 0)))
        // Kelas pengganti yang sudah terjadi ditambahkan dari riwayat.
        assertEquals(5, OfficialAttendance.heldEstimate(listOf(jumat to emptySet()), start, fri.atTime(15, 0), extraHeld = 1))
    }

    @Test
    fun statusAndEstimateLines() {
        val rule = AttendanceRule(14, 75)
        val pd = OfficialAttendance.evaluate("Penambangan Data 4502", 21.43, rule, held = 3)
        assertEquals(3, pd.present)
        assertEquals(11, pd.required)
        assertEquals(8, pd.needMore)
        assertEquals(0, pd.absent)
        assertEquals(3, pd.remaining)
        assertEquals("SiAdin: 21.43% · hadir 3/14 · butuh 8 lagi (min. 11)", OfficialAttendance.summary(pd))
        assertEquals("Perkiraan: 3 pertemuan berlangsung · tidak hadir 0 · sisa jatah 3", OfficialAttendance.estimate(pd))
        assertNull(OfficialAttendance.warning(pd))
        // Tanpa awal semester: hanya angka pasti.
        val noStart = OfficialAttendance.evaluate("Kriptografi 4502", 28.57, rule, held = null)
        assertNull(OfficialAttendance.estimate(noStart))
        assertNull(OfficialAttendance.warning(noStart))
    }

    @Test
    fun warningsWhenAllowanceRunsOut() {
        val rule = AttendanceRule(14, 75)
        val last = OfficialAttendance.evaluate("A", 14.29, rule, held = 4) // hadir 2, tidak hadir 2 → sisa 1
        assertEquals(1, last.remaining)
        assertTrue(OfficialAttendance.warning(last)!!.contains("tinggal 1"))
        val gone = OfficialAttendance.evaluate("A", 14.29, rule, held = 5) // tidak hadir 3 → habis
        assertTrue(OfficialAttendance.warning(gone)!!.contains("HABIS"))
        val impossible = OfficialAttendance.evaluate("A", 7.14, rule, held = 5) // hadir 1 + sisa 9 = 10 < 11
        assertEquals(false, impossible.reachable)
        assertTrue(OfficialAttendance.warning(impossible)!!.contains("tidak mungkin"))
        val done = OfficialAttendance.evaluate("A", 78.57, rule, held = 11) // hadir 11
        assertEquals("SiAdin: 78.57% · hadir 11/14 · minimal 11 sudah tercapai ✓", OfficialAttendance.summary(done))
    }

    @Test
    fun telegramSummaryLines() {
        val rule = AttendanceRule(14, 75)
        val text = OfficialAttendance.telegramLines(
            listOf(
                OfficialAttendance.evaluate("Kriptografi 4502", 28.57, rule, held = null),
                OfficialAttendance.evaluate("Penambangan Data 4502", 21.43, rule, held = 3),
                OfficialAttendance.evaluate("A", 14.29, rule, held = 4),
            ),
        )!!
        assertEquals(
            "📋 Kehadiran resmi SiAdin:\n" +
                "• Kriptografi 4502: 28.57% (hadir 4/14, butuh 7 lagi)\n" +
                "• Penambangan Data 4502: 21.43% (hadir 3/14, butuh 8 lagi) — perkiraan sisa jatah 3\n" +
                "• A: 14.29% (hadir 2/14, butuh 9 lagi) — ⚠️ perkiraan sisa jatah 1",
            text,
        )
        assertNull(OfficialAttendance.telegramLines(emptyList()))
    }

    @Test
    fun verifyRecordedPresensi() {
        // 9 Okt: Kriptografi 21.43% (pagi) → 28.57% setelah presensi.
        assertEquals(VerifyOutcome.RECORDED, OfficialAttendance.verify(21.43, 28.57))
        assertEquals(VerifyOutcome.NOT_YET, OfficialAttendance.verify(21.43, 21.43))
        assertEquals(VerifyOutcome.UNKNOWN, OfficialAttendance.verify(null, 28.57))
        assertEquals(VerifyOutcome.UNKNOWN, OfficialAttendance.verify(21.43, null))
    }

    @Test
    fun snapshotsRoundTrip() {
        val m = mapOf("Kriptografi 4502" to OfficialSnapshot(28.57, 1_760_000_000_000), "MPTI 4515" to OfficialSnapshot(100.0, 5))
        assertEquals(m, OfficialAttendance.decode(OfficialAttendance.encode(m)))
        assertEquals(emptyMap<String, OfficialSnapshot>(), OfficialAttendance.decode("rusak"))
        assertEquals(emptyMap<String, OfficialSnapshot>(), OfficialAttendance.decode(null))
        assertEquals("28.57", OfficialAttendance.pct(28.57))
        assertEquals("100", OfficialAttendance.pct(100.0))
    }

    @Test
    fun singleCardPageOnlyCountsItsOwnCourse() {
        val page = "SiAdin\nSistem Informasi Akademik\nMasa studi: 4 th 1 bl\nPresensi Kuliah Online\nKRIPTOGRAFI\n" +
            "KDMK: A11.64501\nKLPK: A11.4502\n09 October 2026\n21.43 %\nBelum Jadwalnya\nSiAdin | Copyright © Udinus"
        assertEquals(
            mapOf("Kriptografi 4502" to 21.43),
            OfficialAttendance.match(listOf(page), listOf("Sistem Informasi 4507", "Kriptografi 4502")),
        )
    }
}
