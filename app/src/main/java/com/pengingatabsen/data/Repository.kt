package com.pengingatabsen.data

import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.AlarmScheduler
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.logic.CourseData
import com.pengingatabsen.logic.ScheduleCodec
import com.pengingatabsen.logic.ScheduleMath
import com.pengingatabsen.telegram.SendWorker
import com.pengingatabsen.widget.NextCourseWidget
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

    /** Jadwal sebagai teks untuk dibagikan/cadangan. */
    suspend fun exportSchedule(): String =
        ScheduleCodec.encode(
            courseDao.getAll().map {
                CourseData(it.name, it.dayOfWeek, it.openMinute, it.closeMinute, it.room, it.active)
            },
        )

    /**
     * Impor jadwal dari teks. Entri yang sama persis (nama+hari+jam buka) dengan yang sudah ada
     * dilewati, supaya impor berulang tidak menduplikasi. Mengembalikan jumlah matkul yang ditambah.
     */
    suspend fun importSchedule(text: String): Int {
        val incoming = ScheduleCodec.decode(text)
        if (incoming.isEmpty()) return 0
        val existing = courseDao.getAll()
            .map { Triple(it.name.trim().lowercase(), it.dayOfWeek, it.openMinute) }.toHashSet()
        var added = 0
        for (c in incoming) {
            val key = Triple(c.name.trim().lowercase(), c.dayOfWeek, c.openMinute)
            if (!existing.add(key)) continue
            saveCourse(Course(name = c.name, dayOfWeek = c.dayOfWeek, openMinute = c.openMinute, closeMinute = c.closeMinute, room = c.room, active = c.active))
            added++
        }
        return added
    }

    /** Jadwal yang sekarang ada, dalam bentuk [CourseData]. */
    private suspend fun currentData(): List<CourseData> =
        courseDao.getAll().map { CourseData(it.name, it.dayOfWeek, it.openMinute, it.closeMinute, it.room, it.active) }

    /** Jadwal hasil baca KRS yang BELUM ada (lihat [com.pengingatabsen.logic.KrsParser.alreadyExists]). */
    suspend fun newFromKrs(fromKrs: List<CourseData>): List<CourseData> {
        val existing = currentData()
        return fromKrs.filterNot { com.pengingatabsen.logic.KrsParser.alreadyExists(it, existing) }
    }

    /**
     * Simpan jadwal hasil baca KRS. [replaceAll] = hapus semua jadwal lama dulu (semester baru; riwayat tetap
     * tersimpan), selain itu hanya menambah yang belum ada. Mengembalikan jumlah jadwal yang ditambah.
     */
    suspend fun importFromKrs(fromKrs: List<CourseData>, replaceAll: Boolean): Int {
        if (replaceAll) courseDao.getAll().forEach { deleteCourse(it) }
        val toAdd = if (replaceAll) fromKrs.distinct() else newFromKrs(fromKrs)
        toAdd.forEach {
            saveCourse(Course(name = it.name, dayOfWeek = it.dayOfWeek, openMinute = it.openMinute, closeMinute = it.closeMinute, room = it.room))
        }
        return toAdd.size
    }

    /** Berapa matkul yang akan ditambah bila teks ini diimpor (untuk pratinjau). */
    suspend fun previewImport(text: String): Int {
        val incoming = ScheduleCodec.decode(text)
        val existing = courseDao.getAll()
            .map { Triple(it.name.trim().lowercase(), it.dayOfWeek, it.openMinute) }.toHashSet()
        return incoming.count { existing.add(Triple(it.name.trim().lowercase(), it.dayOfWeek, it.openMinute)) }
    }

    /** Liburkan kemunculan hari ini (hanya bila matkul memang ada hari ini). */
    suspend fun holidayToday(course: Course, now: LocalDateTime = LocalDateTime.now()) {
        val today = now.toLocalDate()
        if (course.dayOfWeek != today.dayOfWeek.value) return
        applySkip(course, today, listOf(today))
    }

    /** Lewati kemunculan minggu ini (Senin–Minggu) yang belum lewat. */
    suspend fun skipThisWeek(course: Course, now: LocalDateTime = LocalDateTime.now()) {
        val today = now.toLocalDate()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val sunday = monday.plusDays(6)
        val date = monday.plusDays((course.dayOfWeek - 1).toLong())
        applySkip(course, sunday, if (date.isBefore(today)) emptyList() else listOf(date))
    }

    /**
     * Libur massal (UTS/UAS/libur semester): semua matkul dilewati sampai [until] (inklusif).
     * Memakai mesin libur per-matkul yang sama; kemunculan hari ini (bila ada) dicatat Libur
     * supaya pengingat yang sedang tampil langsung berhenti.
     */
    suspend fun pauseAll(until: LocalDate, now: LocalDateTime = LocalDateTime.now()) {
        val today = now.toLocalDate()
        if (until.isBefore(today)) return
        for (course in courseDao.getAll()) {
            val dates = if (course.dayOfWeek == today.dayOfWeek.value) listOf(today) else emptyList()
            applySkip(course, until, dates)
        }
    }

    /** Batalkan semua libur (per matkul maupun massal) yang belum lewat. */
    suspend fun resumeAll() {
        for (course in courseDao.getAll()) if (course.skipUntilEpochDay != null) clearSkip(course)
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

    private suspend fun applySkip(course: Course, until: LocalDate, holidayDates: List<LocalDate>) {
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
            if (existing.status != RecordStatus.ACTIVE || status == RecordStatus.ACTIVE) return existing
            val updated = existing.copy(status = status, awaitingConfirm = false)
            recordDao.update(updated)
            return updated
        }
        val grace = if (Graph.settings.current().smartModeActive) ScheduleMath.SMART_GRACE_MINUTES else 0
        val occ = ScheduleMath.occurrenceOn(course.toSlot(grace), date)
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

    /** Jendela berakhir tanpa konfirmasi: catat terlewat dan kabari Telegram. */
    suspend fun markMissed(record: AttendanceRecord) {
        if (record.status != RecordStatus.ACTIVE) return
        recordDao.update(record.copy(status = RecordStatus.MISSED, awaitingConfirm = false))
        SendWorker.enqueue(Graph.appContext, record.id, SendWorker.KIND_MISSED)
    }

    /** Jendela berakhir tapi dosen tidak pernah membuka presensi: bukan salah pengguna, tanpa Telegram. */
    suspend fun markNoSession(record: AttendanceRecord) {
        if (record.status != RecordStatus.ACTIVE) return
        recordDao.update(record.copy(status = RecordStatus.NO_SESSION, awaitingConfirm = false))
    }

    /** "Sudah, kirim bukti": waktu bukti = [pressedAt], lalu antre ke Telegram. */
    suspend fun confirmDone(record: AttendanceRecord, pressedAt: LocalDateTime) {
        if (record.status == RecordStatus.SENT || record.status == RecordStatus.QUEUED) return
        recordDao.update(
            record.copy(
                status = RecordStatus.QUEUED,
                doneAtMillis = pressedAt.toMillis(),
                awaitingConfirm = false,
                error = null,
            ),
        )
        SendWorker.enqueue(Graph.appContext, record.id, SendWorker.KIND_PROOF)
    }

    /**
     * "Sudah, kirim bukti" dari browser mini. Pakai kemunculan [courseId]/[epochDay] bila ada,
     * kalau tidak absen yang sedang dibuka. Null bila tidak ada absen yang bisa dikonfirmasi.
     */
    suspend fun confirmFromBrowser(courseId: Long, epochDay: Long, pressedAt: LocalDateTime): AttendanceRecord? {
        val record = (if (courseId > 0) recordDao.find(courseId, epochDay) else null)
            ?.takeIf { it.status == RecordStatus.ACTIVE }
            ?: recordDao.active().firstOrNull()
            ?: return null
        confirmDone(record, pressedAt)
        Notifications.cancel(Graph.appContext, record.courseId)
        AlarmScheduler.reschedule(Graph.appContext, record.courseId)
        NextCourseWidget.updateAll(Graph.appContext)
        return record
    }

    /** Kirim ulang bukti yang gagal (dari Riwayat atau notifikasi gagal). */
    suspend fun resend(recordId: Long) {
        val record = recordDao.get(recordId) ?: return
        if (record.doneAtMillis == null) return
        Notifications.cancelId(Graph.appContext, SendWorker.failureNotificationId(recordId))
        recordDao.update(record.copy(status = RecordStatus.QUEUED, error = null))
        SendWorker.enqueue(Graph.appContext, record.id, SendWorker.KIND_PROOF)
    }

    /**
     * Screenshot dibagikan ke aplikasi: kaitkan ke matkul yang sedang aktif,
     * atau ke absen yang baru saja dikonfirmasi (maks. 2 jam lalu).
     * Mengembalikan baris riwayat yang akan dikirim.
     */
    suspend fun attachPhoto(photoPath: String, now: LocalDateTime): AttendanceRecord {
        val nowMillis = now.toMillis()
        val active = recordDao.active()
        val target = active.firstOrNull { nowMillis in it.openAtMillis until it.endAtMillis }
            ?: active.firstOrNull()
        val record = when {
            target != null -> target.copy(doneAtMillis = nowMillis, awaitingConfirm = false)
            else -> recordDao.lastDone()?.takeIf { nowMillis - (it.doneAtMillis ?: 0) <= 2 * 60 * 60 * 1000L }
                ?: nearestCourseToday(now)?.let { markOccurrence(it, now.toLocalDate(), RecordStatus.QUEUED) }
                    ?.copy(doneAtMillis = nowMillis)
                ?: AttendanceRecord(
                    courseId = 0,
                    courseName = "Tanpa matkul",
                    // Tidak terkait jadwal: pakai nilai negatif unik supaya tidak bentrok.
                    epochDay = -nowMillis,
                    openAtMillis = nowMillis,
                    endAtMillis = nowMillis,
                    status = RecordStatus.QUEUED,
                    doneAtMillis = nowMillis,
                ).let { it.copy(id = recordDao.insert(it)) }
        }
        val updated = record.copy(status = RecordStatus.QUEUED, photoPath = photoPath, error = null)
        recordDao.update(updated)
        if (target != null) {
            Notifications.cancel(Graph.appContext, target.courseId)
            AlarmScheduler.reschedule(Graph.appContext, target.courseId)
        }
        SendWorker.enqueue(Graph.appContext, updated.id, SendWorker.KIND_PROOF)
        return updated
    }

    /** Matkul aktif hari ini yang jam bukanya paling dekat dengan [now]. */
    private suspend fun nearestCourseToday(now: LocalDateTime): Course? {
        val minute = now.hour * 60 + now.minute
        return courseDao.getAll()
            .filter { it.active && it.dayOfWeek == now.dayOfWeek.value }
            .minByOrNull { kotlin.math.abs(it.openMinute - minute) }
    }

    /** Dipanggil setiap jadwal berubah. */
    private suspend fun onScheduleChanged(courseId: Long) {
        val context = Graph.appContext
        AlarmScheduler.reschedule(context, courseId)
        // Notifikasi yang sedang tampil untuk kemunculan yang kini libur/nonaktif dibersihkan.
        val course = courseDao.get(courseId)
        val today = LocalDate.now()
        if (course == null || !course.active || ScheduleMath.isSkipped(course.toSlot(), today)) {
            recordDao.active().filter { it.courseId == courseId }.forEach {
                if (course == null || !course.active) recordDao.update(it.copy(status = RecordStatus.HOLIDAY))
            }
            Notifications.cancel(context, courseId)
        }
        NextCourseWidget.updateAll(context)
    }
}

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(this), ZoneId.systemDefault())
