package com.pengingatabsen.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.logic.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Menjalankan [PresensiCheck] sebagai foreground service singkat ("Mengecek presensi SiAdin…").
 * Dimulai tepat saat alarm berbunyi, jadi tidak ditunda Doze/penghemat baterai pabrikan seperti
 * pekerjaan WorkManager. Satu kemunculan matkul hanya dicek satu per satu: alarm berikutnya yang datang
 * saat cek masih berjalan diabaikan (hasil cek yang sedang berjalan tidak dibuang).
 */
class PresensiCheckService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val courseId = intent?.getLongExtra(KEY_COURSE_ID, -1L) ?: -1L
        val epochDay = intent?.getLongExtra(KEY_EPOCH_DAY, 0L) ?: 0L
        val type = intent?.getStringExtra(KEY_TYPE)?.let { runCatching { EventType.valueOf(it) }.getOrNull() }
            ?: EventType.REMIND
        val startedAt = intent?.getLongExtra(KEY_STARTED_AT, 0L) ?: 0L
        val key = "$courseId:$epochDay"

        // Wajib segera: Android menghentikan paksa service yang tidak memanggil startForeground.
        // Bila sistem menolak (pembatasan latar belakang), jangan crash: pindah ke cadangan WorkManager.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                Notifications.checkingNotification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
        if (foreground.isFailure) {
            DiagLog.add("cek: startForeground DITOLAK (${foreground.exceptionOrNull()?.javaClass?.simpleName}) → cadangan WorkManager")
            // enqueue() membaca status antrean (memblokir sebentar): jangan di main thread.
            val app = applicationContext
            if (courseId >= 0 || courseId == PresensiCheck.PREFLIGHT_ID) {
                Thread { PresensiCheckWorker.enqueue(app, courseId, epochDay, type, startedAt) }.start()
            }
            if (running.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        if (courseId == PresensiCheck.PREFLIGHT_ID) {
            if (!running.add("preflight")) return START_NOT_STICKY
            scope.launch {
                try {
                    PresensiCheck.preflight(applicationContext)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagLog.add("kesiapan ERROR: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    running.remove("preflight")
                    stopIfIdle()
                }
            }
            return START_NOT_STICKY
        }

        if (courseId < 0 || !running.add(key)) {
            if (courseId >= 0) DiagLog.add("cek: dilewati, cek matkul ini masih berjalan")
            stopIfIdle()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                PresensiCheck.run(applicationContext, courseId, epochDay, type, startedAt)
            } catch (e: Exception) {
                // Gagal tak terduga: catat, biarkan alarm berikutnya mencoba lagi.
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiagLog.add("cek ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running.remove(key)
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (running.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 778
        private const val KEY_COURSE_ID = "course_id"
        private const val KEY_EPOCH_DAY = "epoch_day"
        private const val KEY_TYPE = "type"
        private const val KEY_STARTED_AT = "started_at"

        fun intent(context: Context, courseId: Long, epochDay: Long, type: EventType, startedAt: Long): Intent =
            Intent(context, PresensiCheckService::class.java).apply {
                data = Uri.parse("pengingatabsen://cek/$courseId/$epochDay")
                putExtra(KEY_COURSE_ID, courseId)
                putExtra(KEY_EPOCH_DAY, epochDay)
                putExtra(KEY_TYPE, type.name)
                putExtra(KEY_STARTED_AT, startedAt)
            }
    }
}
