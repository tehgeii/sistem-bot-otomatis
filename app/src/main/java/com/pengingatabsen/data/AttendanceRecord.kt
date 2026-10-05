package com.pengingatabsen.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordStatus(val label: String) {
    /** Absen sedang dibuka dan belum ditandai selesai. */
    ACTIVE("berlangsung"),
    QUEUED("antre"),
    SENT("terkirim"),
    FAILED("gagal"),
    MISSED("terlewat"),
    HOLIDAY("libur"),
    /** Sampai jam tutup, dosen tidak membuka presensi di SiAdin. */
    NO_SESSION("tidak dibuka");

    /** Tidak perlu diingatkan lagi. */
    val finished: Boolean get() = this != ACTIVE
}

/** Satu baris riwayat = satu kemunculan matkul pada satu tanggal. */
@Entity(
    tableName = "records",
    indices = [Index(value = ["courseId", "epochDay"], unique = true)],
)
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    /** Disalin supaya riwayat tetap terbaca meski matkul dihapus. */
    val courseName: String,
    /** Tanggal kemunculan (epoch day). */
    val epochDay: Long,
    val openAtMillis: Long,
    val endAtMillis: Long,
    val status: RecordStatus,
    /** Saat tombol "Sudah" ditekan / screenshot dibagikan. */
    val doneAtMillis: Long? = null,
    val snoozeUntilMillis: Long? = null,
    /** Notifikasi lanjutan "Sudah absen?" sedang ditampilkan. */
    val awaitingConfirm: Boolean = false,
    /** Screenshot bukti (salinan di penyimpanan internal aplikasi). */
    val photoPath: String? = null,
    val error: String? = null,
)
