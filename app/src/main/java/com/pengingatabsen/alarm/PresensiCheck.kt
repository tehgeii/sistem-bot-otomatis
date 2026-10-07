package com.pengingatabsen.alarm

import android.content.Context
import androidx.core.content.ContextCompat
import com.pengingatabsen.Graph
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.launch.PresensiState
import com.pengingatabsen.launch.SiadinChecker
import com.pengingatabsen.launch.TargetApps
import com.pengingatabsen.logic.EventType
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.widget.NextCourseWidget
import java.time.LocalDateTime

/**
 * Mode pintar: cek halaman Presensi Online SiAdin untuk satu kemunculan matkul, lalu perbarui notifikasi.
 * - Belum dibuka dosen → notifikasi senyap "Menunggu presensi…"
 * - Sudah dibuka ("Presensi Sekarang" terlihat stabil) → notifikasi baru yang bergetar (+ layar penuh)
 * - "Berhasil Presensi" untuk matkul ini → dicatat selesai & bukti dikirim, pengingat berhenti
 * - Gagal cek → diam dulu; bergetar "cek manual" setelah 3 kali gagal berturut-turut (aman, tidak terlewat)
 *
 * Dijalankan oleh [PresensiCheckService] (foreground service, langsung jalan saat alarm berbunyi walau
 * layar mati) dengan [PresensiCheckWorker] sebagai cadangan bila service tidak boleh dimulai.
 */
object PresensiCheck {
    private const val UNKNOWN_ESCALATE_AFTER = 3

    /** Mulai pengecekan sekarang: foreground service, atau WorkManager bila sistem menolak. */
    fun start(context: Context, courseId: Long, epochDay: Long, type: EventType) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val started = runCatching {
            ContextCompat.startForegroundService(app, PresensiCheckService.intent(app, courseId, epochDay, type, now))
        }
        if (started.isFailure) {
            DiagLog.add("cek: service DITOLAK sistem (${started.exceptionOrNull()?.javaClass?.simpleName}) → cadangan WorkManager")
            PresensiCheckWorker.enqueue(app, courseId, epochDay, type, now)
        } else {
            DiagLog.add("cek: service diminta (${type.name})")
        }
    }

    suspend fun run(context: Context, courseId: Long, epochDay: Long, type: EventType, startedAt: Long) {
        val ctx = context.applicationContext
        val course = Graph.repository.course(courseId) ?: return
        val dao = Graph.db.recordDao()
        if (dao.find(courseId, epochDay)?.status?.finished != false) {
            DiagLog.add("cek ${course.name}: dilewati (sudah selesai/tidak ada catatan)")
            return
        }

        val store = Graph.settings
        val settings = store.current()
        // Ukur seberapa telat cek ini mulai sejak alarm berbunyi (Doze/penghemat baterai bisa menundanya).
        val delaySec = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else -1
        if (delaySec >= 0) store.setLastCheckDelay(delaySec)
        DiagLog.add("cek ${course.name} (${type.name}) mulai, telat ${delaySec}dtk dari alarm")
        // Ukur perkiraan data yang dipakai pengecekan ini (untuk ditampilkan di Pengaturan).
        val uid = android.os.Process.myUid()
        val rxBefore = android.net.TrafficStats.getUidRxBytes(uid)
        val txBefore = android.net.TrafficStats.getUidTxBytes(uid)
        val result = SiadinChecker.check(
            ctx,
            settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL,
            if (settings.autoLogin) store.siadinLogin() else null,
            course.name,
        ) { DiagLog.add("cek ${course.name}: $it") }
        val state = result.state
        val rxAfter = android.net.TrafficStats.getUidRxBytes(uid)
        val txAfter = android.net.TrafficStats.getUidTxBytes(uid)
        if (rxBefore >= 0 && rxAfter >= rxBefore) {
            store.addCheckBytes((rxAfter - rxBefore) + (txAfter - txBefore).coerceAtLeast(0))
        }
        // Catatan diagnosis (tampil di Pengaturan): kapan cek terakhir, matkul apa, hasilnya apa.
        store.setLastCheck("${Formatters.hm(LocalDateTime.now())} · ${course.name} · ${describe(state)}")
        DiagLog.add("HASIL ${course.name}: ${describe(state)} — ${result.detail}")

        // Baca ulang: pengguna mungkin sudah menekan tombol selama pengecekan berjalan.
        val record = dao.find(courseId, epochDay) ?: return
        if (record.status.finished) {
            DiagLog.add("cek ${course.name}: sudah selesai saat cek berjalan, notifikasi tidak diubah")
            return
        }

        val wasOpen = store.isPresensiOpen(courseId, epochDay)
        when (state) {
            PresensiState.WAITING -> {
                store.setPresensiUnknownStreak(courseId, epochDay, 0)
                DiagLog.add("notif: Menunggu presensi (senyap)")
                Notifications.showReminder(ctx, course, record, final = false, waiting = true)
            }
            PresensiState.DONE -> {
                // SiAdin sudah menampilkan "Berhasil Presensi" untuk matkul ini (pengguna presensi sendiri,
                // mis. lewat Chrome/Dinusverse): catat selesai, kirim bukti, hentikan pengingat.
                store.setPresensiUnknownStreak(courseId, epochDay, 0)
                DiagLog.add("selesai: Berhasil Presensi terdeteksi → bukti dikirim, pengingat berhenti")
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
                // Pengingat lanjutan (sudah dibuka sebelumnya) cukup notifikasi bergetar.
                val fullScreenUrl = if (settings.fullScreenAlert && !wasOpen) {
                    settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL
                } else {
                    null
                }
                DiagLog.add(
                    "notif: PRESENSI DIBUKA (getar" + (if (fullScreenUrl != null) ", layar penuh" else "") +
                        (if (fullScreenUrl != null && !Notifications.canUseFullScreen(ctx)) " — izin layar penuh BELUM ada" else "") + ")",
                )
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
            PresensiState.UNKNOWN, PresensiState.LOGIN_FAILED -> {
                // Login ditolak (NIM/password berubah?): beri tahu sekali sehari, pengingat tetap jalan.
                if (state == PresensiState.LOGIN_FAILED && store.claimLoginFailedNotice()) {
                    Notifications.showLoginFailed(ctx)
                }
                // Sekali gagal (internet putus sebentar) jangan langsung mengubah notifikasi.
                // Baru bergetar "cek manual" setiap 3 kali gagal berturut-turut (±3 menit), atau di FINAL.
                val streak = store.presensiUnknownStreak(courseId, epochDay) + 1
                val escalate = streak >= UNKNOWN_ESCALATE_AFTER || type == EventType.FINAL
                store.setPresensiUnknownStreak(courseId, epochDay, if (escalate) 0 else streak)
                DiagLog.add("gagal baca ke-$streak berturut-turut" + if (escalate) " → notif Cek presensi (getar)" else "")
                if (escalate) {
                    Notifications.cancel(ctx, courseId)
                    Notifications.showReminder(ctx, course, record, final = type == EventType.FINAL, checkFailed = true)
                } else if (wasOpen) {
                    // Presensi sudah terlihat dibuka: pengingat tetap jalan walau cek kali ini gagal.
                    Notifications.cancel(ctx, courseId)
                    Notifications.showReminder(ctx, course, record, final = false, sessionOpen = true)
                } else if (type == EventType.OPEN) {
                    // Pengecekan pertama gagal: tampilkan status menunggu (senyap) agar notifikasi tetap ada.
                    Notifications.showReminder(ctx, course, record, final = false, waiting = true)
                }
            }
        }
        NextCourseWidget.updateAll(ctx)
    }

    private fun describe(state: PresensiState) = when (state) {
        PresensiState.WAITING -> "menunggu dibuka"
        PresensiState.OPEN -> "presensi DIBUKA"
        PresensiState.DONE -> "berhasil presensi"
        PresensiState.UNKNOWN -> "gagal membaca (offline/halaman belum termuat)"
        PresensiState.LOGIN_FAILED -> "login ditolak"
    }
}
