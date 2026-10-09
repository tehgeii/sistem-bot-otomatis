package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDiffTest {
    private fun krs(name: String, day: Int, open: Int, close: Int?, room: String? = "Kulino") = CourseData(name, day, open, close, room)
    private fun app(id: Long, name: String, day: Int, open: Int, close: Int? = null) = id to CourseData(name, day, open, close, null)

    private val krsNow = listOf(
        krs("Kriptografi 4502", 5, 9 * 60 + 30, 12 * 60 + 15),
        krs("Penambangan Data 4502", 2, 9 * 60 + 30, 12 * 60),
        krs("Technopreneurship 4502", 1, 12 * 60 + 30, 14 * 60 + 10, "H.5.9"),
        krs("Manajemen Proyek Teknologi Informasi 4515", 3, 12 * 60 + 30, 15 * 60),
    )
    private val appNow = listOf(
        app(1, "Kriptografi 4502", 5, 9 * 60 + 30, 12 * 60 + 15),
        app(2, "Penambangan Data 4502", 2, 9 * 60 + 30, 12 * 60 + 30), // jam tutup diperpanjang sendiri
        app(3, "Technopreneurship 4502", 1, 12 * 60 + 30, 14 * 60 + 10),
        app(4, "MPTI 4515", 3, 12 * 60 + 30, 15 * 60),
        app(5, "Les Bahasa Inggris", 6, 8 * 60), // ditambah sendiri, tidak ada di KRS
    )

    @Test
    fun noChangeWhenSame() {
        // Jam tutup berbeda & jadwal tambahan sendiri tidak dilaporkan; singkatan MPTI dikenali.
        assertTrue(ScheduleDiff.compare(krsNow, appNow).isEmpty())
    }

    @Test
    fun detectsMovedCourse() {
        val moved = krsNow.map { if (it.name.startsWith("Kriptografi")) it.copy(dayOfWeek = 4, openMinute = 13 * 60, closeMinute = 15 * 60 + 30) else it }
        val changes = ScheduleDiff.compare(moved, appNow)
        assertEquals(1, changes.size)
        val c = changes[0]
        assertEquals("Kriptografi 4502", c.name)
        assertEquals(listOf(SlotTime(5, 9 * 60 + 30, 12 * 60 + 15)), c.before)
        assertEquals(listOf(SlotTime(4, 13 * 60, 15 * 60 + 30)), c.after)
        assertEquals(listOf(1L), c.appCourseIds)
        assertTrue(c.applicable)
        assertEquals("Kriptografi 4502: Jumat 09:30–12:15 → Kamis 13:00–15:30", c.describe())
    }

    @Test
    fun detectsNewCourseAndKeepsCodeSharedCoursesApart() {
        // Sistem Terdistribusi baru; KLPK 4502 dipakai 3 matkul tapi tetap dicocokkan per nama.
        val withNew = krsNow + krs("Sistem Terdistribusi 4512", 4, 12 * 60 + 30, 15 * 60)
        val changes = ScheduleDiff.compare(withNew, appNow)
        assertEquals(1, changes.size)
        assertTrue(changes[0].isNew)
        assertTrue(changes[0].applicable)
        assertEquals("Baru di KRS: Sistem Terdistribusi 4512 (Kamis 12:30–15:00)", changes[0].describe())
        assertEquals("Kulino", changes[0].room)
    }

    @Test
    fun multiSlotChangeIsManual() {
        // Matkul jadi dua kali seminggu → dilaporkan, tapi tidak diterapkan otomatis.
        val twice = krsNow + krs("Kriptografi 4502", 2, 7 * 60, 9 * 60)
        val c = ScheduleDiff.compare(twice, appNow).single()
        assertFalse(c.applicable)
        assertTrue(c.describe().endsWith("(ubah manual)"))
    }

    @Test
    fun ambiguousNamesAreNotGuessed() {
        // Nama NgiBsen tanpa kode yang cocok dengan dua matkul KRS: tidak dianggap pindah, tidak dianggap baru.
        val krsList = listOf(krs("Sistem Informasi 4507", 5, 7 * 60, 9 * 60), krs("Sistem Informasi Lanjut 4508", 2, 7 * 60, 9 * 60))
        val appList = listOf(app(1, "Sistem Informasi", 3, 7 * 60))
        assertTrue(ScheduleDiff.compare(krsList, appList).isEmpty())
    }

    @Test
    fun encodeDecodeAndSignature() {
        val moved = krsNow.map { if (it.name.startsWith("Kriptografi")) it.copy(dayOfWeek = 4) else it } +
            krs("Sistem Terdistribusi 4512", 4, 12 * 60 + 30, null)
        val changes = ScheduleDiff.compare(moved, appNow)
        assertEquals(changes, ScheduleDiff.decode(ScheduleDiff.encode(changes)))
        assertEquals(ScheduleDiff.signature(changes), ScheduleDiff.signature(ScheduleDiff.decode(ScheduleDiff.encode(changes))))
        assertTrue(ScheduleDiff.decode("rusak").isEmpty())
        assertTrue(ScheduleDiff.decode(null).isEmpty())
        assertEquals("", ScheduleDiff.signature(emptyList()))
    }
}
