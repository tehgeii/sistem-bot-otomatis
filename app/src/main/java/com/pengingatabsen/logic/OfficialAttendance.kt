package com.pengingatabsen.logic

import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Persentase kehadiran resmi SiAdin satu matkul pada satu waktu (dari kartu KRS / Presensi Online). */
data class OfficialSnapshot(val percent: Double, val atMillis: Long)

/** Hasil cek "presensi benar-benar tercatat di SiAdin". */
enum class VerifyOutcome {
    /** Persentase resmi naik setelah presensi. */
    RECORDED,
    /** Belum naik (bisa jadi SiAdin telat memperbarui) → dicek ulang nanti. */
    NOT_YET,
    /** Tidak bisa dibandingkan (belum ada angka sebelumnya / kartu tidak terbaca). */
    UNKNOWN,
}

/**
 * Kehadiran resmi satu matkul menurut SiAdin, dihitung dari persentase kartu.
 * [held]/[absent]/[remaining] hanya ada bila awal semester diatur (PERKIRAAN dari jadwal).
 */
data class OfficialStatus(
    val courseName: String,
    val percent: Double,
    /** Hadir resmi = persen × pertemuan ÷ 100 (28,57% × 14 = 4). */
    val present: Int,
    val meetings: Int,
    /** Minimal hadir sampai akhir semester (14 × 75% → 11). */
    val required: Int,
    /** Perkiraan pertemuan yang sudah berlangsung (null = awal semester belum diatur). */
    val held: Int?,
) {
    /** Masih harus hadir sekian kali lagi supaya mencapai minimal (pasti, dari data resmi). */
    val needMore: Int get() = (required - present).coerceAtLeast(0)
    /** Perkiraan tidak hadir resmi. */
    val absent: Int? get() = held?.let { (it - present).coerceAtLeast(0) }
    /** Perkiraan sisa jatah tidak hadir. */
    val remaining: Int? get() = absent?.let { (meetings - required) - it }
    /** Perkiraan sisa pertemuan semester ini. */
    val left: Int? get() = held?.let { (meetings - it).coerceAtLeast(0) }
    /** Masih mungkin mencapai minimal bila hadir di semua sisa pertemuan (null = tidak diketahui). */
    val reachable: Boolean? get() = left?.let { present + it >= required }
}

/** Kehadiran resmi dari SiAdin (murni & teruji di OfficialAttendanceTest). */
object OfficialAttendance {
    private val PERCENT = Regex("(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*%")

    /** "… 2026 28.57 % Belum Jadwalnya" → 28.57. Angka 0–100 terakhir yang diikuti tanda %. */
    fun parsePercent(cardText: String): Double? =
        PERCENT.findAll(cardText).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .filter { it in 0.0..100.0 }.lastOrNull()

    /** Hadir resmi: 28,57% × 14 ÷ 100 = 3,9998 → 4; 21,43% → 3. */
    fun presentCount(percent: Double, meetings: Int): Int = Math.round(percent * meetings / 100.0).toInt()

    /**
     * Persentase resmi per nama jadwal dari teks kartu-kartu satu halaman SiAdin. Lebih KETAT daripada pencocokan
     * kartu presensi: nama matkul WAJIB cocok (kode KLPK saja tidak cukup — 4502 dipakai Penambangan Data,
     * Kriptografi, dan Technopreneurship), kode hanya untuk memilih bila ada beberapa. Bila tetap beberapa kartu
     * dengan persentase berbeda, matkul itu dilewati (tidak menebak).
     */
    fun match(cardTexts: List<String>, courseNames: Collection<String>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (name in courseNames.distinct()) {
            val word = cardTexts.indices.filter { SiadinPresensiRules.wordMatches(cardTexts[it], name) }
            val both = word.filter { SiadinPresensiRules.codeMatches(cardTexts[it], name) }
            val chosen = if (both.isNotEmpty()) both else word
            val percents = chosen.mapNotNull { parsePercent(cardTexts[it]) }.distinct()
            if (percents.size == 1) out[name] = percents[0]
        }
        return out
    }

    /**
     * Perkiraan pertemuan yang sudah berlangsung sejak [start] sampai [now]: kemunculan jadwal mingguan yang jam
     * kuliahnya sudah selesai, dikurangi tanggal yang tercatat libur/tidak dibuka dosen ([excluded], per jadwal),
     * ditambah kelas pengganti yang sudah terjadi ([extraHeld], dihitung dari riwayat).
     */
    fun heldEstimate(weekly: List<Pair<Slot, Set<LocalDate>>>, start: LocalDate, now: LocalDateTime, extraHeld: Int = 0): Int {
        var count = 0
        var date = start
        val today = now.toLocalDate()
        while (!date.isAfter(today)) {
            for ((slot, excluded) in weekly) {
                if (slot.onlyDate == null && date.dayOfWeek.value == slot.dayOfWeek && date !in excluded &&
                    !ScheduleMath.occurrenceOn(slot, date).end.isAfter(now)
                ) count++
            }
            date = date.plusDays(1)
        }
        return count + extraHeld
    }

    fun evaluate(courseName: String, percent: Double, rule: AttendanceRule, held: Int?): OfficialStatus {
        val meetings = rule.meetings.coerceAtLeast(1)
        return OfficialStatus(
            courseName = courseName,
            percent = percent,
            present = presentCount(percent, meetings),
            meetings = meetings,
            required = meetings - rule.maxAbsent,
            held = held,
        )
    }

    /** Presensi tercatat bila persentase resmi naik dibanding sebelum presensi. */
    fun verify(before: Double?, after: Double?): VerifyOutcome = when {
        before == null || after == null -> VerifyOutcome.UNKNOWN
        after > before + 0.01 -> VerifyOutcome.RECORDED
        else -> VerifyOutcome.NOT_YET
    }

    /** "28.57" / "100" untuk tampilan. */
    fun pct(percent: Double): String =
        if (percent % 1.0 == 0.0) percent.toInt().toString() else String.format(java.util.Locale.US, "%.2f", percent)

    /** Baris utama di Riwayat, mis. "SiAdin: 28.57% · hadir 4/14 · butuh 7 lagi (min. 11)". */
    fun summary(s: OfficialStatus): String =
        "SiAdin: ${pct(s.percent)}% · hadir ${s.present}/${s.meetings} · " +
            if (s.needMore == 0) "minimal ${s.required} sudah tercapai ✓" else "butuh ${s.needMore} lagi (min. ${s.required})"

    /** Baris perkiraan jatah (null bila awal semester belum diatur). */
    fun estimate(s: OfficialStatus): String? {
        val held = s.held ?: return null
        val absent = s.absent ?: return null
        val remaining = s.remaining ?: return null
        val head = "Perkiraan: $held pertemuan berlangsung · tidak hadir $absent · "
        return head + when {
            s.reachable == false -> "⛔ minimal ${s.required} hadir tidak mungkin tercapai lagi"
            remaining < 0 -> "⛔ melebihi jatah (${absent}× dari maks. ${s.meetings - s.required})"
            remaining == 0 -> "⛔ jatah habis"
            remaining == 1 -> "⚠️ sisa jatah 1"
            else -> "sisa jatah $remaining"
        }
    }

    /** Peringatan (notifikasi + Telegram) bila perkiraan jatah menipis/habis; null bila aman. */
    fun warning(s: OfficialStatus): String? {
        val remaining = s.remaining ?: return null
        val msg = when {
            s.reachable == false -> "⛔ ${s.courseName}: menurut SiAdin hadir ${s.present}/${s.meetings}; minimal ${s.required} tidak mungkin tercapai lagi."
            remaining < 0 -> "⛔ ${s.courseName}: perkiraan tidak hadir ${s.absent}× — melebihi jatah ${s.meetings - s.required}×."
            remaining == 0 -> "⛔ ${s.courseName}: perkiraan jatah tidak hadir HABIS (hadir resmi ${s.present}/${s.meetings}). Sisa pertemuan wajib hadir."
            remaining == 1 -> "⚠️ ${s.courseName}: perkiraan jatah tidak hadir tinggal 1 (hadir resmi ${s.present}/${s.meetings})."
            else -> return null
        }
        return "$msg (Perkiraan dari persentase SiAdin & jadwal sejak awal semester; cek juga ke akademik.)"
    }

    /** Bagian ringkasan mingguan Telegram; null bila belum ada angka resmi. */
    fun telegramLines(statuses: List<OfficialStatus>): String? {
        if (statuses.isEmpty()) return null
        return "📋 Kehadiran resmi SiAdin:\n" + statuses.joinToString("\n") { s ->
            val need = if (s.needMore == 0) "minimal tercapai ✓" else "butuh ${s.needMore} lagi"
            val est = s.remaining?.let { r ->
                when {
                    s.reachable == false -> " — ⛔ minimal tak tercapai lagi"
                    r <= 0 -> " — ⛔ perkiraan jatah habis"
                    r == 1 -> " — ⚠️ perkiraan sisa jatah 1"
                    else -> " — perkiraan sisa jatah $r"
                }
            }.orEmpty()
            "• ${s.courseName}: ${pct(s.percent)}% (hadir ${s.present}/${s.meetings}, $need)$est"
        }
    }

    // ---------- Simpan/baca (DataStore, teks JSON) ----------

    fun encode(map: Map<String, OfficialSnapshot>): String = JSONObject().apply {
        map.toSortedMap().forEach { (name, s) -> put(name, JSONObject().put("p", s.percent).put("at", s.atMillis)) }
    }.toString()

    fun decode(text: String?): Map<String, OfficialSnapshot> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(text)
            val out = LinkedHashMap<String, OfficialSnapshot>()
            for (name in o.keys()) {
                val e = o.optJSONObject(name) ?: continue
                val p = e.optDouble("p", Double.NaN)
                if (p.isNaN() || p !in 0.0..100.0) continue
                out[name] = OfficialSnapshot(p, e.optLong("at", 0))
            }
            out
        } catch (e: JSONException) {
            emptyMap()
        }
    }
}
