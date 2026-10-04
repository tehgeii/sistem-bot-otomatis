package com.pengingatabsen.telegram

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.NotificationActionReceiver
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.data.toLocalDateTime
import com.pengingatabsen.logic.Formatters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kirim bukti / pemberitahuan terlewat ke Telegram.
 * WorkManager menunggu sampai ada internet dan mengulang otomatis bila gagal sementara.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1)
        val kind = inputData.getString(KEY_KIND) ?: KIND_PROOF
        val dao = Graph.db.recordDao()
        val record = dao.get(recordId) ?: return Result.success()
        val isProof = kind == KIND_PROOF

        val token = Graph.settings.botToken()
        val chatId = Graph.settings.current().chatId
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) {
            if (isProof) fail(recordId, "Telegram belum diatur di Pengaturan")
            return Result.failure()
        }

        val text = if (isProof) {
            Formatters.proofMessage(record.courseName, (record.doneAtMillis ?: System.currentTimeMillis()).toLocalDateTime())
        } else {
            Formatters.missedMessage(record.courseName, record.openAtMillis.toLocalDateTime(), record.endAtMillis.toLocalDateTime())
        }
        val photo = record.photoPath?.let(::File)?.takeIf { isProof && it.exists() }
        val result = if (photo != null) TelegramClient.sendPhoto(token, chatId, photo, text)
        else TelegramClient.sendMessage(token, chatId, text)

        return when (result) {
            is TgResult.Ok -> {
                if (isProof) dao.get(recordId)?.let { dao.update(it.copy(status = RecordStatus.SENT, error = null)) }
                Result.success()
            }
            is TgResult.Error -> if (result.retryable && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                if (isProof) fail(recordId, result.message)
                Result.failure()
            }
        }
    }

    private suspend fun fail(recordId: Long, message: String) {
        val dao = Graph.db.recordDao()
        val record = dao.get(recordId) ?: return
        dao.update(record.copy(status = RecordStatus.FAILED, error = message))
        val resend = PendingIntent.getBroadcast(
            applicationContext, 0,
            Intent(applicationContext, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_RESEND)
                .setData(Uri.parse("pengingatabsen://resend/$recordId"))
                .putExtra(NotificationActionReceiver.EXTRA_RECORD_ID, recordId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notifications.showInfo(
            applicationContext,
            failureNotificationId(recordId),
            "Gagal kirim bukti ${record.courseName}",
            message,
            "Kirim ulang" to resend,
        )
    }

    companion object {
        private const val KEY_RECORD_ID = "record_id"
        private const val KEY_KIND = "kind"
        const val KIND_PROOF = "proof"
        const val KIND_MISSED = "missed"
        private const val MAX_ATTEMPTS = 10

        fun failureNotificationId(recordId: Long) = 500_000 + (recordId % 100_000).toInt()

        fun enqueue(context: Context, recordId: Long, kind: String) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(workDataOf(KEY_RECORD_ID to recordId, KEY_KIND to kind))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("kirim-$kind-$recordId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
