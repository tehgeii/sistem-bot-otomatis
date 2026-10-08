package com.pengingatabsen.logic

import java.time.LocalDate
import java.time.LocalDateTime

/** Satu kemunculan yang alarm jam bukanya sudah dipasang (lihat [Readiness.missedAlarms]). */
data class ArmedOccurrence(val courseId: Long, val epochDay: Long)

/**
 * Logika murni untuk "Cek kesiapan otomatis" dan "Peringatan alarm terlewat" (teruji di ReadinessTest).
 */
object Readiness {
    /** Cek kesiapan dijalankan sekian menit sebelum matkul pertama tiap hari. */
    const val LEAD_MINUTES = 30L
    /** Alarm jam buka dianggap terlewat bila sudah lewat sekian menit tanpa jejak sama sekali. */
    const val MISSED_GRACE_MINUTES = 3L
    /** Hanya kemunculan sekian hari terakhir yang diperiksa. */
    const val MISSED_LOOKBACK_DAYS = 1L

    /**
     * Waktu cek kesiapan berikutnya setelah [now]: [lead] menit sebelum kemunculan PERTAMA tiap hari
     * (matkul aktif, tidak libur). Null bila tidak ada jadwal dalam 2 minggu ke depan.
     */
    fun nextPreflight(slots: List<Slot>, now: LocalDateTime, lead: Long = LEAD_MINUTES): LocalDateTime? {
        for (d in 0L..14L) {
            val date = now.toLocalDate().plusDays(d)
            val first = firstOpenOn(slots, date) ?: continue
            val at = first.minusMinutes(lead)
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** Jam buka kemunculan pertama pada [date] (null bila tidak ada kuliah hari itu). */
    fun firstOpenOn(slots: List<Slot>, date: LocalDate): LocalDateTime? =
        slots.filter { it.dayOfWeek == date.dayOfWeek.value && !ScheduleMath.isSkipped(it, date) }
            .minOfOrNull { ScheduleMath.occurrenceOn(it, date).open }

    /**
     * Kemunculan yang alarm jam bukanya SUDAH DIPASANG ([armed]) tapi tidak pernah berbunyi: jam buka sudah
     * lewat ≥ [MISSED_GRACE_MINUTES] menit dan tidak ada catatan riwayat sama sekali ([hasRecord] false).
     * Matkul yang dihapus/nonaktif ([slots] tidak memuatnya) atau sedang libur diabaikan.
     * Hanya alarm yang memang pernah dipasang yang dinilai, jadi matkul yang baru ditambah setelah jamnya
     * lewat tidak dianggap terlewat.
     */
    fun missedAlarms(
        armed: Collection<ArmedOccurrence>,
        slots: Map<Long, Slot>,
        hasRecord: (ArmedOccurrence) -> Boolean,
        now: LocalDateTime,
    ): List<Pair<ArmedOccurrence, Occurrence>> {
        val oldest = now.toLocalDate().minusDays(MISSED_LOOKBACK_DAYS).toEpochDay()
        return armed.filter { it.epochDay >= oldest }.mapNotNull { a ->
            val slot = slots[a.courseId] ?: return@mapNotNull null
            val date = LocalDate.ofEpochDay(a.epochDay)
            if (date.dayOfWeek.value != slot.dayOfWeek || ScheduleMath.isSkipped(slot, date)) return@mapNotNull null
            val occ = ScheduleMath.occurrenceOn(slot, date)
            val late = !occ.open.plusMinutes(MISSED_GRACE_MINUTES).isAfter(now)
            if (late && !hasRecord(a)) a to occ else null
        }.sortedBy { it.second.open }
    }

    /** "2 jam 5 menit lagi" / "12 menit lagi" / "sebentar lagi". */
    fun countdown(now: LocalDateTime, at: LocalDateTime): String {
        val minutes = java.time.Duration.between(now, at).toMinutes()
        return when {
            minutes <= 0 -> "sebentar lagi"
            minutes < 60 -> "$minutes menit lagi"
            minutes % 60 == 0L -> "${minutes / 60} jam lagi"
            else -> "${minutes / 60} jam ${minutes % 60} menit lagi"
        }
    }
}
