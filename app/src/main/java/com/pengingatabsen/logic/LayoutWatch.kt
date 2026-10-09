package com.pengingatabsen.logic

/** Satu pengecekan yang halaman SiAdin-nya termuat tapi isinya tidak dikenali. */
data class SuspectCheck(val courseId: Long, val epochDay: Long)

/**
 * Peringatan dini "tampilan SiAdin berubah" (murni & teruji di LayoutWatchTest).
 *
 * Satu matkul yang gagal dibaca beberapa kali bisa saja gangguan sesaat SiAdin. Peringatan baru muncul bila
 * pengecekan yang mencurigakan terjadi BERTURUT-TURUT (tanpa satu pun yang terbaca normal di antaranya)
 * minimal [MIN_CHECKS] kali DAN mencakup minimal 2 matkul atau 2 hari berbeda.
 */
object LayoutWatch {
    const val MIN_CHECKS = 4
    private const val KEEP = 20

    fun decode(text: String?): List<SuspectCheck> =
        text.orEmpty().split(',').mapNotNull { part ->
            val (c, d) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            SuspectCheck(c.toLongOrNull() ?: return@mapNotNull null, d.toLongOrNull() ?: return@mapNotNull null)
        }

    fun encode(list: List<SuspectCheck>): String = list.joinToString(",") { "${it.courseId}:${it.epochDay}" }

    /** Tambah satu pengecekan mencurigakan (hanya [KEEP] terakhir yang disimpan). */
    fun add(list: List<SuspectCheck>, check: SuspectCheck): List<SuspectCheck> = (list + check).takeLast(KEEP)

    fun shouldWarn(list: List<SuspectCheck>): Boolean =
        list.size >= MIN_CHECKS &&
            (list.map { it.courseId }.toSet().size >= 2 || list.map { it.epochDay }.toSet().size >= 2)

    /** Teks peringatan (notifikasi & Telegram). */
    fun message(list: List<SuspectCheck>, names: (Long) -> String?): String {
        val courses = list.map { it.courseId }.distinct().mapNotNull(names).distinct()
        val which = if (courses.isEmpty()) "" else " (${courses.joinToString(", ")})"
        return "NgiBsen tidak bisa membaca halaman Presensi Online SiAdin di ${list.size} pengecekan terakhir$which, " +
            "padahal halamannya termuat. Kemungkinan tampilan SiAdin berubah. Sementara ini cek SiAdin sendiri saat " +
            "kuliah, lalu kirim log diagnosis (Pengaturan → Lanjutan → Diagnosis) supaya pengecek bisa diperbaiki."
    }
}
