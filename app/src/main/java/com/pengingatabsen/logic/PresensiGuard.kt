package com.pengingatabsen.logic

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Penjaga supaya presensi tidak terlewat (3.3.2), semuanya logika MURNI & teruji (PresensiGuardTest):
 * pesan Telegram cadangan, cek mode senyap & baterai, pengingat sebelum kuliah, dan radar presensi di luar jadwal.
 * Tidak ada yang menekan tombol presensi; semuanya hanya memberi tahu.
 */
object PresensiNudge {
    /** Pesan Telegram cadangan dikirim bila presensi sudah terlihat dibuka sekian menit tapi belum ditekan. */
    const val AFTER_MINUTES = 5L

    fun due(openedAtMillis: Long?, nowMillis: Long): Boolean =
        openedAtMillis != null && nowMillis - openedAtMillis >= AFTER_MINUTES * 60_000L

    fun text(courseName: String, openedAt: LocalTime, room: String?): String =
        "⏰ Presensi $courseName sudah dibuka sejak ${Formatters.hm(openedAt)} dan belum kamu tekan" +
            (room?.takeIf { it.isNotBlank() }?.let { " (ruang $it)" } ?: "") +
            ".\nBuka NgiBsen sekarang — tombol presensinya tetap kamu yang tekan."
}

/** Keadaan HP yang memengaruhi apakah getar presensi terasa. */
data class PhoneState(
    /** Jangan Ganggu sedang aktif (filter selain "semua"). */
    val dndActive: Boolean,
    /** Channel notifikasi presensi NgiBsen boleh menembus Jangan Ganggu. */
    val bypassDnd: Boolean,
    /** Pengguna menyatakan NgiBsen sudah diizinkan di pengaturan Jangan Ganggu (Android baru menyimpannya di Mode). */
    val dndAllowedByUser: Boolean,
    /** Mode dering "Senyap" (getar notifikasi mati). */
    val ringerSilent: Boolean,
    /** 0–100, null bila tidak terbaca. */
    val batteryPercent: Int?,
    val charging: Boolean,
)

enum class QuietIssue { DND, SILENT }

object PhoneCheck {
    const val LOW_BATTERY = 15

    fun quietIssue(s: PhoneState): QuietIssue? = when {
        s.dndActive && !s.bypassDnd && !s.dndAllowedByUser -> QuietIssue.DND
        s.ringerSilent -> QuietIssue.SILENT
        else -> null
    }

    fun quietText(issue: QuietIssue): String = when (issue) {
        QuietIssue.DND -> "Jangan Ganggu aktif — notifikasi & getar presensi NgiBsen bisa tertahan"
        QuietIssue.SILENT -> "HP mode Senyap — getar mati. Pakai mode Getar saat kuliah"
    }

    fun batteryText(s: PhoneState): String? =
        s.batteryPercent?.takeIf { it in 0..LOW_BATTERY && !s.charging }
            ?.let { "Baterai $it% — cas dulu supaya pengingat presensi tidak mati" }
}

/** Pengingat sebelum kuliah (juga saat cek mode senyap & baterai). */
object PreClass {
    val LEAD_CHOICES = listOf(10, 15, 30)
    const val DEFAULT_LEAD = 15

    /**
     * Waktu pengingat berikutnya setelah [now] = jam buka − [lead] menit, beserta id matkul yang buka pada jam itu
     * (aktif, tidak libur). Kuliah yang jam bukanya kurang dari [lead] menit lagi dilewati (tidak telat-telat).
     */
    fun next(slots: List<Pair<Long, Slot>>, now: LocalDateTime, lead: Int): Pair<LocalDateTime, List<Long>>? {
        for (d in 0L..14L) {
            val date = now.toLocalDate().plusDays(d)
            val byOpen = slots
                .filter { (_, s) -> ScheduleMath.isOn(s, date) && !ScheduleMath.isSkipped(s, date) }
                .groupBy({ (_, s) -> ScheduleMath.occurrenceOn(s, date).open }, { it.first })
                .toSortedMap()
            for ((open, ids) in byOpen) {
                val at = open.minusMinutes(lead.toLong())
                if (at.isAfter(now)) return at to ids
            }
        }
        return null
    }

    /** Id matkul yang buka tepat pada [open] (dipakai saat alarm berbunyi; urutan sesuai [slots]). */
    fun startingAt(slots: List<Pair<Long, Slot>>, open: LocalDateTime): List<Long> {
        val date = open.toLocalDate()
        return slots.filter { (_, s) ->
            ScheduleMath.isOn(s, date) && !ScheduleMath.isSkipped(s, date) && ScheduleMath.occurrenceOn(s, date).open == open
        }.map { it.first }
    }

    /** "Kriptografi 4502 mulai 09:30 (15 menit lagi)". */
    fun title(names: List<String>, open: LocalDateTime, now: LocalDateTime): String =
        "🔔 ${names.joinToString(" & ")} mulai ${Formatters.hm(open)} (${Readiness.countdown(now, open)})"
}

/** Temuan radar presensi di luar jadwal. */
enum class RadarKind {
    /** Kartu matkul jadwalmu sedang "Presensi Sekarang" padahal di luar jam jadwalnya di NgiBsen. */
    OPEN_OUTSIDE_SCHEDULE,
    /** Ada kartu presensi HARI INI untuk matkul yang di NgiBsen tidak dijadwalkan hari ini (kelas pengganti?). */
    SESSION_NOT_SCHEDULED_TODAY,
    /** Kartu matkul yang tidak ada di jadwal NgiBsen sedang "Presensi Sekarang". */
    UNKNOWN_COURSE_OPEN,
}

/** Satu nama jadwal untuk radar (gabungan semua jadwal bernama sama). */
data class RadarCourse(
    val name: String,
    /** Ada jadwal (mingguan/kelas pengganti) hari ini. */
    val scheduledToday: Boolean,
    /** Jendela pengecekan jadwal ini sedang berjalan (alur biasa yang mengurusnya). */
    val inWindowNow: Boolean,
    /** Hari ini sudah selesai (berhasil/libur/tidak dibuka/terlewat). */
    val doneToday: Boolean,
)

data class RadarFinding(val kind: RadarKind, val courseName: String?, val cardTitle: String) {
    /** Kunci "sekali per hari". */
    fun key(day: LocalDate): String = "radar|$kind|${courseName ?: cardTitle}|${day.toEpochDay()}"
}

object Radar {
    /** Mulai & akhir jam cek ringan di hari kuliah. */
    val ACTIVE_FROM: LocalTime = LocalTime.of(7, 0)
    val ACTIVE_UNTIL: LocalTime = LocalTime.of(17, 30)

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    )
    private val MONTHS_ID = listOf(
        "januari", "februari", "maret", "april", "mei", "juni", "juli", "agustus", "september", "oktober", "november", "desember",
    )
    private val DATE = Regex("(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4})")

    /** Tanggal di kartu, mis. "09 October 2026" (Inggris/Indonesia); null bila tidak ada. */
    fun cardDate(text: String): LocalDate? = DATE.findAll(text).firstNotNullOfOrNull { m ->
        val month = m.groupValues[2].lowercase()
        val idx = MONTHS.indexOf(month).takeIf { it >= 0 } ?: MONTHS_ID.indexOf(month).takeIf { it >= 0 }
            ?: return@firstNotNullOfOrNull null
        runCatching { LocalDate.of(m.groupValues[3].toInt(), idx + 1, m.groupValues[1].toInt()) }.getOrNull()
    }

    /** Judul kartu: baris pertama sebelum KDMK, mis. "KRIPTOGRAFI". */
    fun cardTitle(text: String): String =
        (text.split(Regex("\\n|(?i)kdmk")).firstOrNull { it.isNotBlank() } ?: "").trim().take(60).ifBlank { "matkul tanpa nama" }

    /**
     * Temuan dari kartu-kartu satu halaman Presensi Online. Kartu bertanggal selain [today] diabaikan; kartu tanpa
     * tanggal hanya dipakai bila sedang "Presensi Sekarang". Jadwal yang jendelanya sedang berjalan atau sudah
     * selesai hari ini dilewati (alur biasa sudah mengurusnya).
     */
    fun evaluate(rawCardTexts: List<String>, courses: List<RadarCourse>, today: LocalDate): List<RadarFinding> {
        val cards = rawCardTexts.map(SiadinPresensiRules::cleanCardText)
        val owners = HashMap<Int, MutableList<RadarCourse>>()
        for (c in courses.distinctBy { it.name }) {
            SiadinPresensiRules.pick(cards, c.name).forEach { owners.getOrPut(it) { mutableListOf() } += c }
        }
        val out = LinkedHashSet<RadarFinding>()
        cards.forEachIndexed { i, text ->
            val status = SiadinPresensiRules.cardStatus(text)
            val date = cardDate(text)
            if (date != null && date != today) return@forEachIndexed
            if (date == null && status != CardStatus.OPEN) return@forEachIndexed
            val own = owners[i].orEmpty()
            if (own.isEmpty()) {
                if (status == CardStatus.OPEN) out += RadarFinding(RadarKind.UNKNOWN_COURSE_OPEN, null, cardTitle(text))
                return@forEachIndexed
            }
            for (c in own) {
                if (c.doneToday || c.inWindowNow) continue
                when (status) {
                    CardStatus.OPEN -> out += RadarFinding(RadarKind.OPEN_OUTSIDE_SCHEDULE, c.name, cardTitle(text))
                    CardStatus.WAITING -> if (!c.scheduledToday) {
                        out += RadarFinding(RadarKind.SESSION_NOT_SCHEDULED_TODAY, c.name, cardTitle(text))
                    }
                    else -> Unit
                }
            }
        }
        return out.toList()
    }

    fun title(f: RadarFinding): String = when (f.kind) {
        RadarKind.OPEN_OUTSIDE_SCHEDULE -> "📢 Presensi DIBUKA: ${f.courseName}"
        RadarKind.UNKNOWN_COURSE_OPEN -> "📢 Presensi DIBUKA: ${f.cardTitle}"
        RadarKind.SESSION_NOT_SCHEDULED_TODAY -> "📅 Ada sesi presensi ${f.courseName} hari ini"
    }

    fun text(f: RadarFinding): String = when (f.kind) {
        RadarKind.OPEN_OUTSIDE_SCHEDULE ->
            "Dibuka di SiAdin di luar jam jadwalnya di NgiBsen (dimajukan, susulan, atau kelas pengganti). " +
                "Tap untuk membuka halaman presensi — tombolnya tetap kamu yang tekan."
        RadarKind.UNKNOWN_COURSE_OPEN ->
            "Matkul ini tidak ada di jadwal NgiBsen. Tap untuk membuka halaman presensi; tambahkan ke jadwal bila perlu."
        RadarKind.SESSION_NOT_SCHEDULED_TODAY ->
            "Di SiAdin ada kartu presensi hari ini, padahal di NgiBsen tidak dijadwalkan hari ini (belum dibuka). " +
                "Mungkin kelas pengganti — tambahkan supaya diingatkan otomatis."
    }

    /** Pesan Telegram (hanya untuk yang sedang DIBUKA). */
    fun telegram(f: RadarFinding): String? = when (f.kind) {
        RadarKind.OPEN_OUTSIDE_SCHEDULE, RadarKind.UNKNOWN_COURSE_OPEN ->
            "📢 Presensi ${f.courseName ?: f.cardTitle} sedang DIBUKA di SiAdin (di luar jadwal NgiBsen). " +
                "Buka NgiBsen/SiAdin sekarang — tombolnya tetap kamu yang tekan."
        RadarKind.SESSION_NOT_SCHEDULED_TODAY -> null
    }

    /** Cek ringan berkala hanya pada hari kuliah, jam [ACTIVE_FROM]–[ACTIVE_UNTIL]. */
    fun activeNow(slots: List<Slot>, now: LocalDateTime): Boolean {
        val t = now.toLocalTime()
        if (t.isBefore(ACTIVE_FROM) || t.isAfter(ACTIVE_UNTIL)) return false
        val today = now.toLocalDate()
        return slots.any { ScheduleMath.isOn(it, today) && !ScheduleMath.isSkipped(it, today) }
    }
}
