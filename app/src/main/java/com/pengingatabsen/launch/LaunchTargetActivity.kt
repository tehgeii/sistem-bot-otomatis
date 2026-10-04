package com.pengingatabsen.launch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.AlarmScheduler
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.data.RecordStatus
import com.pengingatabsen.data.toMillis
import com.pengingatabsen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Activity tanpa tampilan untuk tombol "Absen sekarang" & widget.
 * (Android 12+ melarang membuka activity dari BroadcastReceiver notifikasi.)
 * Membuka Dinusverse, lalu menampilkan notifikasi lanjutan "Sudah absen?".
 */
class LaunchTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val courseId = intent.getLongExtra(AlarmScheduler.EXTRA_COURSE_ID, NO_COURSE)
        val epochDay = intent.getLongExtra(AlarmScheduler.EXTRA_EPOCH_DAY, 0L)

        // Baca DataStore sebentar; cepat karena datanya kecil dan sudah di-cache.
        val settings = runBlocking { Graph.settings.current() }
        val target = TargetApps.launchIntent(this, settings)
        val opened = target != null && try {
            startActivity(target)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
        if (!opened) {
            Toast.makeText(this, "Aplikasi tujuan belum dipilih / tidak ditemukan. Atur di Pengaturan.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        if (courseId > 0) {
            val context = applicationContext
            scope.launch { showFollowUp(context, courseId, epochDay, settings.remindIntervalMinutes) }
        } else if (courseId == Notifications.TEST_COURSE_ID) {
            Notifications.cancel(this, courseId)
        }
        finish()
    }

    private suspend fun showFollowUp(context: Context, courseId: Long, epochDay: Long, interval: Int) {
        val repo = Graph.repository
        val course = repo.course(courseId) ?: return
        val dao = Graph.db.recordDao()
        val record = dao.find(courseId, epochDay)
            ?: repo.markOccurrence(course, LocalDate.ofEpochDay(epochDay), RecordStatus.ACTIVE)
        if (record.status != RecordStatus.ACTIVE) {
            Notifications.cancel(context, courseId)
            return
        }
        // Beri waktu untuk absen sebelum pengingat berikutnya berbunyi.
        val updated = record.copy(
            awaitingConfirm = true,
            snoozeUntilMillis = LocalDateTime.now().plusMinutes(interval.toLong()).toMillis(),
        )
        dao.update(updated)
        Notifications.showConfirm(context, course, updated, silent = true)
        AlarmScheduler.reschedule(context, courseId)
    }

    companion object {
        private const val NO_COURSE = 0L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, courseId: Long = NO_COURSE, epochDay: Long = 0L): Intent =
            Intent(context, LaunchTargetActivity::class.java).apply {
                data = Uri.parse("pengingatabsen://buka/$courseId/$epochDay")
                putExtra(AlarmScheduler.EXTRA_COURSE_ID, courseId)
                putExtra(AlarmScheduler.EXTRA_EPOCH_DAY, epochDay)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
    }
}
