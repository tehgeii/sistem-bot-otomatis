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

/**
 * Mode pintar: saat alarm berbunyi, cek dulu halaman Presensi Online SiAdin.
 * - Belum dibuka dosen  → notifikasi senyap "Menunggu presensi…"
 * - Sudah dibuka / gagal cek → notifikasi biasa yang bergetar (gagal cek = tetap bergetar, aman).
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
        )

        // Baca ulang: pengguna mungkin sudah menekan tombol selama pengecekan berjalan.
        val record = dao.find(courseId, epochDay) ?: return Result.success()
        if (record.status.finished) return Result.success()

        when {
            state == PresensiState.WAITING ->
                Notifications.showReminder(applicationContext, course, record, final = false, waiting = true)
            type == EventType.FINAL ->
                Notifications.showReminder(applicationContext, course, record, final = true)
            record.awaitingConfirm ->
                Notifications.showConfirm(applicationContext, course, record, silent = false)
            else ->
                Notifications.showReminder(applicationContext, course, record, final = false)
        }
        if (state == PresensiState.OPEN) {
            store.markPresensiOpen(courseId, epochDay)
            // Sudah dibuka: kembali ke interval pengingat pengguna (bukan cek tiap menit).
            AlarmScheduler.reschedule(applicationContext, courseId)
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
