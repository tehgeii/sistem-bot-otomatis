package com.pengingatabsen.widget

import com.pengingatabsen.Graph
import com.pengingatabsen.logic.ScheduleMath
import com.pengingatabsen.logic.TodayCourse
import com.pengingatabsen.logic.TodayItem
import com.pengingatabsen.logic.TodayPlan
import com.pengingatabsen.logic.TodayState
import com.pengingatabsen.logic.TodayView
import java.time.LocalDateTime

/** Isi "Hari ini" dari database — satu sumber untuk widget dan tile Quick Settings (logika di [TodayPlan]). */
object TodayData {
    data class Loaded(val view: TodayView, val smart: Boolean) {
        /** Fokus: yang sedang dibuka > menunggu > berikutnya hari ini. */
        val focus: TodayItem?
            get() = view.items.firstOrNull { it.state == TodayState.OPEN }
                ?: view.items.firstOrNull { it.state == TodayState.WAITING }
                ?: view.items.firstOrNull { it.state == TodayState.UPCOMING }
    }

    /** Null bila belum ada jadwal aktif. */
    suspend fun load(now: LocalDateTime = LocalDateTime.now()): Loaded? {
        val settings = Graph.settings.current()
        val courses = Graph.repository.allCourses().filter { it.active }
        if (courses.isEmpty()) return null
        val today = now.toLocalDate().toEpochDay()
        val records = Graph.db.recordDao().between(today, today).associateBy { it.courseId }
        val view = TodayPlan.build(
            courses = courses.map { TodayCourse(it.id, it.name, it.room, it.toSlot()) },
            now = now,
            record = { id, day -> if (day == today) records[id]?.status?.toSummaryKind() else null },
            presensiOpen = { id, day -> "$id:$day" in settings.presensiOpenKeys },
            graceMinutes = if (settings.smartModeActive) ScheduleMath.SMART_GRACE_MINUTES else 0,
        )
        return Loaded(view, settings.smartModeActive)
    }

    /**
     * Matkul yang boleh diteruskan ke "Absen sekarang" (catatan & pengingat lanjutan): hanya yang jam kuliahnya
     * sedang berjalan. Sebelum jam buka cukup buka halaman presensi tanpa mencatat apa pun (id 0).
     */
    fun launchTarget(item: TodayItem?): Pair<Long, Long> =
        if (item != null && (item.state == TodayState.WAITING || item.state == TodayState.OPEN)) item.courseId to item.epochDay
        else 0L to 0L
}
