package com.pengingatabsen.logic

/**
 * Aturan MURNI penentuan status presensi dari isi halaman SiAdin — bisa diuji di JVM tanpa Android.
 *
 * Skrip JavaScript di `launch/SiadinScripts.kt` menyalin aturan yang sama; file ini adalah acuan
 * resmi dan diuji dengan teks nyata dari screenshot. Bila aturan berubah, ubah keduanya seiring.
 */
enum class CardStatus { WAITING, OPEN, DONE, UNKNOWN }

/** Satu kartu "Presensi Kuliah Online": nama matkul di kartu + teks tombol/statusnya. */
data class PresensiCard(val cardText: String, val buttonText: String)

object SiadinPresensiRules {
    /** Status satu kartu dari teks tombolnya (persis seperti tampilan SiAdin). */
    fun cardStatus(buttonText: String): CardStatus {
        val t = buttonText.lowercase()
        return when {
            Regex("berhasil\\s*presensi|sudah\\s*presensi").containsMatchIn(t) -> CardStatus.DONE
            Regex("presensi\\s*sekarang").containsMatchIn(t) -> CardStatus.OPEN
            t.contains("belum") -> CardStatus.WAITING
            else -> CardStatus.UNKNOWN
        }
    }

    /** Apakah [cardText] cocok dengan [courseName] (semua kata ≥3 huruf non-angka dari nama ada di kartu). */
    fun matches(cardText: String, courseName: String): Boolean {
        val tokens = norm(courseName).split(' ').filter { it.length >= 3 && !it.all(Char::isDigit) }
        if (tokens.isEmpty()) return false
        val ct = norm(cardText)
        return tokens.all { ct.contains(it) }
    }

    /**
     * Status keseluruhan untuk [courseName], meniru urutan kartu pada `probeScript` (launch/SiadinScripts.kt):
     * - belum login (`loggedIn` false) → UNKNOWN (pemanggil memperlakukannya "tunggu/coba lagi");
     * - KARTU didahulukan: ada kartu cocok → DONE > OPEN > WAITING; tidak ada kartu cocok tapi ada kartu
     *   lain → OPEN bila ada yang OPEN (cadangan), selain itu WAITING;
     * - tanpa kartu & tertulis "Belum Ada Presensi" → WAITING (di aplikasi wajib terlihat berkali-kali,
     *   karena tulisan itu bisa tampil sesaat sebelum kartu termuat);
     * - tanpa kartu dan tanpa tulisan → UNKNOWN.
     */
    fun pageStatus(
        loggedIn: Boolean,
        belumAdaPresensi: Boolean,
        cards: List<PresensiCard>,
        courseName: String,
    ): CardStatus {
        if (!loggedIn) return CardStatus.UNKNOWN
        if (cards.isEmpty()) return if (belumAdaPresensi) CardStatus.WAITING else CardStatus.UNKNOWN
        val matched = cards.filter { matches(it.cardText, courseName) }
        val pool = matched.ifEmpty { cards }
        val statuses = pool.map { cardStatus(it.buttonText) }
        return when {
            matched.isNotEmpty() && statuses.any { it == CardStatus.DONE } -> CardStatus.DONE
            statuses.any { it == CardStatus.OPEN } -> CardStatus.OPEN
            else -> CardStatus.WAITING
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
