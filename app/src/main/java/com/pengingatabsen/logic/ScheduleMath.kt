package com.pengingatabsen.logic

import java.time.LocalDate
import java.time.LocalDateTime

/** Jenis alarm dalam satu jendela absen. */
enum class EventType {
    /** Absen dibuka. */
    OPEN,
    /** Pengingat ulang (tiap N menit). */
    REMIND,
    /** Peringatan terakhir, 5 menit sebelum ditutup. */
    FINAL,
    /** Jendela berakhir: jika belum selesai dicatat "terlewat". */
    EXPIRE,
}

data class AlarmEvent(val type: EventType, val time: LocalDateTime)

/** Data jadwal satu matkul tanpa ketergantungan Android, supaya mudah dites. */
data class Slot(
    /** 1 = Senin ... 7 = Minggu (ISO-8601). */
    val dayOfWeek: Int,
    /** Menit sejak 00:00. */
    val openMinute: Int,
    /** Menit sejak 00:00; null = tidak ada jam tutup. */
    val closeMinute: Int?,
    /** Kemunculan pada tanggal <= ini dilewati (libur). */
    val skipUntil: LocalDate? = null,
    /** Perpanjangan jendela setelah jam tutup (mode pintar: dosen sering membuka presensi terlambat). */
    val extraMinutes: Int = 0,
)

/** Satu kemunculan slot pada tanggal tertentu. */
data class Occurrence(
    val date: LocalDate,
    val open: LocalDateTime,
    /** Jam tutup (atau open + 30 menit bila kosong), ditambah perpanjangan bila ada. */
    val end: LocalDateTime,
)

/** Status kemunculan yang tersimpan di riwayat. */
data class OccurrenceState(
    /** Sudah ditandai selesai / libur / terlewat — tidak perlu diingatkan lagi. */
    val finished: Boolean,
    /** Pengingat ditunda sampai waktu ini. */
    val snoozeUntil: LocalDateTime? = null,
)

data class PlannedAlarm(val occurrence: Occurrence, val event: AlarmEvent)

object ScheduleMath {
    /** Lama jendela bila jam tutup kosong. */
    const val DEFAULT_WINDOW_MINUTES = 30L
    /** Peringatan terakhir dikirim sekian menit sebelum ditutup. */
    const val FINAL_WARNING_MINUTES = 5L
    const val DEFAULT_REMIND_INTERVAL = 3
    /** Mode pintar: SiAdin tetap dicek sampai sekian menit setelah jam tutup. */
    const val SMART_GRACE_MINUTES = 15

    fun occurrenceOn(slot: Slot, date: LocalDate): Occurrence {
        val midnight = date.atStartOfDay()
        val open = midnight.plusMinutes(slot.openMinute.toLong())
        val close = slot.closeMinute?.takeIf { it > slot.openMinute }
        val end = (if (close != null) midnight.plusMinutes(close.toLong()) else open.plusMinutes(DEFAULT_WINDOW_MINUTES))
            .plusMinutes(slot.extraMinutes.coerceAtLeast(0).toLong())
        return Occurrence(date, open, end)
    }

    fun isSkipped(slot: Slot, date: LocalDate): Boolean =
        slot.skipUntil?.let { !date.isAfter(it) } ?: false

    /** Kemunculan yang jendelanya sedang berlangsung pada [now], atau null. */
    fun currentOccurrence(slot: Slot, now: LocalDateTime): Occurrence? {
        val today = now.toLocalDate()
        val back = (today.dayOfWeek.value - slot.dayOfWeek + 7) % 7
        val date = today.minusDays(back.toLong())
        val occ = occurrenceOn(slot, date)
        val inside = !now.isBefore(occ.open) && now.isBefore(occ.end)
        return if (inside && !isSkipped(slot, date)) occ else null
    }

    /** Kemunculan berikutnya yang jam bukanya setelah [after], melewati tanggal libur. */
    fun nextOccurrence(slot: Slot, after: LocalDateTime): Occurrence {
        val start = after.toLocalDate()
        var date = start.plusDays(((slot.dayOfWeek - start.dayOfWeek.value + 7) % 7).toLong())
        repeat(MAX_WEEKS) {
            val occ = occurrenceOn(slot, date)
            if (occ.open.isAfter(after) && !isSkipped(slot, date)) return occ
            date = date.plusWeeks(1)
        }
        return occurrenceOn(slot, date)
    }

    /**
     * Semua alarm dalam satu jendela, urut waktu:
     * OPEN, REMIND tiap [intervalMinutes] (atau mulai [snoozeUntil] bila ditunda),
     * FINAL 5 menit sebelum tutup, lalu EXPIRE saat tutup.
     */
    fun events(occ: Occurrence, intervalMinutes: Int, snoozeUntil: LocalDateTime? = null): List<AlarmEvent> {
        val interval = intervalMinutes.coerceAtLeast(1).toLong()
        val final = occ.end.minusMinutes(FINAL_WARNING_MINUTES)
        val hasFinal = final.isAfter(occ.open)
        val remindLimit = if (hasFinal) final else occ.end
        val snooze = snoozeUntil?.takeIf { it.isAfter(occ.open) }

        val list = mutableListOf<AlarmEvent>()
        if (snooze == null) list += AlarmEvent(EventType.OPEN, occ.open)
        var t = snooze ?: occ.open.plusMinutes(interval)
        while (t.isBefore(remindLimit)) {
            list += AlarmEvent(EventType.REMIND, t)
            t = t.plusMinutes(interval)
        }
        if (hasFinal) list += AlarmEvent(EventType.FINAL, final)
        list += AlarmEvent(EventType.EXPIRE, occ.end)
        return list.sortedBy { it.time }
    }

    /** Alarm pertama dalam jendela yang waktunya setelah [now]. */
    fun nextEvent(
        occ: Occurrence,
        intervalMinutes: Int,
        snoozeUntil: LocalDateTime?,
        now: LocalDateTime,
    ): AlarmEvent? = events(occ, intervalMinutes, snoozeUntil).firstOrNull { it.time.isAfter(now) }

    /**
     * Alarm berikutnya untuk satu matkul.
     * Jika jendela sedang berlangsung dan belum selesai, lanjutkan pengingat;
     * jika tidak, alarm OPEN pada kemunculan berikutnya yang belum selesai.
     */
    fun plan(
        slot: Slot,
        now: LocalDateTime,
        intervalMinutes: Int,
        stateOf: (LocalDate) -> OccurrenceState?,
    ): PlannedAlarm {
        currentOccurrence(slot, now)?.let { occ ->
            val state = stateOf(occ.date)
            if (state?.finished != true) {
                nextEvent(occ, intervalMinutes, state?.snoozeUntil, now)?.let { return PlannedAlarm(occ, it) }
            }
        }
        var occ = nextOccurrence(slot, now)
        repeat(MAX_WEEKS) {
            if (stateOf(occ.date)?.finished != true) return PlannedAlarm(occ, AlarmEvent(EventType.OPEN, occ.open))
            occ = nextOccurrence(slot, occ.open)
        }
        return PlannedAlarm(occ, AlarmEvent(EventType.OPEN, occ.open))
    }

    private const val MAX_WEEKS = 520
}
