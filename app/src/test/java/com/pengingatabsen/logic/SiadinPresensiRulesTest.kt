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

    // ---------- 7 Okt (teks asli dari screenshot): nama jadwal pakai singkatan + kode kelas ----------

    private val mpti = "MANAJEMEN PROYEK TEKNOLOGI INFORMASI KDMK: A11.64504 KLPK: A11.4515 07 October 2026 28.57 % Belum Jadwalnya"
    private val psk = "PEMROGRAMAN SISI KLIEN KDMK: A11.64706 KLPK: A11.4702 07 October 2026 21.43 % Presensi Sekarang"

    @Test
    fun matches_byClassCode() {
        assertTrue(SiadinPresensiRules.matches(mpti, "MPTI 4515"))
        assertTrue(SiadinPresensiRules.matches(psk, "Pemrograman Sisi Klien 4702"))
        assertFalse(SiadinPresensiRules.matches(psk, "MPTI 4515"))
        assertFalse(SiadinPresensiRules.matches(mpti, "Sistem Terdistribusi 4512"))
        // "4504" hanya bagian dari KDMK A11.64504, bukan angka utuh: tidak boleh cocok.
        assertFalse(SiadinPresensiRules.matches(mpti, "Basis Data 4504"))
    }

    @Test
    fun matches_byAcronymOrWords() {
        assertTrue(SiadinPresensiRules.matches(mpti, "MPTI"))
        assertTrue(SiadinPresensiRules.matches(mpti, "Manajemen Proyek TI"))
        assertTrue(SiadinPresensiRules.matches(psk, "PSK"))
        assertFalse(SiadinPresensiRules.matches(psk, "MPTI"))
        assertTrue(SiadinPresensiRules.matches("KALKULUS II KDMK: X", "Kalkulus II"))
        assertFalse(SiadinPresensiRules.matches("KALKULUS I KDMK: X", "Kalkulus II"))
    }

    @Test
    fun pageStatus_realPage7Oct() {
        val cards = listOf(PresensiCard(mpti, "Belum Jadwalnya"), PresensiCard(psk, "Presensi Sekarang"))
        assertEquals(CardStatus.OPEN, SiadinPresensiRules.pageStatus(true, false, cards, "Pemrograman Sisi Klien 4702"))
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.pageStatus(true, false, cards, "MPTI 4515"))
        // Matkul lain (kode 4512) belum punya kartu: presensi Sisi Klien yang dibuka BUKAN miliknya.
        assertEquals(CardStatus.WAITING, SiadinPresensiRules.pageStatus(true, false, cards, "Sistem Terdistribusi 4512"))
    }

    @Test
    fun pick_sameClassCodeOnDifferentCourses() {
        // KLPK 4502 dipakai 3 matkul. Bila dua kartu ber-KLPK sama tampil bersamaan, nama yang menentukan.
        val tekno = "TECHNOPRENEURSHIP KDMK: A11.64601 KLPK: A11.4502 Presensi Sekarang"
        val kripto = "KRIPTOGRAFI KDMK: A11.64602 KLPK: A11.4502 Belum Jadwalnya"
        val cards = listOf(tekno, kripto)
        assertEquals(listOf(1), SiadinPresensiRules.pick(cards, "Kriptografi 4502"))
        assertEquals(listOf(0), SiadinPresensiRules.pick(cards, "Technopreneurship 4502"))
        // Kode saja (nama tak dikenali) & kode tidak unik → tidak menebak.
        assertEquals(emptyList<Int>(), SiadinPresensiRules.pick(cards, "Kelas Pak X 4502"))
        // Kode saja & unik → dipakai.
        assertEquals(listOf(0), SiadinPresensiRules.pick(listOf(tekno, mpti), "Kelas Pak X 4502"))
        // Kode salah ketik tapi nama cocok → nama yang dipakai.
        assertEquals(listOf(1), SiadinPresensiRules.pick(cards, "Kriptografi 9999"))
    }
}
