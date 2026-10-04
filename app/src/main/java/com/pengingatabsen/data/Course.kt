package com.pengingatabsen.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pengingatabsen.logic.Slot
import java.time.LocalDate

/** Satu mata kuliah yang berulang tiap minggu. */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 1 = Senin ... 7 = Minggu. */
    val dayOfWeek: Int,
    /** Jam absen dibuka, menit sejak 00:00. */
    val openMinute: Int,
    /** Jam absen ditutup (opsional), menit sejak 00:00. */
    val closeMinute: Int? = null,
    val room: String? = null,
    val active: Boolean = true,
    /** Libur: kemunculan sampai tanggal ini (epoch day) dilewati. */
    val skipUntilEpochDay: Long? = null,
) {
    val skipUntil: LocalDate? get() = skipUntilEpochDay?.let(LocalDate::ofEpochDay)

    fun toSlot() = Slot(dayOfWeek, openMinute, closeMinute, skipUntil)
}
