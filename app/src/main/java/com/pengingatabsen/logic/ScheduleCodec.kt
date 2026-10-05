package com.pengingatabsen.logic

/** Jadwal satu matkul tanpa ketergantungan Android/Room, untuk ekspor/impor teks. */
data class CourseData(
    val name: String,
    /** 1 = Senin ... 7 = Minggu. */
    val dayOfWeek: Int,
    val openMinute: Int,
    val closeMinute: Int?,
    val room: String?,
    val active: Boolean = true,
)

/**
 * Ekspor/impor jadwal sebagai teks yang mudah dibagikan lewat WhatsApp/Telegram.
 *
 * Baris pertama penanda [HEADER]; tiap matkul satu baris:
 * `hari|jamBuka|jamTutup|aktif|nama|ruang` (menit sejak 00:00; jamTutup/ruang boleh kosong).
 * `|` dan baris baru di dalam nama/ruang di-escape. Baris yang rusak diabaikan saat impor.
 */
object ScheduleCodec {
    const val HEADER = "NGIBSEN-JADWAL-1"
    private const val SEP = "|"

    fun encode(courses: List<CourseData>): String = buildString {
        append(HEADER).append('\n')
        for (c in courses) {
            append(c.dayOfWeek).append(SEP)
                .append(c.openMinute).append(SEP)
                .append(c.closeMinute?.toString() ?: "").append(SEP)
                .append(if (c.active) "1" else "0").append(SEP)
                .append(esc(c.name)).append(SEP)
                .append(esc(c.room ?: ""))
                .append('\n')
        }
    }

    /** Mengembalikan daftar matkul yang valid; baris header & baris rusak dilewati. */
    fun decode(text: String): List<CourseData> {
        val out = mutableListOf<CourseData>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line == HEADER) continue
            val f = splitEscaped(line)
            if (f.size < 6) continue
            val day = f[0].toIntOrNull()?.takeIf { it in 1..7 } ?: continue
            val open = f[1].toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: continue
            val close = f[2].takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it in 0..24 * 60 }
            val active = f[3] != "0"
            val name = f[4].trim()
            if (name.isEmpty()) continue
            val room = f[5].trim().ifBlank { null }
            out += CourseData(name, day, open, close, room, active)
        }
        return out
    }

    fun looksLikeSchedule(text: String): Boolean =
        text.lineSequence().firstOrNull()?.trim() == HEADER

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", " ").replace("\r", " ")

    private fun splitEscaped(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\\' && i + 1 < line.length -> {
                    val n = line[i + 1]
                    sb.append(if (n == 'p') '|' else n)
                    i += 2
                }
                ch == '|' -> {
                    fields += sb.toString(); sb.clear(); i++
                }
                else -> {
                    sb.append(ch); i++
                }
            }
        }
        fields += sb.toString()
        return fields
    }
}
