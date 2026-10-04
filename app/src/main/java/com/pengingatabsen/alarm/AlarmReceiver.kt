package com.pengingatabsen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengingatabsen.Graph
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
        val slot = course.toSlot()
        if (!course.active || ScheduleMath.isSkipped(slot, date)) return

        val existing = Graph.db.recordDao().find(courseId, date.toEpochDay())
        if (existing != null && existing.status.finished) return

        // Alarm yang telat sampai melewati jam tutup diperlakukan sebagai EXPIRE.
        val occ = ScheduleMath.occurrenceOn(slot, date)
        val type = if (!LocalDateTime.now().isBefore(occ.end)) EventType.EXPIRE else planned

        val record = existing ?: repo.markOccurrence(course, date, RecordStatus.ACTIVE)
        when (type) {
            EventType.OPEN, EventType.REMIND ->
                if (record.awaitingConfirm) Notifications.showConfirm(context, course, record, silent = false)
                else Notifications.showReminder(context, course, record, final = false)
            EventType.FINAL -> Notifications.showReminder(context, course, record, final = true)
            EventType.EXPIRE -> {
                Notifications.cancel(context, courseId)
                repo.markMissed(record)
            }
        }
    }
}
