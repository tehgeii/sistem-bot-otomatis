package com.pengingatabsen.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pengingatabsen.logic.EventType

/**
 * Cadangan [PresensiCheckService]: menjalankan [PresensiCheck] lewat WorkManager bila sistem menolak
 * memulai foreground service (jarang; mis. dipicu saat aplikasi tidak boleh memulai service dari latar).
 */
class PresensiCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val courseId = inputData.getLong(KEY_COURSE_ID, -1)
        val epochDay = inputData.getLong(KEY_EPOCH_DAY, 0)
        val type = inputData.getString(KEY_TYPE)?.let { runCatching { EventType.valueOf(it) }.getOrNull() }
            ?: EventType.REMIND
        PresensiCheck.run(applicationContext, courseId, epochDay, type, inputData.getLong(KEY_ENQUEUED_AT, 0L))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(CHECKING_NOTIFICATION_ID, Notifications.checkingNotification(applicationContext))

    companion object {
        private const val KEY_COURSE_ID = "course_id"
        private const val KEY_EPOCH_DAY = "epoch_day"
        private const val KEY_TYPE = "type"
        private const val KEY_ENQUEUED_AT = "enqueued_at"
        private const val CHECKING_NOTIFICATION_ID = 778

        fun enqueue(context: Context, courseId: Long, epochDay: Long, type: EventType, enqueuedAt: Long) {
            val name = "cek-presensi-$courseId-$epochDay"
            val wm = WorkManager.getInstance(context)
            // Cek yang SEDANG berjalan dibiarkan selesai (KEEP); cek yang masih tertunda di antrean
            // diganti (REPLACE) supaya satu antrean yang ditahan sistem tidak menahan semua cek berikutnya.
            val running = runCatching {
                wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            val request = OneTimeWorkRequestBuilder<PresensiCheckWorker>()
                .setInputData(
                    workDataOf(
                        KEY_COURSE_ID to courseId,
                        KEY_EPOCH_DAY to epochDay,
                        KEY_TYPE to type.name,
                        KEY_ENQUEUED_AT to enqueuedAt,
                    ),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(name, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }
    }
}
