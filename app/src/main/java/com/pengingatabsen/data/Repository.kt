package com.pengingatabsen.data

import com.pengingatabsen.logic.ScheduleMath
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Satu-satunya pintu ke database untuk UI dan receiver. */
class Repository(private val db: AppDatabase) {
    private val courseDao get() = db.courseDao()
    private val recordDao get() = db.recordDao()

    val courses = courseDao.observeAll()
    val history = recordDao.observeHistory()

    suspend fun allCourses() = courseDao.getAll()
    suspend fun course(id: Long) = courseDao.get(id)

    suspend fun saveCourse(course: Course): Long {
        val id = if (course.id == 0L) courseDao.insert(course) else course.id.also { courseDao.update(course) }
        onScheduleChanged(id)
        return id
    }

    suspend fun deleteCourse(course: Course) {
        courseDao.delete(course)
        onScheduleChanged(course.id)
    }

    suspend fun setActive(course: Course, active: Boolean) = saveCourse(course.copy(active = active))

    /** Liburkan kemunculan hari ini (hanya bila matkul memang ada hari ini). */
    suspend fun holidayToday(course: Course, now: LocalDateTime = LocalDateTime.now()) {
        val today = now.toLocalDate()
        if (course.dayOfWeek != today.dayOfWeek.value) return
        skipUntil(course, today, listOf(today))
    }

    /** Lewati kemunculan minggu ini (Senin–Minggu) yang belum lewat. */
    suspend fun skipThisWeek(course: Course, now: LocalDateTime = LocalDateTime.now()) {
        val today = now.toLocalDate()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val sunday = monday.plusDays(6)
        val date = monday.plusDays((course.dayOfWeek - 1).toLong())
        skipUntil(course, sunday, if (date.isBefore(today)) emptyList() else listOf(date))
    }

    suspend fun clearSkip(course: Course) {
        val today = LocalDate.now()
        course.skipUntil?.let { until ->
            var d = today
            while (!d.isAfter(until)) {
                recordDao.deleteHoliday(course.id, d.toEpochDay())
                d = d.plusDays(1)
            }
        }
        saveCourse(course.copy(skipUntilEpochDay = null))
    }

    private suspend fun skipUntil(course: Course, until: LocalDate, holidayDates: List<LocalDate>) {
        val newUntil = maxOf(until, course.skipUntil ?: until)
        for (date in holidayDates) markOccurrence(course, date, RecordStatus.HOLIDAY)
        saveCourse(course.copy(skipUntilEpochDay = newUntil.toEpochDay()))
    }

    /**
     * Ambil (atau buat) baris riwayat untuk kemunculan [date].
     * Baris yang sudah final (terkirim, dll.) tidak ditimpa kecuali masih ACTIVE.
     */
    suspend fun markOccurrence(course: Course, date: LocalDate, status: RecordStatus): AttendanceRecord {
        val existing = recordDao.find(course.id, date.toEpochDay())
        if (existing != null) {
            if (existing.status != RecordStatus.ACTIVE) return existing
            val updated = existing.copy(status = status, awaitingConfirm = false)
            recordDao.update(updated)
            return updated
        }
        val occ = ScheduleMath.occurrenceOn(course.toSlot(), date)
        val record = AttendanceRecord(
            courseId = course.id,
            courseName = course.name,
            epochDay = date.toEpochDay(),
            openAtMillis = occ.open.toMillis(),
            endAtMillis = occ.end.toMillis(),
            status = status,
        )
        return record.copy(id = recordDao.insert(record))
    }

    /** Dipanggil setiap jadwal berubah. */
    private suspend fun onScheduleChanged(courseId: Long) {
    }
}

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(this), ZoneId.systemDefault())
