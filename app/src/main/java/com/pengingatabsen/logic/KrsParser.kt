package com.pengingatabsen.logic

/**
 * Mengubah teks kartu KRS SiAdin (halaman Akademik → KRS) menjadi jadwal NgiBsen. Murni & teruji (KrsParserTest).
 *
 * Bentuk kartu asli (8 Okt 2026):
 * ```
 * SISTEM TERDISTRIBUSI            3 SKS
 * KDMK: A11.64501 —— KLPK: A11.4512
 * • KAMIS   12.30-15.00   Kulino
 * • -
 * • -
 * 28.57 %
 * ```
 * Nama jadwal = nama matkul (huruf awal kapital) + kode kelas dari KLPK ("Sistem Terdistribusi 4512"), sama
 * dengan cara pencocokan kartu presensi. Satu baris hari = satu jadwal; baris "-" diabaikan.
 */
object KrsParser {
    private val DAYS = mapOf(
        "SENIN" to 1, "SELASA" to 2, "RABU" to 3, "KAMIS" to 4, "JUMAT" to 5, "JUM'AT" to 5, "SABTU" to 6, "MINGGU" to 7,
    )
    private val ROW = Regex(
        "(SENIN|SELASA|RABU|KAMIS|JUM'?AT|SABTU|MINGGU)\\s*\\|?\\s*(\\d{1,2})[.:](\\d{2})\\s*[-–]\\s*(\\d{1,2})[.:](\\d{2})\\s*\\|?\\s*([^|]*)",
        RegexOption.IGNORE_CASE,
    )
    private val KLPK = Regex("KLPK\\s*:?\\s*\\|?\\s*([A-Z0-9]+(?:\\.[A-Z0-9]+)*)", RegexOption.IGNORE_CASE)

    /** Semua jadwal dari teks kartu-kartu KRS (duplikat dibuang). */
    /** Teks kartu dibersihkan dulu dari navbar/menu/footer yang ikut terbaca ([SiadinPresensiRules.cleanCardText]). */
    fun parse(cardTexts: List<String>): List<CourseData> =
        cardTexts.map(SiadinPresensiRules::cleanCardText).flatMap(::parseCard).distinct()

    fun parseCard(text: String): List<CourseData> {
        val joined = text.replace("•", " ").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
        val title = joined.split(Regex("kdmk", RegexOption.IGNORE_CASE))[0]
            .replace(Regex("\\d+\\s*sks", RegexOption.IGNORE_CASE), " ")
            .replace("|", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.isEmpty()) return emptyList()
        val code = KLPK.find(joined)?.groupValues?.get(1)?.substringAfterLast('.')
            ?.takeIf { it.length >= 3 && it.all(Char::isDigit) }
        val name = titleCase(title) + (code?.let { " $it" } ?: "")
        return ROW.findAll(joined).mapNotNull { m ->
            val day = DAYS[m.groupValues[1].uppercase()] ?: return@mapNotNull null
            val open = minutes(m.groupValues[2], m.groupValues[3]) ?: return@mapNotNull null
            val close = minutes(m.groupValues[4], m.groupValues[5])?.takeIf { it > open }
            CourseData(name = name, dayOfWeek = day, openMinute = open, closeMinute = close, room = room(m.groupValues[6]))
        }.toList()
    }

    private fun minutes(h: String, m: String): Int? {
        val hour = h.toIntOrNull() ?: return null
        val minute = m.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Ruang setelah jam ("Kulino", "D.2.A"); "-", persen, atau kosong → tanpa ruang. */
    private fun room(raw: String): String? {
        val r = raw.trim().trim('-', '—', '–').trim()
        if (r.isEmpty() || Regex("^[\\d.,]+\\s*%$").matches(r)) return null
        if (DAYS.containsKey(r.uppercase())) return null
        return r
    }

    /** "MANAJEMEN PROYEK TEKNOLOGI INFORMASI" → "Manajemen Proyek Teknologi Informasi"; angka Romawi tetap ("II"). */
    fun titleCase(s: String): String = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
        when {
            w.matches(Regex("^[IVX]+$")) -> w
            w.any(Char::isDigit) -> w
            else -> w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Apakah jadwal [new] sudah ada di [existing] (nama jadwal, hari, jam buka): hari & jam buka sama DAN
     * (kode kelas sama, atau nama sama tanpa memperhatikan huruf besar/kecil). Dipakai supaya impor berulang /
     * nama singkatan buatan sendiri ("MPTI 4515") tidak menggandakan jadwal.
     */
    fun alreadyExists(new: CourseData, existing: List<CourseData>): Boolean {
        val newCodes = SiadinPresensiRules.codes(new.name).toSet()
        return existing.any { e ->
            e.dayOfWeek == new.dayOfWeek && e.openMinute == new.openMinute &&
                (e.name.trim().equals(new.name.trim(), ignoreCase = true) ||
                    SiadinPresensiRules.codes(e.name).any { it in newCodes })
        }
    }
}
