package com.pengingatabsen.logic

import java.time.LocalDate

/** Aturan kehadiran minimal (Pengaturan → Kehadiran). */
data class AttendanceRule(val meetings: Int = DEFAULT_MEETINGS, val minPercent: Int = DEFAULT_MIN_PERCENT) {
    /**
     * Paling banyak boleh tidak hadir: pertemuan − pertemuan wajib (dibulatkan ke atas).
     * 14 pertemuan × 75% = 10,5 → wajib 11 → boleh tidak hadir 3.
     */
    val maxAbsent: Int
        get() {
            val m = meetings.coerceAtLeast(1)
            val required = (m * minPercent.coerceIn(0, 100) + 99) / 100
            return (m - required).coerceAtLeast(0)
        }

    companion object {
        /** UDINUS: 14 pertemuan kuliah per semester (KRS menampilkan 4/14 = 28,57 %). */
        const val DEFAULT_MEETINGS = 14
        const val DEFAULT_MIN_PERCENT = 75
    }
}

enum class AllowanceLevel { SAFE, LAST, LIMIT, OVER }

/** Jatah tidak hadir satu matkul (dihitung dari catatan NgiBsen). */
data class Allowance(val courseName: String, val missed: Int, val maxAbsent: Int) {
    val remaining: Int get() = maxAbsent - missed
    val level: AllowanceLevel
        get() = when {
            remaining >= 2 -> AllowanceLevel.SAFE
            remaining == 1 -> AllowanceLevel.LAST
            remaining == 0 -> AllowanceLevel.LIMIT
            else -> AllowanceLevel.OVER
        }
}

/** Sisa jatah tidak hadir (murni, teruji di AllowanceTest). */
object Allowances {
    /** Hanya "terlewat" yang mengurangi jatah; libur & "tidak dibuka dosen" tidak. */
    fun forCourse(courseName: String, kinds: List<SummaryKind>, rule: AttendanceRule): Allowance =
        Allowance(courseName, kinds.count { it == SummaryKind.MISSED }, rule.maxAbsent)

    fun perCourse(stats: List<CourseStats>, rule: AttendanceRule): List<Allowance> =
        stats.mapNotNull { s -> s.courseName?.let { Allowance(it, s.missed, rule.maxAbsent) } }

    /** Riwayat yang dihitung: sejak awal semester (bila diatur). */
    fun inSemester(date: LocalDate, semesterStart: LocalDate?): Boolean = semesterStart == null || !date.isBefore(semesterStart)

    fun label(a: Allowance): String = when (a.level) {
        AllowanceLevel.SAFE -> "Jatah tidak hadir: sisa ${a.remaining} dari ${a.maxAbsent}"
        AllowanceLevel.LAST -> "⚠️ Jatah tidak hadir tinggal 1 (dari ${a.maxAbsent})"
        AllowanceLevel.LIMIT -> "⛔ Jatah tidak hadir habis — sisa pertemuan wajib hadir"
        AllowanceLevel.OVER -> "⛔ Melebihi batas: ${a.missed}× tidak hadir (maks. ${a.maxAbsent})"
    }

    /** Peringatan setelah satu presensi terlewat; null bila jatah masih aman (sisa ≥ 2). */
    fun missedWarning(a: Allowance): String? {
        val text = when (a.level) {
            AllowanceLevel.SAFE -> return null
            AllowanceLevel.LAST -> "⚠️ ${a.courseName}: jatah tidak hadir tinggal 1 dari ${a.maxAbsent}. Jangan sampai terlewat lagi."
            AllowanceLevel.LIMIT -> "⛔ ${a.courseName}: jatah tidak hadir HABIS (${a.missed} dari ${a.maxAbsent}). Sisa pertemuan wajib hadir."
            AllowanceLevel.OVER -> "⛔ ${a.courseName}: sudah ${a.missed}× tidak hadir, melebihi batas ${a.maxAbsent}. Segera hubungi dosen/akademik."
        }
        return "$text (Dihitung dari catatan NgiBsen; cek juga angka resmi di SiAdin.)"
    }
}

/** Satu minggu (Senin–Minggu) untuk grafik kehadiran. */
data class WeekBucket(val weekStart: LocalDate, val present: Int, val missed: Int)

/** Grafik kehadiran per minggu (murni, teruji). */
object WeeklyChart {
    /** [weeks] minggu terakhir s/d minggu [today] (urut lama → baru), termasuk minggu tanpa kuliah (0). */
    fun buckets(items: List<SummaryItem>, today: LocalDate, weeks: Int = 8): List<WeekBucket> {
        val thisWeek = WeeklySummary.weekStart(today)
        val starts = (weeks - 1 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
        val byWeek = items.groupBy { WeeklySummary.weekStart(it.date) }
        return starts.map { start ->
            val kinds = byWeek[start].orEmpty().map { it.kind }
            WeekBucket(
                weekStart = start,
                present = kinds.count { it == SummaryKind.DONE || it == SummaryKind.FAILED },
                missed = kinds.count { it == SummaryKind.MISSED },
            )
        }
    }
}
