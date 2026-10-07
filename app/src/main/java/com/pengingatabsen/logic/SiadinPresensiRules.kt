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

    /** Kode kelas di nama jadwal: deret ≥4 angka, mis. "MPTI 4515" → ["4515"] (= KLPK A11.4515 di kartu). */
    fun codes(courseName: String): List<String> = Regex("\\d{4,}").findAll(courseName).map { it.value }.toList()

    /**
     * Apakah [cardText] (teks satu kartu) cocok dengan [courseName] di jadwal:
     * 1. kode kelas di nama jadwal muncul sebagai angka utuh di kartu (KLPK A11.4515 ↔ "MPTI 4515"); atau
     * 2. setiap kata (≥2 huruf, bukan angka) ada di kartu — kata 2 huruf harus kata utuh (mis. "II") —
     *    atau merupakan bagian singkatan nama matkul di kartu ("MPTI", "TI" ↔ Manajemen Proyek Teknologi Informasi).
     */
    fun matches(cardText: String, courseName: String): Boolean {
        if (codes(courseName).any { Regex("(^|[^0-9])$it([^0-9]|$)").containsMatchIn(cardText) }) return true
        val tokens = norm(courseName).split(' ').filter { it.length >= 2 && !it.all(Char::isDigit) }
        if (tokens.isEmpty()) return false
        val ct = norm(cardText)
        val ini = initials(cardText.split(Regex("(?i)kdmk"))[0])
        return tokens.all { k ->
            val inText = if (k.length >= 3) ct.contains(k) else " $ct ".contains(" $k ")
            inText || (k.all { it in 'a'..'z' } && ini.contains(k))
        }
    }

    /** Huruf depan tiap kata nama matkul (tanpa kata sambung), mis. "Manajemen Proyek Teknologi Informasi" → "mpti". */
    fun initials(title: String): String =
        norm(title).split(' ')
            .filter { it.isNotEmpty() && it[0] in 'a'..'z' && it !in STOP_WORDS }
            .joinToString("") { it.take(1) }

    private val STOP_WORDS = setOf("dan", "di", "ke", "of", "and")

    /**
     * Status keseluruhan untuk [courseName], meniru urutan kartu pada `probeScript` (launch/SiadinScripts.kt):
     * - belum login (`loggedIn` false) → UNKNOWN (pemanggil memperlakukannya "tunggu/coba lagi");
     * - KARTU didahulukan: ada kartu cocok → DONE > OPEN > WAITING; tidak ada kartu cocok → WAITING, kecuali
     *   nama jadwal TANPA kode kelas: kartu lain dipakai sebagai cadangan "dibuka" (nama mungkin berbeda).
     *   Nama berkode tidak pernah memakai kartu matkul lain (presensi matkul lain bukan presensi matkul ini);
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
        val pool = when {
            matched.isNotEmpty() -> matched
            codes(courseName).isEmpty() -> cards
            else -> emptyList()
        }
        val statuses = pool.map { cardStatus(it.buttonText) }
        return when {
            matched.isNotEmpty() && statuses.any { it == CardStatus.DONE } -> CardStatus.DONE
            statuses.any { it == CardStatus.OPEN } -> CardStatus.OPEN
            else -> CardStatus.WAITING
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
