package com.pengingatabsen.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pengingatabsen.R
import com.pengingatabsen.data.AttendanceRecord
import com.pengingatabsen.data.Course
import com.pengingatabsen.launch.LaunchTargetActivity
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.ui.MainActivity

object Notifications {
    /** Channel heads-up untuk absen (suara + getar). */
    const val CHANNEL_ABSEN = "absen_dibuka"
    /** Channel biasa untuk info (gagal kirim, dll.). */
    const val CHANNEL_INFO = "info"

    /** ID notifikasi uji. */
    const val TEST_COURSE_ID = -1L

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val absen = NotificationChannel(CHANNEL_ABSEN, "Absen dibuka", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pengingat saat absen mata kuliah dibuka"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(CHANNEL_INFO, "Info", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Status pengiriman bukti ke Telegram"
        }
        nm.createNotificationChannels(listOf(absen, info))
    }

    fun idFor(courseId: Long): Int = 1000 + (courseId % 1_000_000).toInt()

    /** Notifikasi utama: "Absen dibuka: <matkul>" + tombol Absen sekarang / Tunda 5 menit / Libur. */
    fun showReminder(context: Context, course: Course, record: AttendanceRecord?, final: Boolean, silent: Boolean = false) {
        val epochDay = record?.epochDay ?: 0L
        val detail = buildString {
            append(Formatters.window(course.openMinute, course.closeMinute))
            course.room?.let { append(" · Ruang ").append(it) }
        }
        val title = if (final) "⚠️ 5 menit lagi ditutup: ${course.name}" else "Absen dibuka: ${course.name}"
        val text = if (final) "Segera absen sekarang! $detail" else detail
        val absen = launchIntent(context, course.id, epochDay)
        val builder = base(context, CHANNEL_ABSEN, silent)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(absen)
            .addAction(0, "Absen sekarang", absen)
            .addAction(0, "Tunda 5 menit", action(context, NotificationActionReceiver.ACTION_SNOOZE, course.id, epochDay))
            .addAction(0, "Libur", action(context, NotificationActionReceiver.ACTION_HOLIDAY, course.id, epochDay))
        if (final) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        notify(context, idFor(course.id), builder)
    }

    /** Notifikasi lanjutan setelah membuka Dinusverse: "Sudah absen <matkul>?" */
    fun showConfirm(context: Context, course: Course, record: AttendanceRecord, silent: Boolean) {
        val text = "Tekan \"Sudah\" setelah absen berhasil. Bisa juga bagikan screenshot ke Pengingat Absen."
        val builder = base(context, CHANNEL_ABSEN, silent)
            .setContentTitle("Sudah absen ${course.name}?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(launchIntent(context, course.id, record.epochDay))
            .addAction(0, "Sudah, kirim bukti", action(context, NotificationActionReceiver.ACTION_DONE, course.id, record.epochDay))
            .addAction(0, "Belum", action(context, NotificationActionReceiver.ACTION_NOT_YET, course.id, record.epochDay))
        notify(context, idFor(course.id), builder)
    }

    fun showTest(context: Context) {
        val sample = Course(id = TEST_COURSE_ID, name = "Contoh Matkul", dayOfWeek = 1, openMinute = 7 * 60, closeMinute = 8 * 60 + 40, room = "H.3.4")
        showReminder(context, sample, null, final = false)
    }

    /** Info non-heads-up, mis. gagal mengirim bukti. */
    fun showInfo(context: Context, id: Int, title: String, text: String, action: Pair<String, PendingIntent>? = null) {
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        action?.let { builder.addAction(0, it.first, it.second) }
        notify(context, id, builder)
    }

    fun cancel(context: Context, courseId: Long) {
        NotificationManagerCompat.from(context).cancel(idFor(courseId))
    }

    fun cancelId(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun base(context: Context, channel: String, silent: Boolean) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setSilent(silent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun launchIntent(context: Context, courseId: Long, epochDay: Long): PendingIntent =
        PendingIntent.getActivity(
            context, 0, LaunchTargetActivity.intent(context, courseId, epochDay),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun action(context: Context, action: String, courseId: Long, epochDay: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("pengingatabsen://action/$courseId/$epochDay")
            putExtra(AlarmScheduler.EXTRA_COURSE_ID, courseId)
            putExtra(AlarmScheduler.EXTRA_EPOCH_DAY, epochDay)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
