package com.pengingatabsen.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.pengingatabsen.Graph
import com.pengingatabsen.R
import com.pengingatabsen.alarm.runAsync
import com.pengingatabsen.launch.LaunchTargetActivity
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.logic.ScheduleMath
import com.pengingatabsen.logic.TodayCourse
import com.pengingatabsen.logic.TodayItem
import com.pengingatabsen.logic.TodayPlan
import com.pengingatabsen.logic.TodayState
import java.time.Duration
import java.time.LocalDateTime

/**
 * Widget: matkul hari ini (atau berikutnya) + status presensi + hitung mundur langsung ke jam buka.
 * Isi sama dengan layar "Hari ini" (logika [TodayPlan]). Tap = buka halaman presensi matkul itu.
 */
class NextCourseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runAsync { updateAll(context) }
    }

    /** Yang ditampilkan widget. [countdownTo] diisi bila hitung mundur ke jam buka perlu tampil. */
    private data class Display(
        val title: String,
        val detail: String,
        val countdownTo: LocalDateTime?,
        val courseId: Long,
        val epochDay: Long,
    )

    companion object {
        /** Hitung mundur hanya ditampilkan bila jam buka kurang dari sehari lagi (format jam:menit:detik). */
        private val COUNTDOWN_MAX: Duration = Duration.ofHours(24)

        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextCourseWidget::class.java))
            if (ids.isEmpty()) return
            val now = LocalDateTime.now()
            val d = describe(now)
            val views = RemoteViews(context.packageName, R.layout.widget_next).apply {
                setTextViewText(R.id.widget_title, d.title)
                setTextViewText(R.id.widget_detail, d.detail)
                val until = d.countdownTo?.let { Duration.between(now, it) }
                if (until != null && !until.isNegative && until <= COUNTDOWN_MAX) {
                    // Chronometer hitung mundur berjalan sendiri tanpa perlu memperbarui widget tiap detik.
                    setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime() + until.toMillis(), null, true)
                    setChronometerCountDown(R.id.widget_countdown, true)
                    setViewVisibility(R.id.widget_countdown_row, View.VISIBLE)
                } else {
                    setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime(), null, false)
                    setViewVisibility(R.id.widget_countdown_row, View.GONE)
                }
                val open = PendingIntent.getActivity(
                    context, 0, LaunchTargetActivity.intent(context, d.courseId, d.epochDay),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }

        private suspend fun describe(now: LocalDateTime): Display {
            val settings = Graph.settings.current()
            val courses = Graph.repository.allCourses().filter { it.active }
            if (courses.isEmpty()) return Display("Belum ada jadwal", "Tap untuk membuka NgiBsen", null, 0L, 0L)
            val today = now.toLocalDate().toEpochDay()
            val records = Graph.db.recordDao().between(today, today).associateBy { it.courseId }
            val view = TodayPlan.build(
                courses = courses.map { TodayCourse(it.id, it.name, it.room, it.toSlot()) },
                now = now,
                record = { id, day -> if (day == today) records[id]?.status?.toSummaryKind() else null },
                presensiOpen = { id, day -> "$id:$day" in settings.presensiOpenKeys },
                graceMinutes = if (settings.smartModeActive) ScheduleMath.SMART_GRACE_MINUTES else 0,
            )
            // Fokus: yang sedang dibuka > menunggu > berikutnya hari ini > matkul hari lain > yang terakhir hari ini.
            val focus = view.items.firstOrNull { it.state == TodayState.OPEN }
                ?: view.items.firstOrNull { it.state == TodayState.WAITING }
                ?: view.items.firstOrNull { it.state == TodayState.UPCOMING }
            if (focus != null) return display(focus, now, settings.smartModeActive)
            view.next?.let { next ->
                val day = if (next.open.toLocalDate() == now.toLocalDate().plusDays(1)) "Besok" else Formatters.dayName(next.open.dayOfWeek.value)
                val room = next.room?.let { " · $it" }.orEmpty()
                val doneToday = view.items.count { it.state == TodayState.DONE }
                val prefix = if (doneToday > 0) "✅ Hari ini $doneToday presensi beres · " else ""
                return Display(next.name, "$prefix$day ${Formatters.hm(next.open)}$room", next.open, next.courseId, next.epochDay)
            }
            return view.items.lastOrNull()?.let { display(it, now, settings.smartModeActive) }
                ?: Display("NgiBsen", "Tap untuk membuka", null, 0L, 0L)
        }

        private fun display(item: TodayItem, now: LocalDateTime, smart: Boolean): Display {
            val until = "s/d ${Formatters.hm(item.end)}"
            val detail = when (item.state) {
                TodayState.UPCOMING -> "🕒 Dibuka ${Formatters.hm(item.open)}" + (item.room?.let { " · $it" }.orEmpty())
                TodayState.WAITING -> if (smart) "⏳ Menunggu presensi dibuka · $until" else "⏰ Waktunya absen · $until"
                TodayState.OPEN -> "🔵 Presensi DIBUKA — tap! · $until"
                TodayState.DONE -> "✅ Sudah presensi"
                TodayState.FAILED -> "⚠️ Sudah presensi, bukti gagal terkirim"
                TodayState.HOLIDAY -> "🏖 Libur"
                TodayState.MISSED -> "❌ Terlewat"
                TodayState.NO_SESSION -> "⏸ Presensi tidak dibuka dosen"
                TodayState.ENDED -> "Selesai"
            }
            val countdown = if (item.state == TodayState.UPCOMING) item.open else null
            return Display(item.name, detail, countdown, item.courseId, item.epochDay)
        }
    }
}
