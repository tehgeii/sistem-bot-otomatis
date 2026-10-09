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
    /** courseId khusus untuk service/worker: jalankan cek kesiapan, bukan cek satu matkul. */
    const val PREFLIGHT_ID = -100L

    /** Mulai cek kesiapan sekarang (foreground service; cadangan WorkManager). */
    fun startPreflight(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val started = runCatching {
            ContextCompat.startForegroundService(app, PresensiCheckService.intent(app, PREFLIGHT_ID, 0L, EventType.REMIND, now))
        }
        if (started.isFailure) {
            DiagLog.add("kesiapan: service DITOLAK (${started.exceptionOrNull()?.javaClass?.simpleName}) → cadangan WorkManager")
            PresensiCheckWorker.enqueue(app, PREFLIGHT_ID, 0L, EventType.REMIND, now)
        }
    }

    /**
     * Cek kesiapan ±30 menit sebelum kuliah pertama: izin penting + pengecek SiAdin yang sama dengan saat
     * kuliah dijalankan untuk matkul berikutnya. Hanya MEMBACA; notifikasi muncul HANYA bila ada masalah,
     * supaya bisa dibereskan sebelum kelas. Hasilnya juga tampil di layar Hari ini & Pengaturan.
     */
    suspend fun preflight(context: Context) {
        val ctx = context.applicationContext
        val store = Graph.settings
        val settings = store.current()
        val now = LocalDateTime.now()
        checkMissedAlarms(ctx)
        if (!settings.smartModeActive) return
        val target = Graph.repository.allCourses().filter { it.active }
            .mapNotNull { c -> com.pengingatabsen.logic.ScheduleMath.nextOccurrence(c.toSlot(), now.minusMinutes(1))?.let { c to it } }
            .minByOrNull { it.second.open } ?: return
        val (course, occ) = target
        val label = "${course.name} ${Formatters.hm(occ.open)}"
        DiagLog.add("kesiapan: cek untuk $label")

        val problems = mutableListOf<String>()
        val notificationsOn = Permissions.notificationsGranted(ctx) &&
            androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (!notificationsOn) problems += "notifikasi NgiBsen mati"
        if (!Permissions.exactAlarmGranted(ctx)) problems += "izin alarm tepat waktu belum ada"
        if (!Permissions.batteryUnrestricted(ctx)) problems += "optimasi baterai masih aktif"
        if (settings.fullScreenAlert && !Notifications.canUseFullScreen(ctx)) problems += "izin layar penuh belum ada"

        val result = SiadinChecker.check(
            ctx,
            settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL,
            if (settings.autoLogin) store.siadinLogin() else null,
            course.name,
            extractScript = com.pengingatabsen.launch.SiadinScripts.CARD_TEXTS_SCRIPT,
        ) { DiagLog.add("kesiapan ${course.name}: $it") }
        DiagLog.add("kesiapan HASIL ${course.name}: ${result.state} — ${result.detail}")
        // Persentase resmi di kartu hari ini (tanpa kuota tambahan) = angka "sebelum presensi" hari ini.
        com.pengingatabsen.data.OfficialSync.absorb(ctx, com.pengingatabsen.data.OfficialSync.cardTexts(result.extracted))
        SiadinHealth.record(ctx, course.id, occ.date.toEpochDay(), result)
        when (result.state) {
            PresensiState.WAITING, PresensiState.OPEN, PresensiState.DONE -> Unit
            PresensiState.LOGIN_FAILED -> problems.add(0, "login SiAdin ditolak — perbarui NIM/password")
            PresensiState.UNKNOWN -> problems.add(0, "SiAdin tidak terbaca (${result.detail.substringBefore(" |")})")
        }

        val time = Formatters.hm(now)
        if (problems.isEmpty()) {
            store.setReadiness(true, "$time · siap untuk $label")
            Notifications.cancelId(ctx, Notifications.READINESS_ID)
            DiagLog.add("kesiapan: SIAP")
        } else {
            store.setReadiness(false, "$time · ${problems.joinToString("; ")}")
            Notifications.showReadinessProblem(ctx, label, problems)
            DiagLog.add("kesiapan: MASALAH — ${problems.joinToString("; ")}")
        }
        NextCourseWidget.updateAll(ctx)
    }

    /**
     * Alarm jam buka yang pernah dipasang tapi tidak pernah berbunyi (HP mati / NgiBsen ditahan sistem).
     * Diberitahukan sekali per kemunculan. Mengembalikan semua yang terlewat (untuk layar Hari ini).
     */
    suspend fun checkMissedAlarms(context: Context): List<String> {
        val ctx = context.applicationContext
        val store = Graph.settings
        val courses = Graph.repository.allCourses().filter { it.active }
        val slots = courses.associate { it.id to it.toSlot() }
        val names = courses.associate { it.id to it.name }
        val dao = Graph.db.recordDao()
        val armed = store.armed()
        val withRecord = armed.filter { dao.find(it.courseId, it.epochDay) != null }.toSet()
        val missed = com.pengingatabsen.logic.Readiness.missedAlarms(armed, slots, { it in withRecord }, LocalDateTime.now())
        return missed.map { (a, occ) ->
            val text = "${names[a.courseId]} ${Formatters.hm(occ.open)}"
            if (store.claimMissedReport(a.courseId, a.epochDay)) {
                DiagLog.add("ALARM TERLEWAT: $text (tidak pernah berbunyi)")
                Notifications.showMissedAlarm(ctx, text)
            }
            text
        }
    }

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
            captureProof = true,
            extractScript = com.pengingatabsen.launch.SiadinScripts.CARD_TEXTS_SCRIPT,
        ) { DiagLog.add("cek ${course.name}: $it") }
        val state = result.state
        // Kehadiran resmi: angka SEBELUM pengecekan ini diingat untuk memastikan presensi tercatat.
        val officialBefore = store.officialSnapshots()[course.name]?.percent
        val official = com.pengingatabsen.data.OfficialSync.absorb(ctx, com.pengingatabsen.data.OfficialSync.cardTexts(result.extracted))
        if (state == PresensiState.DONE) {
            com.pengingatabsen.data.OfficialSync.afterCheckerDone(ctx, course.name, officialBefore, official[course.name])
        }
        val rxAfter = android.net.TrafficStats.getUidRxBytes(uid)
        val txAfter = android.net.TrafficStats.getUidTxBytes(uid)
        if (rxBefore >= 0 && rxAfter >= rxBefore) {
            store.addCheckBytes((rxAfter - rxBefore) + (txAfter - txBefore).coerceAtLeast(0))
        }
        // Catatan diagnosis (tampil di Pengaturan): kapan cek terakhir, matkul apa, hasilnya apa.
        store.setLastCheck("${Formatters.hm(LocalDateTime.now())} · ${course.name} · ${describe(state)}")
        DiagLog.add("HASIL ${course.name}: ${describe(state)} — ${result.detail}")
        SiadinHealth.record(ctx, courseId, epochDay, result)

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
                // Bukti foto otomatis (kartu hijau dipotret pengecek) bila ada; kalau tidak, bukti teks.
                Graph.repository.confirmDone(
                    record.copy(photoPath = result.photoPath ?: record.photoPath),
                    LocalDateTime.now(),
                    verifyOfficial = false,
                )
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
