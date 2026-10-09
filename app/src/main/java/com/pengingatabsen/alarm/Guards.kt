package com.pengingatabsen.alarm

import android.content.Context
import com.pengingatabsen.Graph
import com.pengingatabsen.data.Course
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.data.toLocalDateTime
import com.pengingatabsen.launch.TargetApps
import com.pengingatabsen.logic.KrsParser
import com.pengingatabsen.logic.PhoneCheck
import com.pengingatabsen.logic.PreClass
import com.pengingatabsen.logic.PresensiNudge
import com.pengingatabsen.logic.Radar
import com.pengingatabsen.logic.RadarCourse
import com.pengingatabsen.logic.ScheduleDiff
import com.pengingatabsen.logic.ScheduleMath
import com.pengingatabsen.telegram.NoticeWorker
import java.time.LocalDateTime

/**
 * Penjaga supaya presensi tidak terlewat (3.3.2): pesan Telegram cadangan, mode senyap & baterai, pengingat sebelum
 * kuliah, radar presensi di luar jadwal, dan perubahan jadwal di KRS. Semuanya HANYA memberi tahu; keputusan ada di
 * logika murni (logic/PresensiGuard.kt, logic/ScheduleDiff.kt) yang teruji.
 */
object Guards {
    // ---------- Pesan Telegram cadangan ----------

    /** Presensi sudah terlihat dibuka ≥5 menit tapi belum ditekan → sekali kirim pesan ke Telegram. */
    suspend fun nudgeIfDue(context: Context, course: Course, epochDay: Long) {
        val store = Graph.settings
        val settings = store.current()
        if (!settings.telegramNudge || !settings.telegramReady) return
        val openedAt = store.presensiOpenedAt(course.id, epochDay) ?: return
        if (!PresensiNudge.due(openedAt, System.currentTimeMillis())) return
        if (Graph.db.recordDao().find(course.id, epochDay)?.status?.finished != false) return
        if (!store.claimNotice("senggol|${course.id}|$epochDay")) return
        DiagLog.add("Telegram cadangan: presensi ${course.name} dibuka ≥${PresensiNudge.AFTER_MINUTES} menit, belum ditekan")
        NoticeWorker.enqueue(
            context, "senggol-${course.id}-$epochDay",
            PresensiNudge.text(course.name, openedAt.toLocalDateTime().toLocalTime(), course.room),
        )
    }

    // ---------- Mode senyap, baterai, pengingat sebelum kuliah ----------

    /** Saat jendela kuliah dimulai: HP masih Senyap/Jangan Ganggu → beri tahu (sekali per kemunculan). */
    suspend fun quietAtStart(context: Context, course: Course, epochDay: Long) {
        val store = Graph.settings
        if (!store.current().quietCheck) return
        val issue = PhoneCheck.quietIssue(PhoneStatus.read(context)) ?: return
        if (!store.claimNotice("senyap|${course.id}|$epochDay")) return
        DiagLog.add("mode senyap saat ${course.name} dimulai: $issue")
        Notifications.showQuietWarning(context, course.name, issue)
    }

    /** Alarm "sebelum kuliah" untuk kuliah yang buka pada [openMillis]. */
    suspend fun preClass(context: Context, openMillis: Long) {
        val settings = Graph.settings.current()
        val now = LocalDateTime.now()
        val open = openMillis.toLocalDateTime()
        if (!now.isBefore(open)) {
            DiagLog.add("sebelum kuliah: alarm telat (kuliah sudah mulai), dilewati")
            return
        }
        val courses = Graph.repository.allCourses().filter { it.active }
        val ids = PreClass.startingAt(courses.map { it.id to it.toSlot() }, open)
        val starting = courses.filter { it.id in ids }
        if (starting.isEmpty()) return
        val phone = PhoneStatus.read(context)
        val quiet = if (settings.quietCheck) PhoneCheck.quietIssue(phone) else null
        val warnings = listOfNotNull(
            quiet?.let(PhoneCheck::quietText),
            if (settings.batteryCheck) PhoneCheck.batteryText(phone) else null,
        )
        DiagLog.add(
            "sebelum kuliah: ${starting.joinToString { it.name }} ${com.pengingatabsen.logic.Formatters.hm(open)}" +
                if (warnings.isEmpty()) "" else " · ${warnings.joinToString("; ")}",
        )
        if (!settings.preClassReminder && warnings.isEmpty()) return
        val title = if (settings.preClassReminder) {
            PreClass.title(starting.map { it.name }, open, now)
        } else {
            "⚠️ Sebelum ${starting.first().name} ${com.pengingatabsen.logic.Formatters.hm(open)}"
        }
        val rooms = starting.mapNotNull { c -> c.room?.takeIf { it.isNotBlank() }?.let { if (starting.size > 1) "${c.name}: $it" else "Ruang $it" } }
        val timeout = java.time.Duration.between(now, open).toMillis() + 60 * 60_000L
        Notifications.showPreClass(context, title, rooms.joinToString(" · ").ifBlank { null }, warnings, quiet, timeout)
    }

    // ---------- Radar presensi di luar jadwal ----------

    /** Periksa kartu-kartu halaman Presensi Online yang baru terbaca (pengecekan apa pun). */
    suspend fun radar(context: Context, cardTexts: List<String>, now: LocalDateTime = LocalDateTime.now()) {
        val store = Graph.settings
        val settings = store.current()
        if (!settings.radar || !settings.smartModeActive || cardTexts.isEmpty()) return
        val today = now.toLocalDate()
        val courses = Graph.repository.allCourses().filter { it.active }
        if (courses.isEmpty()) return
        val records = Graph.db.recordDao().between(today.toEpochDay(), today.toEpochDay())
        val done = records.filter { it.doneAtMillis != null || it.status == RecordStatus.HOLIDAY }.map { it.courseName }.toSet()
        val grace = ScheduleMath.SMART_GRACE_MINUTES
        val radarCourses = courses.groupBy { it.name }.map { (name, list) ->
            RadarCourse(
                name = name,
                scheduledToday = list.any { ScheduleMath.isOn(it.toSlot(), today) && !ScheduleMath.isSkipped(it.toSlot(), today) },
                inWindowNow = list.any { ScheduleMath.currentOccurrence(it.toSlot(grace), now) != null },
                doneToday = name in done,
            )
        }
        val findings = Radar.evaluate(cardTexts, radarCourses, today)
        if (findings.isEmpty()) return
        val url = settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL
        for (f in findings) {
            if (!store.claimNotice(f.key(today))) continue
            DiagLog.add("RADAR: ${f.kind} · ${f.courseName ?: f.cardTitle}")
            // Matkul jadwal yang cocok (utamakan yang dijadwalkan hari ini) untuk sorotan tombol & pencatatan bukti.
            val course = f.courseName?.let { n ->
                courses.filter { it.name == n }.maxByOrNull { if (ScheduleMath.isOn(it.toSlot(), today)) 1 else 0 }
            }
            Notifications.showRadar(context, f, course?.id, today.toEpochDay(), url)
            val tg = Radar.telegram(f)
            if (tg != null && settings.telegramNudge && settings.telegramReady) {
                NoticeWorker.enqueue(context, "radar-${f.key(today).hashCode()}", tg)
            }
        }
    }

    // ---------- Perubahan jadwal di KRS ----------

    /** Bandingkan kartu KRS yang baru terbaca dengan jadwal NgiBsen; perbedaan BARU diberitahukan sekali. */
    suspend fun checkScheduleChanges(context: Context, krsCardTexts: List<String>) {
        val store = Graph.settings
        if (!store.current().scheduleDiffCheck) return
        val krs = KrsParser.parse(krsCardTexts)
        // KRS tidak terbaca (halaman gagal dimuat): jangan menyimpulkan apa pun.
        if (krs.isEmpty()) return
        val changes = ScheduleDiff.compare(krs, Graph.repository.weeklyForDiff())
        val fresh = store.putScheduleChanges(changes)
        if (changes.isEmpty()) return
        DiagLog.add("jadwal KRS berbeda: " + changes.joinToString("; ") { it.describe() })
        if (fresh) Notifications.showScheduleChanges(context, changes.map { it.describe() })
    }
}
