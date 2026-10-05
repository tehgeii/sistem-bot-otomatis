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
import com.pengingatabsen.Graph
import com.pengingatabsen.R
import com.pengingatabsen.data.AttendanceRecord
import com.pengingatabsen.data.Course
import com.pengingatabsen.launch.LaunchTargetActivity
import com.pengingatabsen.launch.PresensiAlertActivity
import com.pengingatabsen.launch.WebBrowserActivity
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.ui.MainActivity
import kotlinx.coroutines.runBlocking

object Notifications {
    /** Channel heads-up untuk absen (suara + getar). */
    const val CHANNEL_ABSEN = "absen_dibuka"
    /** Channel heads-up untuk absen, getar saja (dipakai saat "Getar saja" aktif). */
    const val CHANNEL_ABSEN_VIBRATE = "absen_dibuka_getar"
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
        val absenVibrate = NotificationChannel(
            CHANNEL_ABSEN_VIBRATE, "Absen dibuka (getar saja)", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Pengingat absen tanpa suara, hanya getar"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(CHANNEL_INFO, "Info", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Status pengiriman bukti ke Telegram"
        }
        nm.createNotificationChannels(listOf(absen, absenVibrate, info))
    }

    fun idFor(courseId: Long): Int = 1000 + (courseId % 1_000_000).toInt()

    /** Notifikasi utama: "Waktunya absen / Presensi sudah dibuka: <matkul>" + tombol Absen sekarang / Tunda 5 menit / Libur. */
    fun showReminder(
        context: Context,
        course: Course,
        record: AttendanceRecord?,
        final: Boolean,
        silent: Boolean = false,
        /** Mode pintar: presensi di SiAdin belum dibuka dosen → notifikasi senyap. */
        waiting: Boolean = false,
        /** Mode pintar: SiAdin gagal dicek beberapa kali → minta cek manual. */
        checkFailed: Boolean = false,
        /** Mode pintar: presensi di SiAdin terdeteksi SUDAH dibuka dosen. */
        sessionOpen: Boolean = false,
        /** Bila diisi: tampilkan layar penuh "Presensi sudah dibuka!" yang membuka URL ini. */
        fullScreenUrl: String? = null,
    ) {
        val epochDay = record?.epochDay ?: 0L
        val detail = buildString {
            append(Formatters.window(course.openMinute, course.closeMinute))
            course.room?.let { append(" · Ruang ").append(it) }
        }
        val title = when {
            waiting -> "Menunggu presensi: ${course.name}"
            checkFailed -> "Cek presensi: ${course.name}"
            final -> "⚠️ 5 menit lagi ditutup: ${course.name}"
            sessionOpen -> "✅ Presensi sudah dibuka: ${course.name}"
            // Tanpa pengecekan SiAdin hanya jadwal yang diketahui, jadi jangan klaim "dibuka".
            else -> "Waktunya absen: ${course.name}"
        }
        val text = when {
            waiting -> "Belum dibuka dosen di SiAdin. Dicek otomatis tiap menit — HP bergetar begitu dibuka. $detail"
            checkFailed -> "SiAdin tidak bisa dicek otomatis (internet/login). Tap untuk cek manual. $detail"
            final -> "Segera absen sekarang! $detail"
            else -> detail
        }
        val absen = launchIntent(context, course.id, epochDay)
        val builder = base(context, absenChannel(), silent || waiting)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(absen)
            .addAction(0, "Absen sekarang", absen)
            .addAction(0, "Tunda 5 menit", action(context, NotificationActionReceiver.ACTION_SNOOZE, course.id, epochDay))
            .addAction(0, "Libur", action(context, NotificationActionReceiver.ACTION_HOLIDAY, course.id, epochDay))
        if (final || waiting || checkFailed) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        if (waiting) builder.setPriority(NotificationCompat.PRIORITY_LOW)
        if (fullScreenUrl != null && canUseFullScreen(context)) {
            val alert = PendingIntent.getActivity(
                context, 3, PresensiAlertActivity.intent(context, course.id, epochDay, course.name, fullScreenUrl),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(alert, true)
        }
        notify(context, idFor(course.id), builder)
    }

    /** Android 14+ mewajibkan izin khusus untuk notifikasi layar penuh. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Notifikasi senyap untuk pekerjaan "Mengecek presensi SiAdin…" (wajib untuk expedited work Android < 12). */
    fun checkingNotification(context: Context) =
        NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mengecek presensi SiAdin…")
            .setSilent(true)
            .setOngoing(true)
            .build()

    /** Notifikasi lanjutan setelah membuka Dinusverse: "Sudah absen <matkul>?" */
    fun showConfirm(context: Context, course: Course, record: AttendanceRecord, silent: Boolean) {
        val text = "Tekan \"Sudah\" setelah absen berhasil. Bisa juga bagikan screenshot ke NgiBsen."
        val builder = base(context, absenChannel(), silent)
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

    /** Heads-up saat browser mini mendeteksi sesi presensi sudah dibuka dosen. */
    fun showPresensiOpen(context: Context, courseId: Long, epochDay: Long, url: String) {
        val open = PendingIntent.getActivity(
            context, 2, WebBrowserActivity.intent(context, url, courseId, epochDay),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = base(context, absenChannel(), silent = false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentTitle("Presensi sudah dibuka!")
            .setContentText("Tap lalu tekan tombol presensi yang disorot kuning.")
            .setContentIntent(open)
        notify(context, PRESENSI_OPEN_ID, builder)
    }

    private const val PRESENSI_OPEN_ID = 777
    private const val LOGIN_FAILED_ID = 779

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

    /** NIM/password SiAdin ditolak saat login otomatis. Tap → Pengaturan untuk memperbarui. */
    fun showLoginFailed(context: Context) {
        val settings = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Login otomatis ditolak SiAdin — NIM/password mungkin berubah. " +
            "Perbarui di Pengaturan → SiAdin web. Pengingat tetap jalan."
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Login SiAdin gagal")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(settings)
            .addAction(0, "Perbarui", settings)
        notify(context, LOGIN_FAILED_ID, builder)
    }

    fun cancel(context: Context, courseId: Long) {
        NotificationManagerCompat.from(context).cancel(idFor(courseId))
    }

    fun cancelId(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    /** Channel absen sesuai pengaturan "Getar saja". */
    private fun absenChannel(): String {
        val vibrateOnly = runBlocking { Graph.settings.current().vibrateOnly }
        return if (vibrateOnly) CHANNEL_ABSEN_VIBRATE else CHANNEL_ABSEN
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
