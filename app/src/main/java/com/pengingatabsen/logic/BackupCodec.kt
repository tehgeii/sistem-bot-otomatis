package com.pengingatabsen.logic

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Satu jadwal di file cadangan (sama dengan kolom tabel `courses`). */
data class BackupCourse(
    val id: Long,
    val name: String,
    val dayOfWeek: Int,
    val openMinute: Int,
    val closeMinute: Int?,
    val room: String?,
    val active: Boolean,
    val skipUntilEpochDay: Long?,
    val oneOffEpochDay: Long?,
)

/** Satu baris riwayat di file cadangan. Foto bukti tidak ikut (hanya ada di HP lama). */
data class BackupRecord(
    val courseId: Long,
    val courseName: String,
    val epochDay: Long,
    val openAtMillis: Long,
    val endAtMillis: Long,
    /** Nama status riwayat (ACTIVE, SENT, MISSED, ...). */
    val status: String,
    val doneAtMillis: Long?,
    val error: String?,
)

/**
 * Isi file cadangan NgiBsen. [settings] hanya pengaturan yang AMAN dipindah (boolean/angka/teks biasa);
 * NIM, password, dan bot token TIDAK PERNAH ikut — terenkripsi dengan kunci yang terkunci di HP lama.
 */
data class Backup(
    val createdAt: String,
    val appVersion: String,
    val courses: List<BackupCourse>,
    val records: List<BackupRecord>,
    val settings: Map<String, Any>,
)

/** File cadangan (JSON) untuk pindah HP / instal ulang. Murni & teruji (BackupCodecTest). */
object BackupCodec {
    const val FORMAT = "NGIBSEN-CADANGAN"
    const val VERSION = 1

    fun fileName(date: java.time.LocalDate): String = "NgiBsen-cadangan-$date.json"

    fun encode(backup: Backup): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAt", backup.createdAt)
        root.put("appVersion", backup.appVersion)
        root.put("courses", JSONArray().apply {
            backup.courses.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("dayOfWeek", c.dayOfWeek)
                    put("openMinute", c.openMinute)
                    putOpt("closeMinute", c.closeMinute)
                    putOpt("room", c.room)
                    put("active", c.active)
                    putOpt("skipUntilEpochDay", c.skipUntilEpochDay)
                    putOpt("oneOffEpochDay", c.oneOffEpochDay)
                })
            }
        })
        root.put("records", JSONArray().apply {
            backup.records.forEach { r ->
                put(JSONObject().apply {
                    put("courseId", r.courseId)
                    put("courseName", r.courseName)
                    put("epochDay", r.epochDay)
                    put("openAtMillis", r.openAtMillis)
                    put("endAtMillis", r.endAtMillis)
                    put("status", r.status)
                    putOpt("doneAtMillis", r.doneAtMillis)
                    putOpt("error", r.error)
                })
            }
        })
        root.put("settings", JSONObject().apply {
            backup.settings.toSortedMap().forEach { (k, v) ->
                when (v) {
                    is Boolean, is Int, is Long, is String -> put(k, v)
                    else -> Unit
                }
            }
        })
        return root.toString(2)
    }

    /**
     * Baca file cadangan. Entri yang rusak dilewati; file yang bukan cadangan NgiBsen, rusak total, atau dari
     * versi format yang lebih baru ditolak dengan [IllegalArgumentException] berpesan bahasa Indonesia.
     */
    fun decode(text: String): Backup {
        val root = try {
            JSONObject(text.trim().removePrefix("﻿"))
        } catch (e: JSONException) {
            throw IllegalArgumentException("File ini bukan cadangan NgiBsen (bukan JSON yang valid).")
        }
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("File ini bukan cadangan NgiBsen.")
        }
        val version = root.optInt("version", 0)
        if (version < 1) throw IllegalArgumentException("Versi file cadangan tidak dikenal.")
        if (version > VERSION) {
            throw IllegalArgumentException("Cadangan ini dibuat NgiBsen versi lebih baru. Perbarui aplikasi dulu.")
        }

        val courses = mutableListOf<BackupCourse>()
        val seenIds = HashSet<Long>()
        root.optJSONArray("courses")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val day = o.optInt("dayOfWeek", 0)
                val open = o.optInt("openMinute", -1)
                if (name.isEmpty() || day !in 1..7 || open !in 0 until 24 * 60) continue
                val id = o.optLong("id", 0)
                if (id > 0 && !seenIds.add(id)) continue
                courses += BackupCourse(
                    id = id,
                    name = name,
                    dayOfWeek = day,
                    openMinute = open,
                    closeMinute = o.optIntOrNull("closeMinute")?.takeIf { it in 0..24 * 60 },
                    room = o.optStringOrNull("room"),
                    active = o.optBoolean("active", true),
                    skipUntilEpochDay = o.optLongOrNull("skipUntilEpochDay"),
                    oneOffEpochDay = o.optLongOrNull("oneOffEpochDay"),
                )
            }
        }

        val records = mutableListOf<BackupRecord>()
        val seenOccurrences = HashSet<Pair<Long, Long>>()
        root.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val status = o.optString("status").trim()
                val name = o.optString("courseName").trim()
                if (status.isEmpty() || name.isEmpty() || !o.has("epochDay") || !o.has("openAtMillis")) continue
                val courseId = o.optLong("courseId", 0)
                val epochDay = o.optLong("epochDay")
                // Satu kemunculan (matkul + tanggal) hanya boleh satu baris riwayat.
                if (!seenOccurrences.add(courseId to epochDay)) continue
                val open = o.optLong("openAtMillis")
                records += BackupRecord(
                    courseId = courseId,
                    courseName = name,
                    epochDay = epochDay,
                    openAtMillis = open,
                    endAtMillis = o.optLong("endAtMillis", open),
                    status = status,
                    doneAtMillis = o.optLongOrNull("doneAtMillis"),
                    error = o.optStringOrNull("error"),
                )
            }
        }

        val settings = LinkedHashMap<String, Any>()
        root.optJSONObject("settings")?.let { o ->
            for (key in o.keys()) {
                when (val v = o.opt(key)) {
                    is Boolean, is String -> settings[key] = v
                    is Int -> settings[key] = v
                    is Long -> settings[key] = v
                    is Number -> settings[key] = v.toLong()
                    else -> Unit
                }
            }
        }

        return Backup(
            createdAt = root.optString("createdAt"),
            appVersion = root.optString("appVersion"),
            courses = courses,
            records = records,
            settings = settings,
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().ifEmpty { null } else null
}
