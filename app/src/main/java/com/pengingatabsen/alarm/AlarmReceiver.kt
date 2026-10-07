package com.pengingatabsen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengingatabsen.Graph
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.logic.EventType
import com.pengingatabsen.logic.ScheduleMath
import com.pengingatabsen.widget.NextCourseWidget
import java.time.LocalDate
import java.time.LocalDateTime

/** Menerima alarm jadwal: tampilkan notifikasi, catat terlewat, lalu pasang alarm berikutnya. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getLongExtra(AlarmScheduler.EXTRA_COURSE_ID, -1)
        val epochDay = intent.getLongExtra(AlarmScheduler.EXTRA_EPOCH_DAY, Long.MIN_VALUE)
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TYPE)
            ?.let { runCatching { EventType.valueOf(it) }.getOrNull() }
        if (courseId < 0 || epochDay == Long.MIN_VALUE || type == null) return
        runAsync {
            handle(context, courseId, LocalDate.ofEpochDay(epochDay), type)
            AlarmScheduler.reschedule(context, courseId)
            NextCourseWidget.updateAll(context)
        }
    }

    private suspend fun handle(context: Context, courseId: Long, date: LocalDate, planned: EventType) {
        val repo = Graph.repository
        val course = repo.course(courseId) ?: return
        val smartActive = Graph.settings.current().smartModeActive
        DiagLog.add("⏰ alarm ${planned.name} · ${course.name} · mode pintar ${if (smartActive) "AKTIF" else "mati"}")
        val slot = course.toSlot(if (smartActive) ScheduleMath.SMART_GRACE_MINUTES else 0)
        if (!course.active || ScheduleMath.isSkipped(slot, date)) {
            DiagLog.add("alarm diabaikan: matkul nonaktif/libur")
            return
        }

        val existing = Graph.db.recordDao().find(courseId, date.toEpochDay())
        if (existing != null && existing.status.finished) {
            DiagLog.add("alarm diabaikan: hari ini sudah ${existing.status.label}")
            return
        }

        // Alarm yang telat sampai melewati jam tutup diperlakukan sebagai EXPIRE.
        val occ = ScheduleMath.occurrenceOn(slot, date)
        val type = if (!LocalDateTime.now().isBefore(occ.end)) EventType.EXPIRE else planned

        var record = existing ?: repo.markOccurrence(course, date, RecordStatus.ACTIVE)
        // Mode pintar tidak memakai "Sudah absen?": sisa tanda lama tidak boleh menghentikan pengecekan SiAdin.
        if (smartActive && record.awaitingConfirm) {
            record = record.copy(awaitingConfirm = false)
            Graph.db.recordDao().update(record)
        }

        // Mode pintar (SiAdin web + login tersimpan): bergetar hanya bila presensi sudah dibuka dosen.
        // Setelah dibuka pun tetap cek SiAdin, supaya "Berhasil Presensi" (lewat Chrome/Dinusverse)
        // langsung menghentikan pengingat tanpa perlu menjawab "Sudah absen?".
        val store = Graph.settings
        val smart = smartActive
        val seenOpen = store.isPresensiOpen(courseId, date.toEpochDay())
        if (smart && type != EventType.EXPIRE && !record.awaitingConfirm) {
            PresensiCheck.start(context, courseId, date.toEpochDay(), type)
            return
        }

        if (type != EventType.EXPIRE) DiagLog.add("alarm ${type.name}: notifikasi jadwal (tanpa cek SiAdin)")
        // Hapus dulu notifikasi lama: memperbarui notifikasi yang sama sering tidak bergetar lagi.
        if (type != EventType.EXPIRE) Notifications.cancel(context, courseId)
        when (type) {
            EventType.OPEN, EventType.REMIND ->
                if (record.awaitingConfirm) Notifications.showConfirm(context, course, record, silent = false)
                else Notifications.showReminder(context, course, record, final = false, sessionOpen = smart && seenOpen)
            EventType.FINAL -> Notifications.showReminder(context, course, record, final = true)
            EventType.EXPIRE -> {
                Notifications.cancel(context, courseId)
                store.setPresensiUnknownStreak(courseId, date.toEpochDay(), 0)
                // Dosen tidak pernah membuka presensi: catat "tidak dibuka", tanpa pesan terlewat.
                DiagLog.add("jendela berakhir: " + if (smart && !seenOpen) "presensi tak pernah terlihat dibuka → \"tidak dibuka\"" else "belum presensi → TERLEWAT")
                if (smart && !seenOpen) repo.markNoSession(record) else repo.markMissed(record)
            }
        }
    }
}
