package com.pengingatabsen.telegram

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pengingatabsen.Graph
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.logic.SummaryItem
import com.pengingatabsen.logic.SummaryKind
import com.pengingatabsen.logic.WeeklySummary
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Ringkasan mingguan ke Telegram (Minggu malam): berhasil, terlewat, libur, tidak dibuka dosen.
 * Terjadwal tiap 7 hari; pengiriman yang telat tetap merangkum minggu yang benar dan tidak dikirim dua kali.
 */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val store = Graph.settings
        val settings = store.current()
        if (!manual && !settings.weeklySummary) return Result.success()
        val token = store.botToken()
        val chatId = settings.chatId
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) return Result.success()

        val now = LocalDateTime.now()
        // Manual (tombol tes): minggu berjalan. Terjadwal: minggu yang sudah waktunya, sekali saja.
        val weekStart = if (manual) WeeklySummary.weekStart(now.toLocalDate()) else WeeklySummary.dueWeekStart(now)
        if (!manual && store.summarySentWeek() == weekStart.toEpochDay()) return Result.success()

        val items = Graph.db.recordDao()
            .between(weekStart.toEpochDay(), weekStart.plusDays(6).toEpochDay())
            .map { SummaryItem(it.courseName, LocalDate.ofEpochDay(it.epochDay), kindOf(it.status)) }
        val text = WeeklySummary.build(weekStart, items)
            ?: if (manual) "📊 Belum ada jadwal minggu ini." else null
        if (text == null) {
            store.setSummarySentWeek(weekStart.toEpochDay())
            return Result.success()
        }

        return when (val result = TelegramClient.sendMessage(token, chatId, text)) {
            is TgResult.Ok -> {
                if (!manual) store.setSummarySentWeek(weekStart.toEpochDay())
                Result.success()
            }
            is TgResult.Error -> if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun kindOf(status: RecordStatus) = when (status) {
        RecordStatus.SENT, RecordStatus.QUEUED -> SummaryKind.DONE
        RecordStatus.FAILED -> SummaryKind.FAILED
        RecordStatus.MISSED -> SummaryKind.MISSED
        RecordStatus.NO_SESSION -> SummaryKind.NO_SESSION
        RecordStatus.HOLIDAY -> SummaryKind.HOLIDAY
        RecordStatus.ACTIVE -> SummaryKind.ACTIVE
    }

    companion object {
        private const val KEY_MANUAL = "manual"
        private const val PERIODIC_NAME = "ringkasan-mingguan"
        private const val MAX_ATTEMPTS = 6

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Pasang/hapus jadwal ringkasan sesuai pengaturan. Aman dipanggil berulang (KEEP). */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val delay = Duration.between(LocalDateTime.now(), WeeklySummary.nextSendTime(LocalDateTime.now()))
            val request = PeriodicWorkRequestBuilder<SummaryWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delay.toMinutes().coerceAtLeast(0), TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Tombol "Kirim ringkasan sekarang" (minggu berjalan, untuk mencoba). */
        fun sendNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SummaryWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ringkasan-manual", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
