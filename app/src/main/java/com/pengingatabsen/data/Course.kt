package com.pengingatabsen.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pengingatabsen.logic.Slot
import java.time.LocalDate

/** Satu mata kuliah yang berulang tiap minggu, atau kelas pengganti sekali saja ([oneOffEpochDay]). */
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
    /**
     * Kelas pengganti: hanya terjadi sekali pada tanggal ini (epoch day); null = jadwal mingguan biasa.
     * [dayOfWeek] selalu disamakan dengan hari tanggal ini. Kolom baru sejak database versi 2.
     */
    val oneOffEpochDay: Long? = null,
) {
    /** [extraMinutes]: perpanjangan setelah jam tutup (mode pintar). */
    fun toSlot(extraMinutes: Int = 0) = Slot(dayOfWeek, openMinute, closeMinute, skipUntil, extraMinutes, oneOffDate)
}

/** Di luar entity supaya Room tidak menganggapnya kolom. */
val Course.skipUntil: LocalDate? get() = skipUntilEpochDay?.let(LocalDate::ofEpochDay)

/** Tanggal kelas pengganti, atau null untuk jadwal mingguan. */
val Course.oneOffDate: LocalDate? get() = oneOffEpochDay?.let(LocalDate::ofEpochDay)

val Course.isOneOff: Boolean get() = oneOffEpochDay != null
