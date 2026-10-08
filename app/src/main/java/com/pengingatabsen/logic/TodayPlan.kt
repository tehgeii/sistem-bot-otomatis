package com.pengingatabsen.logic

import java.time.LocalDate
import java.time.LocalDateTime

/** Status satu matkul di layar "Hari ini". */
enum class TodayState {
    /** Belum jam buka (tampil hitung mundur). */
    UPCOMING,
    /** Jam kuliah berjalan, presensi belum terlihat dibuka. */
    WAITING,
    /** Presensi sudah terlihat dibuka dosen (mode pintar). */
    OPEN,
    /** Sudah presensi (bukti terkirim/antre). */
    DONE,
    /** Bukti gagal terkirim (presensi sudah). */
    FAILED,
    HOLIDAY,
    MISSED,
    /** Dosen tidak membuka presensi sampai jam tutup. */
    NO_SESSION,
    /** Jam kuliah sudah lewat tanpa catatan. */
    ENDED,
}

/** Data jadwal satu matkul untuk [TodayPlan] (tanpa Room). */
data class TodayCourse(val id: Long, val name: String, val room: String?, val slot: Slot)

data class TodayItem(
    val courseId: Long,
    val name: String,
    val room: String?,
    val epochDay: Long,
    val open: LocalDateTime,
    val end: LocalDateTime,
    val state: TodayState,
)

/** Isi layar "Hari ini": matkul hari ini (urut jam) + matkul berikutnya bila hari ini kosong/selesai. */
data class TodayView(val items: List<TodayItem>, val next: TodayItem?)

/** Logika murni layar "Hari ini" (teruji di TodayPlanTest). */
object TodayPlan {
    /**
     * @param record hasil riwayat untuk (courseId, epochDay), null bila belum ada catatan.
     * @param presensiOpen apakah presensi kemunculan itu sudah terlihat dibuka (mode pintar).
     * @param graceMinutes perpanjangan jendela setelah jam tutup (mode pintar).
     */
    fun build(
        courses: List<TodayCourse>,
        now: LocalDateTime,
        record: (Long, Long) -> SummaryKind?,
        presensiOpen: (Long, Long) -> Boolean,
        graceMinutes: Int = 0,
    ): TodayView {
        val today = now.toLocalDate()
        val items = courses.filter { it.slot.dayOfWeek == today.dayOfWeek.value }
            .map { item(it, today, now, record, presensiOpen, graceMinutes) }
            .sortedBy { it.open }
        val pending = items.any { it.state in setOf(TodayState.UPCOMING, TodayState.WAITING, TodayState.OPEN) }
        val next = if (pending) {
            null
        } else {
            // Matkul berikutnya setelah hari ini (nextOccurrence sudah melewati tanggal libur).
            courses.map { c ->
                val occ = ScheduleMath.nextOccurrence(c.slot.copy(extraMinutes = graceMinutes), today.plusDays(1).atStartOfDay().minusNanos(1))
                TodayItem(c.id, c.name, c.room, occ.date.toEpochDay(), occ.open, occ.end, TodayState.UPCOMING)
            }.minByOrNull { it.open }
        }
        return TodayView(items, next)
    }

    private fun item(
        c: TodayCourse,
        date: LocalDate,
        now: LocalDateTime,
        record: (Long, Long) -> SummaryKind?,
        presensiOpen: (Long, Long) -> Boolean,
        graceMinutes: Int,
    ): TodayItem {
        val slot = c.slot.copy(extraMinutes = graceMinutes)
        val occ = ScheduleMath.occurrenceOn(slot, date)
        val day = date.toEpochDay()
        val state = when (record(c.id, day)) {
            SummaryKind.DONE -> TodayState.DONE
            SummaryKind.FAILED -> TodayState.FAILED
            SummaryKind.HOLIDAY -> TodayState.HOLIDAY
            SummaryKind.MISSED -> TodayState.MISSED
            SummaryKind.NO_SESSION -> TodayState.NO_SESSION
            SummaryKind.ACTIVE, null -> when {
                ScheduleMath.isSkipped(slot, date) -> TodayState.HOLIDAY
                now.isBefore(occ.open) -> TodayState.UPCOMING
                now.isBefore(occ.end) -> if (presensiOpen(c.id, day)) TodayState.OPEN else TodayState.WAITING
                else -> TodayState.ENDED
            }
        }
        return TodayItem(c.id, c.name, c.room, day, occ.open, occ.end, state)
    }
}
