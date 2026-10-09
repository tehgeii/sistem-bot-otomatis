package com.pengingatabsen.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Course::class, AttendanceRecord::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun recordDao(): RecordDao

    companion object {
        /**
         * Versi 2 (NgiBsen 3.1): kolom kelas pengganti. Hanya MENAMBAH kolom yang boleh kosong, jadi semua
         * jadwal & riwayat lama tetap utuh (tanpa fallback destruktif: data tidak pernah dihapus diam-diam).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `oneOffEpochDay` INTEGER")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "pengingat_absen.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
