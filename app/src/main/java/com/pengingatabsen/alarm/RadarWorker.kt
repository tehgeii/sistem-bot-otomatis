package com.pengingatabsen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pengingatabsen.Graph
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.launch.SiadinChecker
import com.pengingatabsen.launch.SiadinScripts
import com.pengingatabsen.launch.TargetApps
import com.pengingatabsen.logic.Radar
import com.pengingatabsen.logic.ScheduleMath
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Radar presensi di luar jadwal, bagian "cek ringan": tiap ±30 menit pada hari kuliah (07.00–17.30) membaca halaman
 * Presensi Online sekali (hanya membaca), lalu [Guards.radar]. Tidak berjalan saat jendela kuliah mana pun sedang
 * berlangsung (pengecekan biasa sudah membaca halaman yang sama). Di data seluler paling sering tiap ±1 jam.
 */
class RadarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Graph.settings
        val settings = store.current()
        if (!settings.radar || !settings.smartModeActive) return Result.success()
        val now = LocalDateTime.now()
        val courses = Graph.repository.allCourses().filter { it.active }
        if (!Radar.activeNow(courses.map { it.toSlot() }, now)) return Result.success()
        if (courses.any { ScheduleMath.currentOccurrence(it.toSlot(ScheduleMath.SMART_GRACE_MINUTES), now) != null }) {
            return Result.success()
        }
        val metered = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        val nowMillis = System.currentTimeMillis()
        if (metered && nowMillis - store.radarLastRun() < METERED_MIN_GAP_MS) return Result.success()
        store.setRadarLastRun(nowMillis)

        val uid = android.os.Process.myUid()
        val rxBefore = android.net.TrafficStats.getUidRxBytes(uid)
        val txBefore = android.net.TrafficStats.getUidTxBytes(uid)
        val result = SiadinChecker.check(
            applicationContext,
            settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL,
            if (settings.autoLogin) store.siadinLogin() else null,
            courseName = "",
            extractScript = SiadinScripts.CARD_TEXTS_SCRIPT,
        )
        val rxAfter = android.net.TrafficStats.getUidRxBytes(uid)
        val txAfter = android.net.TrafficStats.getUidTxBytes(uid)
        if (rxBefore >= 0 && rxAfter >= rxBefore) store.addCheckBytes((rxAfter - rxBefore) + (txAfter - txBefore).coerceAtLeast(0))
        val cards = com.pengingatabsen.data.OfficialSync.cardTexts(result.extracted)
        DiagLog.add("radar: cek ringan — ${cards.size} kartu (${result.state})")
        com.pengingatabsen.data.OfficialSync.absorb(applicationContext, cards)
        Guards.radar(applicationContext, cards)
        return Result.success()
    }

    companion object {
        private const val NAME = "radar-presensi"
        private const val METERED_MIN_GAP_MS = 55 * 60_000L

        /** Pasang/hapus cek ringan berkala. Aman dipanggil berulang (KEEP). */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RadarWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Alarm "sebelum kuliah": pengingat + cek mode senyap & baterai, lalu pasang yang berikutnya. */
class PreClassReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_PRE_CLASS) return
        val openMillis = intent.getLongExtra(AlarmScheduler.EXTRA_OPEN_MILLIS, 0L)
        runAsync {
            if (openMillis > 0) Guards.preClass(context, openMillis)
            AlarmScheduler.schedulePreClass(context)
        }
    }
}
