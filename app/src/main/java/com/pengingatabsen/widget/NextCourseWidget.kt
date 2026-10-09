package com.pengingatabsen.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.pengingatabsen.R
import com.pengingatabsen.alarm.runAsync
import com.pengingatabsen.launch.LaunchTargetActivity
import com.pengingatabsen.logic.Formatters
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
        /** Judul kecil di atas: "NgiBsen · Hari ini" / "NgiBsen · Berikutnya" (kuliah di hari lain). */
        val header: String,
        val title: String,
        val detail: String,
        val countdownTo: LocalDateTime?,
        val courseId: Long,
        val epochDay: Long,
    )

    companion object {
        /** Hitung mundur hanya ditampilkan bila jam buka kurang dari sehari lagi (format jam:menit:detik). */
        private val COUNTDOWN_MAX: Duration = Duration.ofHours(24)
        private const val HEADER_TODAY = "NgiBsen · Hari ini"
        private const val HEADER_NEXT = "NgiBsen · Berikutnya"
        private const val HEADER_PLAIN = "NgiBsen"

        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextCourseWidget::class.java))
            if (ids.isEmpty()) return
            val now = LocalDateTime.now()
            val d = describe(now)
            val views = RemoteViews(context.packageName, R.layout.widget_next).apply {
                setTextViewText(R.id.widget_label, d.header)
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
            val loaded = TodayData.load(now) ?: return Display(HEADER_PLAIN, "Belum ada jadwal", "Tap untuk membuka NgiBsen", null, 0L, 0L)
            val view = loaded.view
            // Fokus: yang sedang dibuka > menunggu > berikutnya hari ini > matkul hari lain > yang terakhir hari ini.
            loaded.focus?.let { return display(it, now, loaded.smart) }
            view.next?.let { next ->
                val day = if (next.open.toLocalDate() == now.toLocalDate().plusDays(1)) "Besok" else Formatters.dayName(next.open.dayOfWeek.value)
                val room = next.room?.let { " · $it" }.orEmpty()
                val doneToday = view.items.count { it.state == TodayState.DONE }
                val prefix = if (doneToday > 0) "✅ Hari ini $doneToday presensi beres · " else ""
                // Matkul hari lain: tap cukup membuka halaman presensi (tanpa mencatat apa pun).
                // Kuliah di hari lain: judulnya "Berikutnya" supaya tidak terbaca seperti jadwal hari ini.
                val header = if (next.open.toLocalDate() == now.toLocalDate()) HEADER_TODAY else HEADER_NEXT
                return Display(header, next.name, "$prefix$day ${Formatters.hm(next.open)}$room", next.open, 0L, 0L)
            }
            return view.items.lastOrNull()?.let { display(it, now, loaded.smart) }
                ?: Display(HEADER_PLAIN, "Tidak ada kuliah terjadwal", "Tap untuk membuka NgiBsen", null, 0L, 0L)
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
            val (courseId, epochDay) = TodayData.launchTarget(item)
            return Display(HEADER_TODAY, item.name, detail, countdown, courseId, epochDay)
        }
    }
}
