package com.pengingatabsen.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.pengingatabsen.Graph
import com.pengingatabsen.R
import com.pengingatabsen.alarm.runAsync
import com.pengingatabsen.data.toLocalDateTime
import com.pengingatabsen.launch.LaunchTargetActivity
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.logic.OccurrenceState
import com.pengingatabsen.logic.ScheduleMath
import java.time.LocalDateTime

/** Widget: matkul berikutnya + jam absennya. Tap = buka Dinusverse. */
class NextCourseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runAsync { updateAll(context) }
    }

    companion object {
        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextCourseWidget::class.java))
            if (ids.isEmpty()) return
            val (title, detail) = describeNext()
            val views = RemoteViews(context.packageName, R.layout.widget_next).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_detail, detail)
                val open = PendingIntent.getActivity(
                    context, 0, LaunchTargetActivity.intent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }

        private suspend fun describeNext(): Pair<String, String> {
            val now = LocalDateTime.now()
            val interval = Graph.settings.current().remindIntervalMinutes
            val dao = Graph.db.recordDao()
            val next = Graph.repository.allCourses().filter { it.active }.map { course ->
                val records = dao.forCourseSince(course.id, now.toLocalDate().minusDays(2).toEpochDay())
                    .associateBy { it.epochDay }
                val plan = ScheduleMath.plan(course.toSlot(), now, interval) { date ->
                    records[date.toEpochDay()]?.let {
                        OccurrenceState(it.status.finished, it.snoozeUntilMillis?.toLocalDateTime())
                    }
                }
                course to plan.occurrence
            }.minByOrNull { it.second.open } ?: return "Belum ada jadwal" to "Tap untuk membuka Dinusverse"

            val (course, occ) = next
            if (!occ.open.isAfter(now)) {
                return "Sekarang: ${course.name}" to "Absen dibuka s/d ${Formatters.hm(occ.end)}"
            }
            val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), occ.date)
            val day = when (days) {
                0L -> "Hari ini"
                1L -> "Besok"
                else -> Formatters.dayName(occ.date.dayOfWeek.value)
            }
            val room = course.room?.let { " · $it" }.orEmpty()
            return course.name to "$day ${Formatters.hm(occ.open)}$room"
        }
    }
}
