package com.pengingatabsen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengingatabsen.Graph
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.data.toMillis
import com.pengingatabsen.widget.NextCourseWidget
import java.time.LocalDate
import java.time.LocalDateTime

/** Tombol-tombol di notifikasi. Semua cukup satu tap, tanpa membuka aplikasi. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getLongExtra(AlarmScheduler.EXTRA_COURSE_ID, Long.MIN_VALUE)
        val epochDay = intent.getLongExtra(AlarmScheduler.EXTRA_EPOCH_DAY, 0L)
        val action = intent.action ?: return

        if (action == ACTION_RESEND) {
            val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1)
            runAsync { Graph.repository.resend(recordId) }
            return
        }
        if (courseId == Notifications.TEST_COURSE_ID) {
            Notifications.cancel(context, courseId)
            return
        }
        if (courseId == Long.MIN_VALUE) return

        runAsync {
            val repo = Graph.repository
            val course = repo.course(courseId) ?: run { Notifications.cancel(context, courseId); return@runAsync }
            val date = LocalDate.ofEpochDay(epochDay)
            val dao = Graph.db.recordDao()
            val record = dao.find(courseId, epochDay) ?: repo.markOccurrence(course, date, RecordStatus.ACTIVE)
            val now = LocalDateTime.now()

            when (action) {
                ACTION_SNOOZE -> {
                    Notifications.cancel(context, courseId)
                    if (record.status == RecordStatus.ACTIVE) {
                        dao.update(record.copy(snoozeUntilMillis = now.plusMinutes(5).toMillis(), awaitingConfirm = false))
                    }
                }
                ACTION_HOLIDAY -> {
                    Notifications.cancel(context, courseId)
                    repo.markOccurrence(course, date, RecordStatus.HOLIDAY)
                }
                ACTION_DONE -> {
                    Notifications.cancel(context, courseId)
                    // Waktu bukti = saat tombol ditekan, bukan saat terkirim.
                    repo.confirmDone(record, now)
                }
                ACTION_NOT_YET -> {
                    if (record.status == RecordStatus.ACTIVE) {
                        val updated = record.copy(awaitingConfirm = false)
                        dao.update(updated)
                        Notifications.showReminder(context, course, updated, final = false, silent = true)
                    } else {
                        Notifications.cancel(context, courseId)
                    }
                }
            }
            AlarmScheduler.reschedule(context, courseId)
            NextCourseWidget.updateAll(context)
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.pengingatabsen.SNOOZE"
        const val ACTION_HOLIDAY = "com.pengingatabsen.HOLIDAY"
        const val ACTION_DONE = "com.pengingatabsen.DONE"
        const val ACTION_NOT_YET = "com.pengingatabsen.NOT_YET"
        const val ACTION_RESEND = "com.pengingatabsen.RESEND"
        const val EXTRA_RECORD_ID = "record_id"
    }
}
