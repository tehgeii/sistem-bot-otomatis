package com.pengingatabsen.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.launch.PresensiState
import com.pengingatabsen.launch.SiadinChecker
import com.pengingatabsen.launch.SiadinScripts
import com.pengingatabsen.launch.TargetApps
import com.pengingatabsen.logic.OfficialAttendance
import com.pengingatabsen.logic.OfficialSnapshot
import com.pengingatabsen.logic.OfficialStatus
import com.pengingatabsen.logic.VerifyOutcome
import com.pengingatabsen.telegram.NoticeWorker
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Kehadiran RESMI dari SiAdin: persentase di kartu Presensi Online (terbaca di setiap pengecekan, tanpa kuota
 * tambahan) dan kartu KRS (semua matkul, lewat "Sinkronkan" atau ringkasan mingguan). Hanya MEMBACA halaman SiAdin.
 */
object OfficialSync {
    /** Teks kartu dari hasil `SiadinScripts.CARD_TEXTS_SCRIPT` (JSON array). */
    fun cardTexts(extracted: String?): List<String> = extracted?.let { raw ->
        runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrNull()
    }.orEmpty()

    /** Simpan persentase resmi yang terbaca di kartu-kartu halaman ini. Mengembalikan yang diperbarui. */
    suspend fun absorb(context: Context, cardTexts: List<String>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        if (cardTexts.isEmpty()) return emptyMap()
        val names = Graph.repository.allCourses().map { it.name }
        val found = OfficialAttendance.match(cardTexts, names)
        if (found.isEmpty()) return found
        Graph.settings.mergeOfficial(found.mapValues { OfficialSnapshot(it.value, now) })
        DiagLog.add("kehadiran resmi: " + found.entries.joinToString { "${it.key} ${OfficialAttendance.pct(it.value)}%" })
        warnIfLow(context, found.keys)
        return found
    }

    /** Status resmi semua matkul yang punya angka SiAdin (untuk Riwayat & ringkasan mingguan). */
    suspend fun statuses(now: LocalDateTime = LocalDateTime.now()): List<OfficialStatus> {
        val settings = Graph.settings.current()
        val start = settings.semesterStart
        return settings.official.map { (name, snap) ->
            val held = start?.let { heldFor(name, it, now) }
            OfficialAttendance.evaluate(name, snap.percent, settings.attendanceRule, held)
        }.sortedBy { it.courseName.lowercase() }
    }

    /**
     * Perkiraan pertemuan yang sudah berlangsung untuk satu matkul (semua jadwal mingguan bernama sama), dikurangi
     * tanggal libur / tidak dibuka dosen, ditambah kelas pengganti yang sudah terjadi (dari riwayat).
     */
    private suspend fun heldFor(name: String, start: LocalDate, now: LocalDateTime): Int {
        val courses = Graph.repository.allCourses().filter { it.name == name }
        val weekly = courses.filter { !it.isOneOff }
        val weeklyIds = weekly.map { it.id }.toSet()
        val records = Graph.db.recordDao().forCourseNameSince(name, start.toEpochDay())
            .filter { it.courseId > 0 && it.epochDay <= now.toLocalDate().toEpochDay() }
        val slots = weekly.map { c ->
            val excluded = records.filter {
                it.courseId == c.id && (it.status == RecordStatus.HOLIDAY || it.status == RecordStatus.NO_SESSION)
            }.map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
            c.toSlot() to excluded
        }
        val counted = setOf(RecordStatus.SENT, RecordStatus.QUEUED, RecordStatus.FAILED, RecordStatus.MISSED)
        val extra = records.count { it.courseId !in weeklyIds && it.status in counted }
        return OfficialAttendance.heldEstimate(slots, start, now, extra)
    }

    /** Peringatan sekali per keadaan bila perkiraan jatah resmi menipis/habis (hanya bila awal semester diatur). */
    private suspend fun warnIfLow(context: Context, names: Set<String>) {
        if (Graph.settings.current().semesterStart == null) return
        for (s in statuses().filter { it.courseName in names }) {
            val text = OfficialAttendance.warning(s) ?: continue
            if (!Graph.settings.claimOfficialWarning("${s.courseName}|${s.absent}|${s.reachable}")) continue
            DiagLog.add("PERINGATAN jatah resmi: ${s.courseName} tidak hadir ${s.absent}")
            Notifications.showInfo(context, 9_500 + (s.courseName.hashCode() and 0x3FF), "Jatah tidak hadir (SiAdin)", text)
            NoticeWorker.enqueue(context, "jatah-resmi-${s.courseName.hashCode()}", text)
        }
    }

    /** Baca kartu KRS (semua matkul) dan simpan persentasenya. Pesan hasil untuk ditampilkan. */
    suspend fun syncFromKrs(context: Context): String {
        val settings = Graph.settings.current()
        val credentials = if (settings.autoLogin) Graph.settings.siadinLogin() else null
        if (credentials == null) return "Simpan NIM & password SiAdin dulu (Pengaturan → SiAdin web)."
        val result = SiadinChecker.check(
            context.applicationContext,
            TargetApps.SIADIN_ORIGIN + "/akademik",
            credentials,
            courseName = "",
            extractScript = SiadinScripts.CARD_TEXTS_SCRIPT,
        ) { DiagLog.add("sinkron kehadiran: $it") }
        val found = absorb(context, cardTexts(result.extracted))
        return when {
            found.isNotEmpty() -> "${found.size} matkul diperbarui dari SiAdin."
            result.state == PresensiState.LOGIN_FAILED -> "Login SiAdin ditolak. Perbarui NIM/password."
            else -> "Kehadiran resmi tidak terbaca (${result.detail.substringBefore(" |")}). Coba lagi nanti."
        }
    }

    // ---------- Cek presensi benar-benar tercatat ----------

    /**
     * Dipanggil saat presensi dicatat selesai. Persentase resmi SEBELUM presensi diingat; ±10 menit kemudian
     * dibandingkan lagi (KRS). Tidak naik setelah 3 kali cek (±4 jam) → peringatan.
     */
    suspend fun scheduleVerify(context: Context, courseName: String) {
        if (!Graph.settings.current().smartModeActive) return
        val before = Graph.settings.officialSnapshots()[courseName]?.percent ?: return
        VerifyWorker.enqueue(context, courseName, before, attempt = 1)
    }

    /**
     * Pengecek melihat "Berhasil Presensi" beserta persentasenya ([after]); [before] = angka resmi sebelum presensi.
     * Sudah naik → selesai saat itu juga. Belum naik / tak terbaca → dicek ulang nanti. Tanpa angka sebelumnya → dilewati.
     */
    fun afterCheckerDone(context: Context, courseName: String, before: Double?, after: Double?) {
        if (before == null) return
        when (OfficialAttendance.verify(before, after)) {
            VerifyOutcome.RECORDED -> {
                DiagLog.add("✓ presensi $courseName tercatat di SiAdin: ${OfficialAttendance.pct(before)}% → ${OfficialAttendance.pct(after!!)}%")
                VerifyWorker.cancel(context, courseName)
            }
            VerifyOutcome.NOT_YET, VerifyOutcome.UNKNOWN -> {
                DiagLog.add("presensi $courseName: angka resmi belum naik (${after?.let { OfficialAttendance.pct(it) } ?: "?"}%), dicek lagi nanti")
                VerifyWorker.enqueue(context, courseName, before, attempt = 1)
            }
        }
    }
}

/** Cek ulang (lewat KRS) apakah persentase resmi sudah naik setelah presensi. */
class VerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.success()
        val before = inputData.getDouble(KEY_BEFORE, -1.0).takeIf { it >= 0 } ?: return Result.success()
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        val message = OfficialSync.syncFromKrs(applicationContext)
        val after = Graph.settings.officialSnapshots()[name]?.percent
        when (OfficialAttendance.verify(before, after)) {
            VerifyOutcome.RECORDED ->
                DiagLog.add("✓ presensi $name tercatat di SiAdin: ${OfficialAttendance.pct(before)}% → ${OfficialAttendance.pct(after!!)}%")
            VerifyOutcome.NOT_YET, VerifyOutcome.UNKNOWN -> {
                DiagLog.add("cek tercatat $name ke-$attempt: belum naik (${after?.let { OfficialAttendance.pct(it) } ?: "?"}%) — $message")
                if (attempt < DELAYS_MIN.size) {
                    enqueue(applicationContext, name, before, attempt + 1, ExistingWorkPolicy.APPEND_OR_REPLACE)
                } else if (after != null) {
                    // Terbaca tapi tetap tidak naik setelah ±4 jam: beri tahu (sekali).
                    val text = "⚠️ Presensi $name hari ini belum tercatat di SiAdin: kehadiran resmi masih " +
                        "${OfficialAttendance.pct(after)}% (sama seperti sebelum presensi). Buka SiAdin untuk memastikan; " +
                        "bila memang belum tercatat, segera lapor dosen/akademik."
                    if (Graph.settings.claimOfficialWarning("tercatat|$name|${LocalDate.now()}")) {
                        Notifications.showInfo(applicationContext, 9_800 + (name.hashCode() and 0x1FF), "Presensi belum tercatat?", text)
                        NoticeWorker.enqueue(applicationContext, "tercatat-${name.hashCode()}", text)
                    }
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_BEFORE = "before"
        private const val KEY_ATTEMPT = "attempt"
        /** Jeda sebelum cek ke-1, ke-2, ke-3 (menit). */
        private val DELAYS_MIN = longArrayOf(10, 60, 180)

        private fun unique(name: String) = "cek-tercatat-${name.hashCode()}"

        /** [policy]: REPLACE untuk presensi baru; APPEND_OR_REPLACE saat menjadwalkan cek berikutnya dari dalam worker. */
        fun enqueue(context: Context, name: String, before: Double, attempt: Int, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val delay = DELAYS_MIN[(attempt - 1).coerceIn(0, DELAYS_MIN.lastIndex)]
            val request = OneTimeWorkRequestBuilder<VerifyWorker>()
                .setInputData(workDataOf(KEY_NAME to name, KEY_BEFORE to before, KEY_ATTEMPT to attempt))
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(unique(name), policy, request)
        }

        fun cancel(context: Context, name: String) {
            WorkManager.getInstance(context).cancelUniqueWork(unique(name))
        }
    }
}
