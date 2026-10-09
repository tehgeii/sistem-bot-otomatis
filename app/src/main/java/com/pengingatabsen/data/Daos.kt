package com.pengingatabsen.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY dayOfWeek, openMinute, name")
    fun observeAll(): Flow<List<Course>>

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, openMinute, name")
    suspend fun getAll(): List<Course>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun get(id: Long): Course?

    @Insert
    suspend fun insert(course: Course): Long

    @Update
    suspend fun update(course: Course)

    @Delete
    suspend fun delete(course: Course)

    /** Hanya untuk pulihkan cadangan (di dalam transaksi). */
    @Query("DELETE FROM courses")
    suspend fun deleteAll()
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records ORDER BY openAtMillis DESC, id DESC LIMIT 300")
    fun observeHistory(): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: Long): AttendanceRecord?

    @Query("SELECT * FROM records WHERE courseId = :courseId AND epochDay = :epochDay")
    suspend fun find(courseId: Long, epochDay: Long): AttendanceRecord?

    @Query("SELECT * FROM records WHERE courseId = :courseId AND epochDay >= :fromEpochDay")
    suspend fun forCourseSince(courseId: Long, fromEpochDay: Long): List<AttendanceRecord>

    @Query("SELECT * FROM records WHERE epochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY epochDay")
    suspend fun between(fromEpochDay: Long, toEpochDay: Long): List<AttendanceRecord>

    @Query("SELECT * FROM records WHERE status = 'ACTIVE' ORDER BY openAtMillis DESC")
    suspend fun active(): List<AttendanceRecord>

    @Query("SELECT * FROM records WHERE doneAtMillis IS NOT NULL ORDER BY doneAtMillis DESC LIMIT 1")
    suspend fun lastDone(): AttendanceRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: AttendanceRecord): Long

    @Upsert
    suspend fun upsert(record: AttendanceRecord): Long

    @Update
    suspend fun update(record: AttendanceRecord)

    @Query("DELETE FROM records WHERE courseId = :courseId AND epochDay = :epochDay AND status = 'HOLIDAY'")
    suspend fun deleteHoliday(courseId: Long, epochDay: Long)

    /** Semua riwayat (untuk cadangan & ekspor), urut tanggal. */
    @Query("SELECT * FROM records ORDER BY epochDay, openAtMillis, id")
    suspend fun all(): List<AttendanceRecord>

    /** Hanya untuk pulihkan cadangan (di dalam transaksi). */
    @Query("DELETE FROM records")
    suspend fun deleteAll()
}
