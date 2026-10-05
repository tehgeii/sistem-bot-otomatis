package com.pengingatabsen.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** Hasil satu kemunculan matkul, untuk ringkasan mingguan (tanpa ketergantungan Room). */
enum class SummaryKind { DONE, FAILED, MISSED, NO_SESSION, HOLIDAY, ACTIVE }

data class SummaryItem(val courseName: String, val date: LocalDate, val kind: SummaryKind)

/** Ringkasan mingguan ke Telegram: dikirim Minggu malam untuk minggu Senin–Minggu itu. */
object WeeklySummary {
    /** Jam pengiriman pada hari Minggu. */
    const val SEND_HOUR = 19

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Minggu (Senin-nya) yang ringkasannya sudah waktunya dikirim pada [now]: minggu dari hari Minggu
     * jam [SEND_HOUR] terakhir yang sudah lewat. Pengiriman yang telat (mis. Senin pagi karena HP mati)
     * tetap merangkum minggu sebelumnya, bukan minggu baru.
     */
    fun dueWeekStart(now: LocalDateTime): LocalDate {
        var sunday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        if (now.isBefore(sunday.atTime(SEND_HOUR, 0))) sunday = sunday.minusWeeks(1)
        return sunday.minusDays(6)
    }

    /** Waktu kirim terjadwal berikutnya (Minggu jam [SEND_HOUR]) setelah [now]. */
    fun nextSendTime(now: LocalDateTime): LocalDateTime {
        val sunday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(SEND_HOUR, 0)
        return if (sunday.isAfter(now)) sunday else sunday.plusWeeks(1)
    }

    /** Teks ringkasan; null bila minggu itu tidak ada jadwal sama sekali (tidak perlu dikirim). */
    fun build(weekStart: LocalDate, items: List<SummaryItem>): String? {
        val week = items.filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekStart.plusDays(6)) }
        if (week.isEmpty()) return null
        fun count(kind: SummaryKind) = week.count { it.kind == kind }

        return buildString {
            append("📊 Ringkasan NgiBsen ").append(range(weekStart)).append('\n')
            append("✅ Berhasil presensi: ").append(count(SummaryKind.DONE)).append('\n')
            append("❌ Terlewat: ").append(count(SummaryKind.MISSED)).append('\n')
            count(SummaryKind.FAILED).takeIf { it > 0 }?.let { append("⚠️ Bukti gagal terkirim: ").append(it).append('\n') }
            append("⏸ Tidak dibuka dosen: ").append(count(SummaryKind.NO_SESSION)).append('\n')
            append("🏖 Libur: ").append(count(SummaryKind.HOLIDAY))
            val missed = week.filter { it.kind == SummaryKind.MISSED }.sortedBy { it.date }
            if (missed.isNotEmpty()) {
                append("\n\nTerlewat:")
                missed.forEach { append("\n• ").append(it.courseName).append(" (").append(Formatters.date(it.date)).append(')') }
            }
        }
    }

    /** "29 Sep – 5 Okt 2026" */
    private fun range(weekStart: LocalDate): String {
        val end = weekStart.plusDays(6)
        return "${weekStart.dayOfMonth} ${MONTHS[weekStart.monthValue - 1]} – ${end.dayOfMonth} ${MONTHS[end.monthValue - 1]} ${end.year}"
    }

    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
}
