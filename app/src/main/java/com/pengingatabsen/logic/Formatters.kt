package com.pengingatabsen.logic

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Format teks berbahasa Indonesia tanpa bergantung pada locale perangkat. */
object Formatters {
    private val DAYS = arrayOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
    private val MONTHS = arrayOf(
        "Januari", "Februari", "Maret", "April", "Mei", "Juni",
        "Juli", "Agustus", "September", "Oktober", "November", "Desember",
    )

    /** [dayOfWeek]: 1 = Senin ... 7 = Minggu (ISO-8601). */
    fun dayName(dayOfWeek: Int): String = DAYS[(dayOfWeek - 1).coerceIn(0, 6)]

    fun hm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun hm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    fun hm(time: LocalDateTime): String = hm(time.toLocalTime())

    fun hms(time: LocalDateTime): String =
        "%02d:%02d:%02d".format(time.hour, time.minute, time.second)

    /** "Senin, 6 Oktober 2026" */
    fun date(date: LocalDate): String =
        "${dayName(date.dayOfWeek.value)}, ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]} ${date.year}"

    /** "Senin, 6 Oktober 2026 07:31:05" */
    fun dateTime(time: LocalDateTime): String = "${date(time.toLocalDate())} ${hms(time)}"

    /** "07:00–08:40" atau "07:00" bila jam tutup kosong. */
    fun window(openMinute: Int, closeMinute: Int?): String =
        if (closeMinute != null) "${hm(openMinute)}–${hm(closeMinute)}" else hm(openMinute)

    /** Pesan bukti absen. Waktu = saat tombol ditekan / screenshot dibagikan. */
    fun proofMessage(courseName: String, pressedAt: LocalDateTime): String =
        "✅ Absen $courseName — ${dateTime(pressedAt)}"

    fun missedMessage(courseName: String, open: LocalDateTime, end: LocalDateTime): String =
        "❌ Terlewat absen $courseName — ${date(open.toLocalDate())} (dibuka ${hm(open)}–${hm(end)})"
}
