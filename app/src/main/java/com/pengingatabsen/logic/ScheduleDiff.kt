package com.pengingatabsen.logic

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Hari & jam satu jadwal (untuk perbandingan KRS ↔ NgiBsen). */
data class SlotTime(val day: Int, val open: Int, val close: Int?) {
    /** "Jumat 09:30–12:15". */
    fun label(): String = "${Formatters.dayName(day)} ${Formatters.window(open, close)}"
}

/**
 * Perbedaan jadwal satu matkul antara KRS SiAdin ([after]) dan NgiBsen ([before]).
 * [before] kosong = matkul baru di KRS (belum ada di NgiBsen). [appCourseIds] = jadwal NgiBsen yang cocok.
 */
data class ScheduleChange(
    val name: String,
    val before: List<SlotTime>,
    val after: List<SlotTime>,
    val appCourseIds: List<Long>,
    val room: String? = null,
) {
    val isNew: Boolean get() = before.isEmpty()

    /** Bisa diterapkan otomatis: matkul baru, atau satu jadwal ↔ satu jadwal (hari/jam berpindah). */
    val applicable: Boolean get() = isNew || (appCourseIds.size == 1 && before.size == 1 && after.size == 1)

    fun describe(): String = if (isNew) {
        "Baru di KRS: $name (${after.joinToString { it.label() }})"
    } else {
        "$name: ${before.joinToString { it.label() }} → ${after.joinToString { it.label() }}" +
            if (applicable) "" else " (ubah manual)"
    }
}

/**
 * Bandingkan jadwal KRS SiAdin dengan jadwal mingguan di NgiBsen (murni, teruji di ScheduleDiffTest).
 * Yang dibandingkan hanya HARI + JAM BUKA (jam tutup & ruang sering diubah sendiri, mis. diperpanjang untuk mode
 * pintar). Jadwal NgiBsen yang tidak ada di KRS tidak dilaporkan (bisa jadi sengaja ditambah sendiri).
 */
object ScheduleDiff {
    /** Matkul KRS [krsName] (selalu berkode, mis. "Technopreneurship 4502") sama dengan jadwal NgiBsen [appName]? */
    fun sameCourse(krsName: String, appName: String): Boolean {
        if (norm(krsName) == norm(appName)) return true
        val coded = SiadinPresensiRules.codes(appName).isNotEmpty()
        val words = SiadinPresensiRules.wordMatches(krsName, appName)
        return if (coded) words && SiadinPresensiRules.codeMatches(krsName, appName) else words
    }

    fun compare(krs: List<CourseData>, app: List<Pair<Long, CourseData>>): List<ScheduleChange> {
        val groups = krs.groupBy { it.name }
        // Jadwal NgiBsen yang cocok dengan lebih dari satu matkul KRS diabaikan (tidak menebak).
        val matches = app.associate { (id, c) -> id to groups.keys.filter { sameCourse(it, c.name) } }
        val out = mutableListOf<ScheduleChange>()
        for ((name, rows) in groups) {
            val after = rows.map { SlotTime(it.dayOfWeek, it.openMinute, it.closeMinute) }.distinct().sortedWith(ORDER)
            val mine = app.filter { (id, _) -> matches[id] == listOf(name) }
            if (mine.isEmpty()) {
                // Sudah ada tapi namanya cocok dengan >1 matkul KRS → jangan dianggap baru.
                if (app.any { (id, _) -> name in matches[id].orEmpty() }) continue
                out += ScheduleChange(name, emptyList(), after, emptyList(), rows.firstNotNullOfOrNull { it.room })
                continue
            }
            val before = mine.map { (_, c) -> SlotTime(c.dayOfWeek, c.openMinute, c.closeMinute) }.distinct().sortedWith(ORDER)
            if (before.map { it.day to it.open }.toSet() != after.map { it.day to it.open }.toSet()) {
                out += ScheduleChange(mine.first().second.name, before, after, mine.map { it.first })
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Sidik perubahan (supaya perubahan yang sama tidak diberitahukan berulang). */
    fun signature(changes: List<ScheduleChange>): String =
        changes.joinToString("|") { c -> c.name + ":" + c.before.joinToString(",") { "${it.day}-${it.open}" } + ">" + c.after.joinToString(",") { "${it.day}-${it.open}" } }

    fun encode(changes: List<ScheduleChange>): String = JSONArray().apply {
        changes.forEach { c ->
            put(
                JSONObject()
                    .put("name", c.name)
                    .put("before", slots(c.before))
                    .put("after", slots(c.after))
                    .put("ids", JSONArray().apply { c.appCourseIds.forEach { put(it) } })
                    .put("room", c.room ?: JSONObject.NULL),
            )
        }
    }.toString()

    fun decode(text: String?): List<ScheduleChange> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ids = o.optJSONArray("ids") ?: JSONArray()
                ScheduleChange(
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    before = slots(o.optJSONArray("before")),
                    after = slots(o.optJSONArray("after")).takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                    appCourseIds = (0 until ids.length()).map { ids.getLong(it) },
                    room = if (o.isNull("room")) null else o.optString("room").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private val ORDER = compareBy<SlotTime>({ it.day }, { it.open })

    private fun slots(list: List<SlotTime>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("d", it.day).put("o", it.open).put("c", it.close ?: JSONObject.NULL)) }
    }

    private fun slots(arr: JSONArray?): List<SlotTime> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        val d = o.optInt("d", 0).takeIf { it in 1..7 } ?: return@mapNotNull null
        val open = o.optInt("o", -1).takeIf { it in 0 until 24 * 60 } ?: return@mapNotNull null
        val close = if (o.isNull("c")) null else o.optInt("c", -1).takeIf { it in 0 until 24 * 60 }
        SlotTime(d, open, close)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
