package com.pengingatabsen.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.pengingatabsen.Graph
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.data.toLocalDateTime
import com.pengingatabsen.data.toMillis
import com.pengingatabsen.logic.OccurrenceState
import com.pengingatabsen.logic.PlannedAlarm
import com.pengingatabsen.logic.ScheduleMath
import com.pengingatabsen.telegram.SummaryWorker
import com.pengingatabsen.ui.MainActivity
import com.pengingatabsen.widget.NextCourseWidget
import java.time.LocalDateTime

/**
 * Satu alarm aktif per matkul. Setelah alarm berbunyi, receiver memanggil
 * [reschedule] lagi sehingga rantai OPEN → REMIND → FINAL → EXPIRE → OPEN minggu depan berlanjut.
 */
object AlarmScheduler {
    const val ACTION_ALARM = "com.pengingatabsen.ALARM"
    const val ACTION_PREFLIGHT = "com.pengingatabsen.PREFLIGHT"
    const val EXTRA_COURSE_ID = "course_id"
    const val EXTRA_EPOCH_DAY = "epoch_day"
    const val EXTRA_TYPE = "type"

    private fun isMeteredNetwork(context: Context): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /** Hitung & pasang alarm berikutnya untuk satu matkul. */
    suspend fun reschedule(context: Context, courseId: Long, now: LocalDateTime = LocalDateTime.now()) {
        val course = Graph.repository.course(courseId)
        if (course == null || !course.active) {
            cancel(context, courseId)
            Notifications.cancel(context, courseId)
            return
        }
        val settings = Graph.settings.current()
        // Mode pintar: jendela diperpanjang setelah jam tutup karena dosen sering membuka terlambat.
        val slot = course.toSlot(if (settings.smartModeActive) ScheduleMath.SMART_GRACE_MINUTES else 0)
        // Mode pintar: selama presensi belum terlihat dibuka, cek SiAdin tiap 1 menit (senyap).
        val current = ScheduleMath.currentOccurrence(slot, now)
        val waitingForSession = settings.smartModeActive && current != null &&
            !Graph.settings.isPresensiOpen(courseId, current.date.toEpochDay())
        val interval = if (waitingForSession) {
            // Hemat kuota: di data seluler cek tiap 2 menit, dipercepat menjelang jam tutup.
            val minutesToEnd = current?.let { java.time.Duration.between(now, it.end).toMinutes() }
            ScheduleMath.smartCheckInterval(isMeteredNetwork(context), minutesToEnd)
        } else {
            settings.remindIntervalMinutes
        }
        val records = Graph.db.recordDao()
            .forCourseSince(courseId, now.toLocalDate().minusDays(2).toEpochDay())
            .associateBy { it.epochDay }
        val planned = ScheduleMath.plan(slot, now, interval) { date ->
            records[date.toEpochDay()]?.let {
                OccurrenceState(it.status.finished, it.snoozeUntilMillis?.toLocalDateTime())
            }
        }
        if (planned == null) {
            // Tidak ada lagi yang perlu diingatkan (kelas pengganti yang sudah lewat/selesai).
            cancel(context, courseId)
            return
        }
        val exact = set(context, courseId, planned)
        // Ingat alarm jam buka yang sudah dipasang, untuk mendeteksi alarm yang tidak pernah berbunyi.
        if (planned.event.type == com.pengingatabsen.logic.EventType.OPEN) {
            Graph.settings.markArmed(courseId, planned.occurrence.date.toEpochDay())
        }
        // Hanya catat alarm hari ini (cukup untuk diagnosis, log tidak penuh oleh jadwal minggu depan).
        if (planned.occurrence.date == now.toLocalDate()) {
            DiagLog.add(
                "jadwal: ${course.name} ${planned.event.type.name} pukul ${com.pengingatabsen.logic.Formatters.hms(planned.event.time)}" +
                    if (exact) "" else " (TIDAK tepat waktu: izin alarm tepat belum ada)",
            )
        }
    }

    /** Dipanggil saat boot, update aplikasi, perubahan jam/zona waktu, atau aplikasi dibuka. */
    suspend fun rescheduleAll(context: Context) {
        expireStale(context)
        Graph.repository.cleanupOldOneOffs()
        for (course in Graph.repository.allCourses()) reschedule(context, course.id)
        NextCourseWidget.updateAll(context)
        SummaryWorker.schedule(context, Graph.settings.current().weeklySummary)
        schedulePreflight(context)
    }

    /**
     * Pasang alarm "cek kesiapan" ±30 menit sebelum matkul pertama hari berikutnya yang ada kuliah
     * (hanya mode pintar & bila diaktifkan). Dipasang ulang tiap kali jadwal dihitung ulang dan setelah berjalan.
     */
    suspend fun schedulePreflight(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val settings = Graph.settings.current()
        val pi = preflightIntent(context)
        if (!settings.smartModeActive || !settings.readinessCheck) {
            alarmManager(context).cancel(pi)
            return
        }
        val slots = Graph.repository.allCourses().filter { it.active }.map { it.toSlot() }
        val at = com.pengingatabsen.logic.Readiness.nextPreflight(slots, now)
        if (at == null) {
            alarmManager(context).cancel(pi)
            return
        }
        val am = alarmManager(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toMillis(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toMillis(), pi)
        }
    }

    private fun preflightIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PreflightReceiver::class.java).setAction(ACTION_PREFLIGHT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Jendela yang sudah lewat tapi masih ACTIVE (mis. HP mati) dicatat sebagai terlewat. */
    private suspend fun expireStale(context: Context) {
        val now = System.currentTimeMillis()
        for (record in Graph.db.recordDao().active()) {
            if (record.endAtMillis <= now) {
                Graph.repository.markMissed(record)
                Notifications.cancel(context, record.courseId)
            }
        }
    }

    fun cancel(context: Context, courseId: Long) {
        alarmManager(context).cancel(pendingIntent(context, courseId, null))
    }

    /** Pasang alarm; true bila alarm tepat waktu (setAlarmClock). */
    private fun set(context: Context, courseId: Long, planned: PlannedAlarm): Boolean {
        val pi = pendingIntent(context, courseId, planned)
        val at = planned.event.time.toMillis()
        val am = alarmManager(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            // setAlarmClock: paling tepat waktu & tidak dibatasi Doze.
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            // Tanpa izin exact alarm: tetap berbunyi, tapi bisa terlambat beberapa menit.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        return canExact
    }

    private fun pendingIntent(context: Context, courseId: Long, planned: PlannedAlarm?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            // Data unik per matkul supaya PendingIntent tidak tertukar.
            data = Uri.parse("pengingatabsen://alarm/$courseId")
            putExtra(EXTRA_COURSE_ID, courseId)
            if (planned != null) {
                putExtra(EXTRA_EPOCH_DAY, planned.occurrence.date.toEpochDay())
                putExtra(EXTRA_TYPE, planned.event.type.name)
            }
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)
}
