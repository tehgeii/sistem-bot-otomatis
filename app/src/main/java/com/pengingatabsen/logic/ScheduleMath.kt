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
    /**
     * Kelas pengganti: hanya terjadi SEKALI pada tanggal ini (bukan mingguan). [dayOfWeek] tetap diisi
     * sesuai hari tanggal ini.
     */
    val onlyDate: LocalDate? = null,
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
    /** Menjelang jam tutup, pengecekan dipercepat ke tiap 1 menit walau pakai data seluler. */
    const val SMART_TIGHT_WINDOW_MINUTES = 20L

    /**
     * Interval pengecekan SiAdin (menit) saat mode pintar menunggu presensi dibuka.
     * Wi-Fi (tak berbayar) → selalu 1 menit. Data seluler → 2 menit, dipercepat jadi 1 menit pada
     * [SMART_TIGHT_WINDOW_MINUTES] menit terakhir sebelum jendela berakhir, agar tetap hemat tapi
     * tidak telat di akhir kuliah. [minutesToEnd] = menit dari sekarang ke akhir jendela (bisa null).
     */
    fun smartCheckInterval(metered: Boolean, minutesToEnd: Long?): Int = when {
        !metered -> 1
        minutesToEnd != null && minutesToEnd <= SMART_TIGHT_WINDOW_MINUTES -> 1
        else -> 2
    }

    fun occurrenceOn(slot: Slot, date: LocalDate): Occurrence {
        val midnight = date.atStartOfDay()
        val open = midnight.plusMinutes(slot.openMinute.toLong())
        val close = slot.closeMinute?.takeIf { it > slot.openMinute }
        val end = (if (close != null) midnight.plusMinutes(close.toLong()) else open.plusMinutes(DEFAULT_WINDOW_MINUTES))
            .plusMinutes(slot.extraMinutes.coerceAtLeast(0).toLong())
        return Occurrence(date, open, end)
    }

    /**
     * Libur massal: bila SEMUA matkul aktif sedang libur (tanggal libur ≥ [today]), kembalikan tanggal
     * libur paling awal berakhir (saat pengingat pertama kembali). Selain itu null.
     */
    fun allPausedUntil(activeSkipUntil: List<LocalDate?>, today: LocalDate): LocalDate? {
        if (activeSkipUntil.isEmpty()) return null
        if (activeSkipUntil.any { it == null || it.isBefore(today) }) return null
        return activeSkipUntil.filterNotNull().minOrNull()
    }

    fun isSkipped(slot: Slot, date: LocalDate): Boolean =
        slot.skipUntil?.let { !date.isAfter(it) } ?: false

    /** Apakah jadwal ini ada kuliah pada [date] (belum memperhitungkan libur): kelas pengganti hanya di tanggalnya. */
    fun isOn(slot: Slot, date: LocalDate): Boolean =
        slot.onlyDate?.let { it == date } ?: (date.dayOfWeek.value == slot.dayOfWeek)

    /**
     * Kelas pengganti pada [date]: kemunculan jadwal biasa ([slot]) yang paling mungkin DIGANTIKAN, yaitu yang
     * sedang berlangsung atau berikutnya setelah [now], asalkan berjarak paling jauh 6 hari dari [date]
     * (minggu yang sama, atau dipindah ke awal minggu depan). Null bila tidak ada yang cocok.
     */
    fun replacedOccurrence(slot: Slot, date: LocalDate, now: LocalDateTime): LocalDate? {
        if (slot.onlyDate != null) return null
        val next = currentOccurrence(slot, now) ?: nextOccurrence(slot, now) ?: return null
        val gap = kotlin.math.abs(next.date.toEpochDay() - date.toEpochDay())
        return next.date.takeIf { gap <= 6 }
    }

    /** Kemunculan yang jendelanya sedang berlangsung pada [now], atau null. */
    fun currentOccurrence(slot: Slot, now: LocalDateTime): Occurrence? {
        val today = now.toLocalDate()
        val date = slot.onlyDate ?: today.minusDays(((today.dayOfWeek.value - slot.dayOfWeek + 7) % 7).toLong())
        val occ = occurrenceOn(slot, date)
        val inside = !now.isBefore(occ.open) && now.isBefore(occ.end)
        return if (inside && !isSkipped(slot, date)) occ else null
    }

    /**
     * Kemunculan berikutnya yang jam bukanya setelah [after], melewati tanggal libur.
     * Null bila tidak ada lagi (kelas pengganti yang sudah lewat, atau libur lebih dari 10 tahun).
     */
    fun nextOccurrence(slot: Slot, after: LocalDateTime): Occurrence? {
        slot.onlyDate?.let { date ->
            val occ = occurrenceOn(slot, date)
            return if (occ.open.isAfter(after) && !isSkipped(slot, date)) occ else null
        }
        val start = after.toLocalDate()
        var date = start.plusDays(((slot.dayOfWeek - start.dayOfWeek.value + 7) % 7).toLong())
        repeat(MAX_WEEKS) {
            val occ = occurrenceOn(slot, date)
            if (occ.open.isAfter(after) && !isSkipped(slot, date)) return occ
            date = date.plusWeeks(1)
        }
        return null
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
     * Null bila tidak ada lagi yang perlu diingatkan (mis. kelas pengganti yang sudah lewat/selesai).
     */
    fun plan(
        slot: Slot,
        now: LocalDateTime,
        intervalMinutes: Int,
        stateOf: (LocalDate) -> OccurrenceState?,
    ): PlannedAlarm? {
        currentOccurrence(slot, now)?.let { occ ->
            val state = stateOf(occ.date)
            if (state?.finished != true) {
                nextEvent(occ, intervalMinutes, state?.snoozeUntil, now)?.let { return PlannedAlarm(occ, it) }
            }
        }
        var occ = nextOccurrence(slot, now) ?: return null
        repeat(MAX_WEEKS) {
            if (stateOf(occ.date)?.finished != true) return PlannedAlarm(occ, AlarmEvent(EventType.OPEN, occ.open))
            occ = nextOccurrence(slot, occ.open) ?: return null
        }
        return null
    }

    private const val MAX_WEEKS = 520
}
