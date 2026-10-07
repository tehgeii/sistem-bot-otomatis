package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teks pada tes ini disalin dari screenshot SiAdin nyata (5 Okt 2026), supaya perubahan aturan
 * deteksi ketahuan langsung di CI, bukan baru saat kuliah.
 */
class SiadinPresensiRulesTest {
    @Test
    fun cardStatus_fromRealButtonTexts() {
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.cardStatus("Belum Jadwalnya"))
        assertEquals(CardStatus.OPEN, SiadinPresensiRules.cardStatus("Presensi Sekarang"))
        assertEquals(CardStatus.DONE, SiadinPresensiRules.cardStatus("Berhasil Presensi"))
        assertEquals(CardStatus.UNKNOWN, SiadinPresensiRules.cardStatus("Lihat Detail"))
    }

    @Test
    fun matches_courseNameWithClassCode() {
        // Jadwal "Pemrograman Game 4703" harus cocok dengan kartu "PEMROGRAMAN GAME".
        assertTrue(SiadinPresensiRules.matches("PEMROGRAMAN GAME KDMK: A11.64710", "Pemrograman Game 4703"))
        assertTrue(SiadinPresensiRules.matches("TECHNOPRENEURSHIP KDMK: AF201703", "Technopreneurship"))
        assertFalse(SiadinPresensiRules.matches("PEMROGRAMAN GAME", "Technopreneurship"))
    }

    private fun card(name: String, button: String) = PresensiCard("$name KDMK: X KLPK: Y", button)

    @Test
    fun pageStatus_waitingWhenAllBelumJadwalnya() {
        val cards = listOf(card("PEMROGRAMAN GAME", "Belum Jadwalnya"), card("TECHNOPRENEURSHIP", "Belum Jadwalnya"))
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.pageStatus(true, false, cards, "Pemrograman Game 4703"))
    }

    @Test
    fun pageStatus_openOnlyForTheMatchingCard() {
        // Game dibuka, Techno masih belum: pengingat Game -> OPEN, pengingat Techno -> WAITING.
        val cards = listOf(card("PEMROGRAMAN GAME", "Presensi Sekarang"), card("TECHNOPRENEURSHIP", "Belum Jadwalnya"))
        assertEquals(CardStatus.OPEN, SiadinPresensiRules.pageStatus(true, false, cards, "Pemrograman Game 4703"))
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.pageStatus(true, false, cards, "Technopreneurship"))
    }

    @Test
    fun pageStatus_doneAfterBerhasilPresensi() {
        val cards = listOf(card("PEMROGRAMAN GAME", "Berhasil Presensi"), card("TECHNOPRENEURSHIP", "Belum Jadwalnya"))
        assertEquals(CardStatus.DONE, SiadinPresensiRules.pageStatus(true, false, cards, "Pemrograman Game 4703"))
    }

    @Test
    fun pageStatus_belumAdaPresensiText() {
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.pageStatus(true, true, emptyList(), "Apa saja"))
    }

    @Test
    fun pageStatus_notLoggedInIsUnknown() {
        // Halaman tanpa login juga bisa bertuliskan "Belum Ada Presensi"; jangan dipercaya sebagai WAITING.
        assertEquals(CardStatus.UNKNOWN, SiadinPresensiRules.pageStatus(false, true, emptyList(), "Apa saja"))
    }

    @Test
    fun pageStatus_unmatchedCardsOnlyTriggerOpenNotDone() {
        // Nama jadwal tak cocok kartu mana pun: kartu lain hanya jadi cadangan untuk "dibuka".
        val cards = listOf(card("STATISTIKA", "Berhasil Presensi"))
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.pageStatus(true, false, cards, "Kalkulus"))
        val open = listOf(card("STATISTIKA", "Presensi Sekarang"))
        assertEquals(CardStatus.OPEN, SiadinPresensiRules.pageStatus(true, false, open, "Kalkulus"))
    }

    @Test
    fun pageStatus_cardsWinOverBelumAdaPresensiText() {
        // 7 Okt 2026: "Pemrograman Sisi Klien" sudah "Presensi Sekarang" sejak 09.30, tapi aplikasi
        // tetap "menunggu". Tulisan "Belum Ada Presensi" (sisa tampilan saat memuat) tak boleh mengalahkan kartu.
        val cards = listOf(
            card("MANAJEMEN PROYEK TEKNOLOGI INFORMASI", "Belum Jadwalnya"),
            card("PEMROGRAMAN SISI KLIEN", "Presensi Sekarang"),
        )
        assertEquals(CardStatus.OPEN, SiadinPresensiRules.pageStatus(true, true, cards, "Pemrograman Sisi Klien"))
        assertEquals(CardStatus.OPEN, SiadinPresensiRules.pageStatus(true, false, cards, "Pemrograman Sisi Klien 4702"))
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.pageStatus(true, true, cards, "Manajemen Proyek Teknologi Informasi"))
    }
}
