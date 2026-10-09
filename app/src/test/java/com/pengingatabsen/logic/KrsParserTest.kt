package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teks kartu KRS asli dari screenshot 8 Okt 2026 (desktop) dan bentuk per-baris (seperti di HP). */
class KrsParserTest {
    private val sisterOneLine = """
        SISTEM TERDISTRIBUSI
        3 SKS
        KDMK: A11.64501 —— KLPK: A11.4512
        • KAMIS 12.30-15.00 Kulino
        • -
        • -
        28.57 %
    """.trimIndent()

    // Kolom hari / jam / ruang terpisah baris (innerText elemen flex di HP).
    private val mptiSplit = """
        MANAJEMEN PROYEK TEKNOLOGI INFORMASI
        3 SKS
        KDMK:
        A11.64504
        KLPK:
        A11.4515
        RABU
        12.30-15.00
        Kulino
        -
        -
        28.57 %
    """.trimIndent()

    private val technopreneurship = """
        TECHNOPRENEURSHIP 2 SKS
        KDMK: AF201703 —— KLPK: A11.4502
        SENIN 12.30-14.10 H.5.9
        -
        -
        28.57 %
    """.trimIndent()

    @Test
    fun parsesRealCards() {
        assertEquals(
            listOf(CourseData("Sistem Terdistribusi 4512", 4, 12 * 60 + 30, 15 * 60, "Kulino")),
            KrsParser.parseCard(sisterOneLine),
        )
        assertEquals(
            listOf(CourseData("Manajemen Proyek Teknologi Informasi 4515", 3, 12 * 60 + 30, 15 * 60, "Kulino")),
            KrsParser.parseCard(mptiSplit),
        )
        assertEquals(
            listOf(CourseData("Technopreneurship 4502", 1, 12 * 60 + 30, 14 * 60 + 10, "H.5.9")),
            KrsParser.parseCard(technopreneurship),
        )
    }

    @Test
    fun multipleRowsAndNoRoom() {
        val card = """
            KALKULUS II 3 SKS
            KDMK: A11.1 —— KLPK: A11.4401
            • SENIN 07.00-08.40 D.3.1
            • JUM'AT 09.30-11.10
            • -
        """.trimIndent()
        val parsed = KrsParser.parseCard(card)
        assertEquals(2, parsed.size)
        assertEquals("Kalkulus II 4401", parsed[0].name)
        assertEquals(5, parsed[1].dayOfWeek)
        assertEquals(null, parsed[1].room)
    }

    @Test
    fun dedupeAgainstUserNamedSchedule() {
        // Jadwal buatan sendiri "MPTI 4515" (Rabu 12:30) = kartu KRS MPTI → tidak ditambah lagi.
        val existing = listOf(CourseData("MPTI 4515", 3, 12 * 60 + 30, 15 * 60, "Kulino"))
        val mpti = KrsParser.parseCard(mptiSplit).single()
        assertTrue(KrsParser.alreadyExists(mpti, existing))
        val sister = KrsParser.parseCard(sisterOneLine).single()
        assertFalse(KrsParser.alreadyExists(sister, existing))
    }

    @Test
    fun ignoresCardsWithoutSchedule() {
        assertTrue(KrsParser.parseCard("SKRIPSI 6 SKS\nKDMK: A11.9 —— KLPK: A11.4999\n• -\n• -").isEmpty())
        assertTrue(KrsParser.parse(listOf("")).isEmpty())
    }

    @Test
    fun singleCardKrsPageIgnoresMenuAndNavbar() {
        val page = "SiAdin Sistem Informasi Akademik BUDI\nMasa studi: 4 th 1 bl 9 hr\n" +
            "KRS KHS Jadwal Ujian Presensi Online Daftar Nilai Matrikulasi Semester Antara\n" + technopreneurship +
            "\nSiAdin | Copyright © Udinus 2008 - 2026"
        assertEquals(
            listOf(CourseData("Technopreneurship 4502", 1, 12 * 60 + 30, 14 * 60 + 10, "H.5.9")),
            KrsParser.parse(listOf(page)),
        )
        // Kartu yang sudah bersih tetap sama hasilnya.
        assertEquals(KrsParser.parseCard(technopreneurship), KrsParser.parse(listOf(technopreneurship)))
    }
}
