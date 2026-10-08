package com.pengingatabsen.logic

/** Statistik kehadiran satu matkul (atau semua matkul bila [courseName] null). */
data class CourseStats(
    val courseName: String?,
    /** Sudah presensi (termasuk yang buktinya gagal terkirim — presensinya tetap sudah). */
    val present: Int,
    val missed: Int,
    val holiday: Int,
    val noSession: Int,
) {
    /** Pertemuan yang dihitung: hadir + terlewat (libur & tidak dibuka dosen tidak dihitung). */
    val counted: Int get() = present + missed

    /** Persentase hadir 0..100, null bila belum ada pertemuan yang dihitung. */
    val percent: Int? get() = if (counted == 0) null else Math.round(present * 100.0 / counted).toInt()
}

/** Statistik kehadiran dari riwayat (murni, teruji di AttendanceStatsTest). */
object AttendanceStats {
    /** Per matkul (urut nama) dari pasangan (nama matkul, jenis hasil). Kemunculan yang masih berjalan diabaikan. */
    fun perCourse(items: List<Pair<String, SummaryKind>>): List<CourseStats> =
        items.groupBy({ it.first }, { it.second })
            .map { (name, kinds) -> stats(name, kinds) }
            .sortedBy { it.courseName?.lowercase() }

    fun overall(items: List<Pair<String, SummaryKind>>): CourseStats = stats(null, items.map { it.second })

    private fun stats(name: String?, kinds: List<SummaryKind>) = CourseStats(
        courseName = name,
        present = kinds.count { it == SummaryKind.DONE || it == SummaryKind.FAILED },
        missed = kinds.count { it == SummaryKind.MISSED },
        holiday = kinds.count { it == SummaryKind.HOLIDAY },
        noSession = kinds.count { it == SummaryKind.NO_SESSION },
    )
}
