package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleMathTest {
    // 5 Oktober 2026 = Senin
    private val monday = LocalDate.of(2026, 10, 5)
    private fun at(date: LocalDate, h: Int, m: Int, s: Int = 0) = date.atTime(h, m, s)
    private fun mins(h: Int, m: Int) = h * 60 + m

    private val senin0700 = Slot(dayOfWeek = 1, openMinute = mins(7, 0), closeMinute = mins(7, 20))

    // ---------- Alarm berikutnya ----------

    @Test
    fun nextOccurrence_sameDayBeforeOpen() {
        val occ = ScheduleMath.nextOccurrence(senin0700, at(monday, 6, 0))
        assertEquals(at(monday, 7, 0), occ.open)
        assertEquals(at(monday, 7, 20), occ.end)
    }

    @Test
    fun nextOccurrence_afterOpenGoesToNextWeek() {
        val occ = ScheduleMath.nextOccurrence(senin0700, at(monday, 7, 0))
        assertEquals(at(monday.plusWeeks(1), 7, 0), occ.open)
    }

    @Test
    fun nextOccurrence_laterInWeek() {
        val jumat = Slot(dayOfWeek = 5, openMinute = mins(13, 0), closeMinute = null)
        val occ = ScheduleMath.nextOccurrence(jumat, at(monday, 9, 0))
        assertEquals(at(monday.plusDays(4), 13, 0), occ.open)
    }

    @Test
    fun nextOccurrence_dayAlreadyPassedThisWeek() {
        // Rabu, cari Senin → Senin depan
        val occ = ScheduleMath.nextOccurrence(senin0700, at(monday.plusDays(2), 10, 0))
        assertEquals(at(monday.plusWeeks(1), 7, 0), occ.open)
    }

    @Test
    fun nextOccurrence_skipsHoliday() {
        val libur = senin0700.copy(skipUntil = monday)
        val occ = ScheduleMath.nextOccurrence(libur, at(monday, 6, 0))
        assertEquals(at(monday.plusWeeks(1), 7, 0), occ.open)
    }

    @Test
    fun nextOccurrence_skipUntilEndOfWeek() {
        val libur = senin0700.copy(skipUntil = monday.plusDays(6))
        val occ = ScheduleMath.nextOccurrence(libur, at(monday, 6, 0))
        assertEquals(at(monday.plusWeeks(1), 7, 0), occ.open)
    }

    @Test
    fun occurrence_withoutCloseLasts30Minutes() {
        val slot = Slot(1, mins(7, 0), null)
        assertEquals(at(monday, 7, 30), ScheduleMath.occurrenceOn(slot, monday).end)
    }

    @Test
    fun occurrence_extraMinutesExtendsEnd() {
        val slot = senin0700.copy(extraMinutes = ScheduleMath.SMART_GRACE_MINUTES)
        assertEquals(at(monday, 7, 35), ScheduleMath.occurrenceOn(slot, monday).end)
        // Masih dalam jendela 10 menit setelah jam tutup asli (07:20).
        assertEquals(at(monday, 7, 0), ScheduleMath.currentOccurrence(slot, at(monday, 7, 30))?.open)
        val noClose = Slot(1, mins(7, 0), null, extraMinutes = 15)
        assertEquals(at(monday, 7, 45), ScheduleMath.occurrenceOn(noClose, monday).end)
    }

    @Test
    fun occurrence_closeBeforeOpenTreatedAsNoClose() {
        val slot = Slot(1, mins(9, 0), mins(8, 0))
        assertEquals(at(monday, 9, 30), ScheduleMath.occurrenceOn(slot, monday).end)
    }

    @Test
    fun currentOccurrence_insideAndOutside() {
        assertEquals(at(monday, 7, 0), ScheduleMath.currentOccurrence(senin0700, at(monday, 7, 10))?.open)
        assertNull(ScheduleMath.currentOccurrence(senin0700, at(monday, 7, 20)))
        assertNull(ScheduleMath.currentOccurrence(senin0700, at(monday, 6, 59)))
        assertNull(ScheduleMath.currentOccurrence(senin0700.copy(skipUntil = monday), at(monday, 7, 10)))
    }

    @Test
    fun currentOccurrence_windowCrossingMidnight() {
        val malam = Slot(1, mins(23, 45), null) // berakhir Selasa 00:15
        val occ = ScheduleMath.currentOccurrence(malam, at(monday.plusDays(1), 0, 5))
        assertEquals(at(monday, 23, 45), occ?.open)
    }

    // ---------- Pengingat ulang ----------

    @Test
    fun events_everyThreeMinutesThenFinalThenExpire() {
        val occ = ScheduleMath.occurrenceOn(senin0700, monday) // 07:00–07:20, final 07:15
        val events = ScheduleMath.events(occ, 3)
        assertEquals(
            listOf(
                EventType.OPEN to at(monday, 7, 0),
                EventType.REMIND to at(monday, 7, 3),
                EventType.REMIND to at(monday, 7, 6),
                EventType.REMIND to at(monday, 7, 9),
                EventType.REMIND to at(monday, 7, 12),
                EventType.FINAL to at(monday, 7, 15),
                EventType.EXPIRE to at(monday, 7, 20),
            ),
            events.map { it.type to it.time },
        )
    }

    @Test
    fun events_noCloseStopsAfter30Minutes() {
        val occ = ScheduleMath.occurrenceOn(Slot(1, mins(7, 0), null), monday)
        val events = ScheduleMath.events(occ, 10)
        assertEquals(
            listOf(
                EventType.OPEN to at(monday, 7, 0),
                EventType.REMIND to at(monday, 7, 10),
                EventType.REMIND to at(monday, 7, 20),
                EventType.FINAL to at(monday, 7, 25),
                EventType.EXPIRE to at(monday, 7, 30),
            ),
            events.map { it.type to it.time },
        )
    }

    @Test
    fun events_shortWindowHasNoFinal() {
        val occ = ScheduleMath.occurrenceOn(Slot(1, mins(7, 0), mins(7, 4)), monday)
        val events = ScheduleMath.events(occ, 3)
        assertEquals(
            listOf(EventType.OPEN, EventType.REMIND, EventType.EXPIRE),
            events.map { it.type },
        )
    }

    @Test
    fun events_snoozeShiftsReminders() {
        val occ = ScheduleMath.occurrenceOn(senin0700, monday)
        val events = ScheduleMath.events(occ, 3, snoozeUntil = at(monday, 7, 7, 30))
        assertEquals(
            listOf(
                EventType.REMIND to at(monday, 7, 7, 30),
                EventType.REMIND to at(monday, 7, 10, 30),
                EventType.REMIND to at(monday, 7, 13, 30),
                EventType.FINAL to at(monday, 7, 15),
                EventType.EXPIRE to at(monday, 7, 20),
            ),
            events.map { it.type to it.time },
        )
    }

    @Test
    fun events_snoozePastFinalKeepsFinal() {
        val occ = ScheduleMath.occurrenceOn(senin0700, monday)
        val events = ScheduleMath.events(occ, 3, snoozeUntil = at(monday, 7, 16))
        assertEquals(listOf(EventType.FINAL, EventType.EXPIRE), events.map { it.type })
    }

    @Test
    fun nextEvent_isStrictlyAfterNow() {
        val occ = ScheduleMath.occurrenceOn(senin0700, monday)
        assertEquals(at(monday, 7, 3), ScheduleMath.nextEvent(occ, 3, null, at(monday, 7, 0))?.time)
        assertEquals(EventType.FINAL, ScheduleMath.nextEvent(occ, 3, null, at(monday, 7, 12))?.type)
        assertEquals(EventType.EXPIRE, ScheduleMath.nextEvent(occ, 3, null, at(monday, 7, 15))?.type)
        assertNull(ScheduleMath.nextEvent(occ, 3, null, at(monday, 7, 20)))
    }

    // ---------- Rencana per matkul ----------

    @Test
    fun plan_beforeOpenSchedulesOpen() {
        val p = ScheduleMath.plan(senin0700, at(monday, 6, 0), 3) { null }
        assertEquals(EventType.OPEN, p.event.type)
        assertEquals(at(monday, 7, 0), p.event.time)
    }

    @Test
    fun plan_insideWindowContinuesReminders() {
        val p = ScheduleMath.plan(senin0700, at(monday, 7, 4), 3) { OccurrenceState(finished = false) }
        assertEquals(EventType.REMIND, p.event.type)
        assertEquals(at(monday, 7, 6), p.event.time)
    }

    @Test
    fun plan_finishedJumpsToNextWeek() {
        val p = ScheduleMath.plan(senin0700, at(monday, 7, 4), 3) { d ->
            if (d == monday) OccurrenceState(finished = true) else null
        }
        assertEquals(EventType.OPEN, p.event.type)
        assertEquals(at(monday.plusWeeks(1), 7, 0), p.event.time)
    }

    @Test
    fun plan_respectsSnooze() {
        val p = ScheduleMath.plan(senin0700, at(monday, 7, 1), 3) {
            OccurrenceState(finished = false, snoozeUntil = at(monday, 7, 6))
        }
        assertEquals(at(monday, 7, 6), p.event.time)
    }

    @Test
    fun plan_skipsFutureHolidayRecord() {
        val nextMonday = monday.plusWeeks(1)
        val p = ScheduleMath.plan(senin0700, at(monday, 8, 0), 3) { d ->
            if (d == nextMonday) OccurrenceState(finished = true) else null
        }
        assertEquals(at(monday.plusWeeks(2), 7, 0), p.event.time)
    }

    // ---------- Format pesan ----------

    @Test
    fun proofMessageFormat() {
        assertEquals(
            "✅ Absen Basis Data — Senin, 5 Oktober 2026 07:03:09",
            Formatters.proofMessage("Basis Data", at(monday, 7, 3, 9)),
        )
    }
}
