package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private val sample = Backup(
        createdAt = "2026-10-09T12:00:00",
        appVersion = "3.1",
        courses = listOf(
            BackupCourse(1, "Sistem Terdistribusi 4512", 4, 12 * 60 + 30, 15 * 60, "Kulino", true, null, null),
            BackupCourse(2, "MPTI 4515", 3, 12 * 60 + 30, null, null, false, 20_380L, null),
            // Kelas pengganti Sabtu 10 Okt 2026.
            BackupCourse(3, "MPTI 4515", 6, 8 * 60, 10 * 60, "H.5.9", true, null, 20_371L),
        ),
        records = listOf(
            BackupRecord(1, "Sistem Terdistribusi 4512", 20_369L, 1_760_000_000_000, 1_760_009_000_000, "SENT", 1_760_000_100_000, null),
            BackupRecord(2, "MPTI | koma, \"kutip\"", 20_368L, 1_759_900_000_000, 1_759_909_000_000, "MISSED", null, "gagal: tidak ada koneksi"),
            BackupRecord(0, "Tanpa matkul", -1_760_000_000_000, 1_760_000_000_000, 1_760_000_000_000, "QUEUED", 1_760_000_000_000, null),
        ),
        settings = mapOf("remind_interval" to 3, "vibrate_only" to true, "deep_link" to "https://mhs.dinus.ac.id/akademik/presensiOnline"),
    )

    @Test
    fun roundTripKeepsEverything() {
        val text = BackupCodec.encode(sample)
        val back = BackupCodec.decode(text)
        assertEquals(sample.courses, back.courses)
        assertEquals(sample.records, back.records)
        assertEquals(sample.settings, back.settings)
        assertEquals("3.1", back.appVersion)
        assertEquals("2026-10-09T12:00:00", back.createdAt)
    }

    @Test
    fun neverContainsSecretsKeys() {
        // Codec hanya menyalin apa yang diberikan; pastikan berkas contoh tidak memuat kunci rahasia.
        val text = BackupCodec.encode(sample)
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertTrue(text.startsWith("{"))
    }

    @Test
    fun rejectsForeignOrNewerFiles() {
        expectError("bukan JSON") { BackupCodec.decode("NGIBSEN-JADWAL-1\n1|420|500|1|Basis Data|") }
        expectError("bukan cadangan") { BackupCodec.decode("""{"format":"lain","version":1}""") }
        expectError("lebih baru") { BackupCodec.decode("""{"format":"NGIBSEN-CADANGAN","version":99}""") }
    }

    @Test
    fun skipsBrokenEntriesAndDuplicates() {
        val text = """
            {"format":"NGIBSEN-CADANGAN","version":1,
             "courses":[
               {"id":1,"name":"A","dayOfWeek":1,"openMinute":420},
               {"id":1,"name":"A duplikat","dayOfWeek":2,"openMinute":420},
               {"id":2,"name":"","dayOfWeek":1,"openMinute":420},
               {"id":3,"name":"B","dayOfWeek":9,"openMinute":420},
               {"id":4,"name":"C","dayOfWeek":5,"openMinute":2000},
               {"id":5,"name":"D","dayOfWeek":5,"openMinute":600,"closeMinute":null,"room":null}
             ],
             "records":[
               {"courseId":1,"courseName":"A","epochDay":10,"openAtMillis":5,"status":"SENT"},
               {"courseId":1,"courseName":"A","epochDay":10,"openAtMillis":6,"status":"MISSED"},
               {"courseId":1,"courseName":"A","epochDay":11,"status":"SENT"},
               {"courseId":1,"courseName":"A","epochDay":12,"openAtMillis":7,"status":""}
             ],
             "settings":{"remind_interval":5,"aneh":{"x":1},"tes":1.0E10}}
        """.trimIndent()
        val b = BackupCodec.decode("﻿" + text)
        assertEquals(listOf("A", "D"), b.courses.map { it.name })
        assertEquals(null, b.courses[1].closeMinute)
        assertEquals(null, b.courses[1].room)
        assertEquals(1, b.records.size)
        assertEquals("SENT", b.records[0].status)
        assertEquals(5L, b.records[0].endAtMillis)
        assertEquals(5, (b.settings["remind_interval"] as Number).toInt())
        assertFalse(b.settings.containsKey("aneh"))
    }

    @Test
    fun fileNameHasDate() {
        assertEquals("NgiBsen-cadangan-2026-10-09.json", BackupCodec.fileName(java.time.LocalDate.of(2026, 10, 9)))
    }

    private fun expectError(part: String, block: () -> Unit) {
        try {
            block()
            fail("harus gagal: $part")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains(part))
        }
    }
}
