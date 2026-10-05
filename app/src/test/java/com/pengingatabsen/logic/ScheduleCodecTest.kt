package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCodecTest {
    private val sample = listOf(
        CourseData("Pemrograman Game 4703", 1, 9 * 60 + 30, 12 * 60, "D.2.I", active = true),
        CourseData("Technopreneurship", 1, 12 * 60 + 30, null, null, active = false),
    )

    @Test
    fun roundTrip() {
        val decoded = ScheduleCodec.decode(ScheduleCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun encodeStartsWithHeader() {
        assertTrue(ScheduleCodec.encode(sample).startsWith(ScheduleCodec.HEADER + "\n"))
        assertTrue(ScheduleCodec.looksLikeSchedule(ScheduleCodec.encode(sample)))
        assertFalse(ScheduleCodec.looksLikeSchedule("halo dunia"))
    }

    @Test
    fun namesWithPipeSurviveRoundTrip() {
        val tricky = listOf(CourseData("Kalkulus | Lanjut", 3, 7 * 60, 9 * 60, "A|B", active = true))
        assertEquals(tricky, ScheduleCodec.decode(ScheduleCodec.encode(tricky)))
    }

    @Test
    fun malformedLinesAreSkipped() {
        val text = """
            ${ScheduleCodec.HEADER}
            1|570|720|1|Basis Data|H.3.4
            baris-ngawur-tanpa-pipe
            9|570|720|1|Hari salah|X
            1|abc|720|1|Jam salah|X
            2|480||1|Tanpa jam tutup|
        """.trimIndent()
        val decoded = ScheduleCodec.decode(text)
        assertEquals(2, decoded.size)
        assertEquals("Basis Data", decoded[0].name)
        assertEquals(null, decoded[1].closeMinute)
        assertEquals(null, decoded[1].room)
    }
}
