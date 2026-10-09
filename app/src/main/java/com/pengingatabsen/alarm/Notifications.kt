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
    /** Pengingat sebelum kuliah & peringatan mode senyap/baterai (getar singkat, tanpa layar penuh). */
    const val CHANNEL_PRE = "sebelum_kuliah"

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
        val pre = NotificationChannel(CHANNEL_PRE, "Sebelum kuliah", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Pengingat sebelum kuliah dimulai, peringatan mode senyap & baterai lemah"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(listOf(absen, absenVibrate, info, pre))
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
    const val READINESS_ID = 780
    private const val MISSED_ALARM_ID = 781
    private const val LAYOUT_CHANGED_ID = 782
    private const val UPDATE_ID = 783
    private const val PRE_CLASS_ID = 784
    private const val QUIET_ID = 785
    private const val SCHEDULE_CHANGES_ID = 786

    /** Intent ke tab Pengaturan (untuk memperbaiki izin/login). */
    private fun settingsIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Cek kesiapan menemukan masalah sebelum kuliah [target] (mis. "MPTI 4515 12:30"). */
    fun showReadinessProblem(context: Context, target: String, problems: List<String>) {
        val text = problems.joinToString("\n• ", prefix = "• ") + "\nPerbaiki sekarang supaya pengingat presensi tetap jalan."
        val open = settingsIntent(context, 4)
        val builder = base(context, absenChannel(), silent = false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentTitle("⚠️ NgiBsen belum siap: $target")
            .setContentText(problems.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Perbaiki", open)
        notify(context, READINESS_ID, builder)
    }

    /** Alarm jam buka [what] (mis. "MPTI 4515 12:30") tidak pernah berbunyi. */
    fun showMissedAlarm(context: Context, what: String) {
        val text = "Alarm $what tidak berbunyi — HP mati, aplikasi baru diperbarui, atau NgiBsen ditahan sistem. " +
            "Aktifkan Autostart & izin baterai (Pengaturan → Izin HP), dan kunci NgiBsen di Recent apps."
        val open = settingsIntent(context, 5)
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠️ Alarm terlewat: $what")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Buka Izin HP", open)
        notify(context, MISSED_ALARM_ID, builder)
    }

    /** Halaman SiAdin termuat tapi berkali-kali tidak dikenali: kemungkinan tampilannya berubah. */
    fun showLayoutChanged(context: Context, text: String) {
        val open = settingsIntent(context, 6)
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠️ Tampilan SiAdin sepertinya berubah")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Buka Diagnosis", open)
        notify(context, LAYOUT_CHANGED_ID, builder)
    }

    /** Versi baru di Release "terbaru". Tap → halaman unduhan (browser). Aplikasi tidak memasang apa pun sendiri. */
    /** Versi baru ada. Tap → pembaruan sekali tap ([com.pengingatabsen.update.UpdateActivity]); [ready] = APK sudah diunduh & diperiksa. */
    fun showUpdateAvailable(context: Context, newVersion: String, installedVersion: String, ready: Boolean) {
        val update = updateIntent(context, start = true)
        val page = PendingIntent.getActivity(
            context, 7,
            Intent(Intent.ACTION_VIEW, Uri.parse(com.pengingatabsen.update.UpdateChecker.RELEASE_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (ready) "⬆️ NgiBsen $newVersion siap dipasang" else "⬆️ Versi baru NgiBsen $newVersion"
        val text = if (ready) {
            "Sudah diunduh lewat Wi-Fi & dicek keasliannya (terpasang $installedVersion). Ketuk Perbarui — data tetap aman."
        } else {
            "Terpasang $installedVersion. Ketuk Perbarui untuk mengunduh & memasang (±9 MB) — data tetap aman."
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(update)
            .addAction(0, "Perbarui", update)
            .addAction(0, "Halaman unduhan", page)
        notify(context, UPDATE_ID, builder)
    }

    /** Pembaruan yang sedang berjalan butuh kamu (konfirmasi Android / gagal). Tap → layar pembaruan. */
    fun showUpdateStep(context: Context, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(updateIntent(context, start = false))
        notify(context, UPDATE_ID, builder)
    }

    /** Dikirim versi BARU setelah pembaruan dari dalam aplikasi selesai. */
    fun showUpdated(context: Context, versionName: String) {
        val text = "Jadwal, riwayat, login, dan pengaturan tetap seperti sebelumnya. Ketuk untuk membuka NgiBsen."
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✅ NgiBsen diperbarui ke $versionName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        notify(context, UPDATE_ID, builder)
    }

    private fun updateIntent(context: Context, start: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context, if (start) 70 else 71,
            com.pengingatabsen.update.UpdateActivity.intent(context, start),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Pengingat sebelum kuliah ([title], mis. "🔔 Kriptografi 4502 mulai 09:30 (15 menit lagi)") beserta peringatan
     * mode senyap/baterai. [quiet] menentukan tombol perbaikan. Hilang sendiri ±1 jam setelah kuliah mulai.
     */
    fun showPreClass(
        context: Context,
        title: String,
        detail: String?,
        warnings: List<String>,
        quiet: com.pengingatabsen.logic.QuietIssue?,
        timeoutMillis: Long,
    ) {
        val text = listOfNotNull(detail?.takeIf { it.isNotBlank() }).plus(warnings.map { "⚠️ $it" }).joinToString("\n")
            .ifBlank { "Siapkan diri — NgiBsen mengecek presensi otomatis saat jam kuliah." }
        val builder = NotificationCompat.Builder(context, CHANNEL_PRE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMillis.coerceAtLeast(60_000L))
            .setContentIntent(openApp(context))
        addQuietActions(context, builder, quiet)
        notify(context, PRE_CLASS_ID, builder)
    }

    /** Saat kuliah dimulai HP masih Senyap/Jangan Ganggu: getar presensi tidak akan terasa. */
    fun showQuietWarning(context: Context, courseName: String, issue: com.pengingatabsen.logic.QuietIssue) {
        val text = com.pengingatabsen.logic.PhoneCheck.quietText(issue) + ". NgiBsen tetap mengecek presensi $courseName, " +
            "tapi kamu mungkin tidak merasakan getarnya."
        val builder = NotificationCompat.Builder(context, CHANNEL_PRE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🔕 HP dalam mode senyap: $courseName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 60 * 60_000L)
            .setContentIntent(openApp(context))
        addQuietActions(context, builder, issue)
        notify(context, QUIET_ID, builder)
    }

    private fun addQuietActions(context: Context, builder: NotificationCompat.Builder, quiet: com.pengingatabsen.logic.QuietIssue?) {
        when (quiet) {
            com.pengingatabsen.logic.QuietIssue.DND -> {
                builder.addAction(
                    0, "Izinkan NgiBsen",
                    PendingIntent.getActivity(
                        context, 72, PhoneStatus.dndSettingsIntent(context),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                builder.addAction(
                    0, "Sudah diizinkan",
                    PendingIntent.getBroadcast(
                        context, 73,
                        Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_DND_ALLOWED),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            com.pengingatabsen.logic.QuietIssue.SILENT -> builder.addAction(
                0, "Atur suara",
                PendingIntent.getActivity(
                    context, 74, PhoneStatus.soundSettingsIntent(),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            null -> Unit
        }
    }

    fun cancelQuietNotices(context: Context) {
        cancelId(context, PRE_CLASS_ID)
        cancelId(context, QUIET_ID)
    }

    /**
     * Radar presensi di luar jadwal. Yang DIBUKA → bergetar seperti presensi biasa, tap membuka halaman presensi
     * ([courseId]/[epochDay] = matkul jadwal yang cocok, supaya tombolnya disorot & buktinya tercatat ke matkul itu).
     * Sesi hari ini yang tidak dijadwalkan → info + tombol "Tambah kelas pengganti".
     */
    fun showRadar(context: Context, finding: com.pengingatabsen.logic.RadarFinding, courseId: Long?, epochDay: Long, url: String) {
        val title = com.pengingatabsen.logic.Radar.title(finding)
        val text = com.pengingatabsen.logic.Radar.text(finding)
        val id = 9_900 + (finding.key(java.time.LocalDate.ofEpochDay(epochDay.coerceAtLeast(0))).hashCode() and 0x7F)
        val builder = if (finding.kind == com.pengingatabsen.logic.RadarKind.SESSION_NOT_SCHEDULED_TODAY) {
            val add = PendingIntent.getActivity(
                context, id,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, 0)
                    .putExtra(MainActivity.EXTRA_REPLACE_COURSE, courseId ?: 0L)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            NotificationCompat.Builder(context, CHANNEL_INFO)
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setContentIntent(add)
                .addAction(0, "Tambah kelas pengganti", add)
        } else {
            val open = PendingIntent.getActivity(
                context, id, WebBrowserActivity.intent(context, url, courseId ?: 0L, if (courseId != null) epochDay else 0L),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            base(context, absenChannel(), silent = false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, "Buka presensi", open)
        }
        builder.setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        notify(context, id, builder)
    }

    /** Jadwal di KRS SiAdin berbeda dengan jadwal NgiBsen. Tap → layar Jadwal (banner "Terapkan"). */
    fun showScheduleChanges(context: Context, lines: List<String>) {
        val text = lines.joinToString("\n") { "• $it" } + "\nBuka NgiBsen untuk menerapkan atau mengabaikan."
        val open = PendingIntent.getActivity(
            context, 75,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("📅 Jadwal di SiAdin berubah")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Lihat", open)
        notify(context, SCHEDULE_CHANGES_ID, builder)
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

    /** Channel notifikasi presensi yang sedang dipakai (untuk cek & pengaturan Jangan Ganggu). */
    fun presensiChannel(): String = absenChannel()

    /** Channel presensi untuk pengaturan "Getar saja" [vibrateOnly] (tanpa membaca pengaturan). */
    fun presensiChannel(vibrateOnly: Boolean): String = if (vibrateOnly) CHANNEL_ABSEN_VIBRATE else CHANNEL_ABSEN

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
        if (!granted) {
            com.pengingatabsen.data.DiagLog.add("NOTIFIKASI DIBLOKIR: izin notifikasi belum diberikan")
            return
        }
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) com.pengingatabsen.data.DiagLog.add("NOTIFIKASI DIMATIKAN di pengaturan HP untuk NgiBsen")
        try {
            nm.notify(id, builder.build())
        } catch (e: SecurityException) {
            com.pengingatabsen.data.DiagLog.add("notifikasi gagal: ${e.message}")
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
