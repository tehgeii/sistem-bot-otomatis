package com.pengingatabsen.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pengingatabsen.Graph
import com.pengingatabsen.launch.PresensiState
import com.pengingatabsen.launch.SiadinChecker
import com.pengingatabsen.launch.TargetApps
import com.pengingatabsen.logic.EventType
import java.time.LocalDateTime

/**
 * Mode pintar: saat alarm berbunyi, cek dulu halaman Presensi Online SiAdin.
 * - Belum dibuka dosen → notifikasi senyap "Menunggu presensi…"
 * - Sudah dibuka ("Presensi Sekarang" terlihat stabil) → notifikasi baru yang bergetar
 * - "Berhasil Presensi" untuk matkul ini → dicatat selesai & bukti dikirim, pengingat berhenti
 * - Gagal cek → diam dulu; bergetar "cek manual" setelah 3 kali gagal berturut-turut (aman, tidak terlewat)
 */
class PresensiCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val courseId = inputData.getLong(KEY_COURSE_ID, -1)
        val epochDay = inputData.getLong(KEY_EPOCH_DAY, 0)
        val type = inputData.getString(KEY_TYPE)?.let { runCatching { EventType.valueOf(it) }.getOrNull() }
            ?: EventType.REMIND
        val course = Graph.repository.course(courseId) ?: return Result.success()
        val dao = Graph.db.recordDao()
        if (dao.find(courseId, epochDay)?.status?.finished != false) return Result.success()

        val store = Graph.settings
        val settings = store.current()
        val state = SiadinChecker.check(
            applicationContext,
            settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL,
            if (settings.autoLogin) store.siadinLogin() else null,
            course.name,
        )

        // Baca ulang: pengguna mungkin sudah menekan tombol selama pengecekan berjalan.
        val record = dao.find(courseId, epochDay) ?: return Result.success()
        if (record.status.finished) return Result.success()

        val ctx = applicationContext
        when (state) {
            PresensiState.WAITING -> {
                store.setPresensiUnknownStreak(courseId, epochDay, 0)
                Notifications.showReminder(ctx, course, record, final = false, waiting = true)
            }
            PresensiState.DONE -> {
                // SiAdin sudah menampilkan "Berhasil Presensi" untuk matkul ini (pengguna presensi sendiri,
                // mis. lewat Chrome/Dinusverse): catat selesai, kirim bukti, hentikan pengingat.
                store.setPresensiUnknownStreak(courseId, epochDay, 0)
                Notifications.cancel(ctx, courseId)
                Graph.repository.confirmDone(record, LocalDateTime.now())
                AlarmScheduler.reschedule(ctx, courseId)
            }
            PresensiState.OPEN -> {
                store.setPresensiUnknownStreak(courseId, epochDay, 0)
                store.markPresensiOpen(courseId, epochDay)
                // Hapus dulu notifikasi senyap supaya yang baru diposting ulang & pasti bergetar.
                Notifications.cancel(ctx, courseId)
                // Layar penuh hanya di momen ini: kartu baru saja berubah menjadi "Presensi Sekarang".
                val fullScreenUrl = if (settings.fullScreenAlert) settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL else null
                when {
                    type == EventType.FINAL ->
                        Notifications.showReminder(ctx, course, record, final = true, fullScreenUrl = fullScreenUrl)
                    record.awaitingConfirm -> Notifications.showConfirm(ctx, course, record, silent = false)
                    else -> Notifications.showReminder(
                        ctx, course, record, final = false, sessionOpen = true, fullScreenUrl = fullScreenUrl,
                    )
                }
                // Sudah dibuka: kembali ke interval pengingat pengguna (bukan cek tiap menit).
                AlarmScheduler.reschedule(ctx, courseId)
            }
            PresensiState.UNKNOWN -> {
                // Sekali gagal (internet putus sebentar) jangan langsung mengubah notifikasi.
                // Baru bergetar "cek manual" setiap 3 kali gagal berturut-turut (±3 menit), atau di FINAL.
                val streak = store.presensiUnknownStreak(courseId, epochDay) + 1
                val escalate = streak >= UNKNOWN_ESCALATE_AFTER || type == EventType.FINAL
                store.setPresensiUnknownStreak(courseId, epochDay, if (escalate) 0 else streak)
                if (escalate) {
                    Notifications.cancel(ctx, courseId)
                    Notifications.showReminder(ctx, course, record, final = type == EventType.FINAL, checkFailed = true)
                } else if (type == EventType.OPEN) {
                    // Pengecekan pertama gagal: tampilkan status menunggu (senyap) agar notifikasi tetap ada.
                    Notifications.showReminder(ctx, course, record, final = false, waiting = true)
                }
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(CHECKING_NOTIFICATION_ID, Notifications.checkingNotification(applicationContext))

    companion object {
        private const val KEY_COURSE_ID = "course_id"
        private const val KEY_EPOCH_DAY = "epoch_day"
        private const val KEY_TYPE = "type"
        private const val CHECKING_NOTIFICATION_ID = 778
        private const val UNKNOWN_ESCALATE_AFTER = 3

        fun enqueue(context: Context, courseId: Long, epochDay: Long, type: EventType) {
            val request = OneTimeWorkRequestBuilder<PresensiCheckWorker>()
                .setInputData(workDataOf(KEY_COURSE_ID to courseId, KEY_EPOCH_DAY to epochDay, KEY_TYPE to type.name))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("cek-presensi-$courseId-$epochDay", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
