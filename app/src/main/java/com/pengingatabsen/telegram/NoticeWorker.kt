package com.pengingatabsen.telegram

import android.content.Context
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
import java.util.concurrent.TimeUnit

/**
 * Pesan pemberitahuan singkat ke Telegram (mis. "tampilan SiAdin berubah", "jatah tidak hadir menipis").
 * Dikirim saat ada internet, dicoba ulang bila gagal sementara. Tanpa bot tersambung: dilewati diam-diam.
 */
class NoticeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val store = Graph.settings
        val token = store.botToken()
        val chatId = store.current().chatId
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) return Result.success()
        return when (val result = TelegramClient.sendMessage(token, chatId, text)) {
            is TgResult.Ok -> Result.success()
            is TgResult.Error -> if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_TEXT = "text"
        private const val MAX_ATTEMPTS = 6

        /** [key]: pesan dengan kunci sama menggantikan yang belum terkirim (tidak menumpuk). */
        fun enqueue(context: Context, key: String, text: String) {
            val request = OneTimeWorkRequestBuilder<NoticeWorker>()
                .setInputData(workDataOf(KEY_TEXT to text))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("pemberitahuan-$key", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
