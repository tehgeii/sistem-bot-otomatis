package com.pengingatabsen.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pengingatabsen.logic.ScheduleMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pengaturan")

data class AppSettings(
    val onboardingDone: Boolean = false,
    /** Package aplikasi tujuan (Dinusverse), dipilih pengguna. */
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    /** Jika diisi, tombol "Absen sekarang" membuka URL ini. */
    val deepLink: String? = null,
    val hasBotToken: Boolean = false,
    val chatId: String? = null,
    val botUsername: String? = null,
    val remindIntervalMinutes: Int = ScheduleMath.DEFAULT_REMIND_INTERVAL,
    /** Default getar saja supaya tidak berbunyi di kelas. */
    val vibrateOnly: Boolean = true,
    /** NIM & password SiAdin tersimpan (terenkripsi) untuk login otomatis di browser mini. */
    val hasSiadinLogin: Boolean = false,
    val autoLogin: Boolean = true,
    /** Getar hanya saat presensi SiAdin benar-benar sudah dibuka (dicek tiap menit). */
    val smartPresensi: Boolean = true,
    /** Layar penuh seperti alarm saat presensi baru saja dibuka dosen. */
    val fullScreenAlert: Boolean = true,
    /** Perkiraan data yang dipakai pengecekan SiAdin hari ini (byte). */
    val checkBytesToday: Long = 0,
    /** Keterlambatan pengecekan SiAdin terakhir dari jadwal alarm (detik); null = belum ada data. */
    val lastCheckDelaySec: Long? = null,
    /** Kirim ringkasan mingguan ke Telegram tiap Minggu malam. */
    val weeklySummary: Boolean = true,
    /** Catatan pengecekan SiAdin terakhir, mis. "10.14 · Pemrograman Sisi Klien · presensi DIBUKA". */
    val lastCheck: String? = null,
    /** Cek kesiapan otomatis ±30 menit sebelum kuliah pertama tiap hari. */
    val readinessCheck: Boolean = true,
    /** Hasil cek kesiapan terakhir: true = siap, false = ada masalah, null = belum pernah. */
    val readinessOk: Boolean? = null,
    /** Keterangan cek kesiapan terakhir, mis. "09.00 · siap untuk Pemrograman Sisi Klien 09:30". */
    val readinessText: String? = null,
    /** Kemunculan ("courseId:epochDay") yang presensinya sudah terlihat dibuka dosen. */
    val presensiOpenKeys: Set<String> = emptySet(),
    /** Cek sehari sekali apakah ada versi NgiBsen baru di Release. */
    val updateCheck: Boolean = true,
    /** Versi baru diunduh & diperiksa lebih dulu saat Wi-Fi (dipasang tetap atas tap pengguna). */
    val updateAutoDownload: Boolean = true,
    /** Aturan kehadiran untuk "jatah tidak hadir" (pertemuan per semester & minimal hadir %). */
    val meetingsPerSemester: Int = com.pengingatabsen.logic.AttendanceRule.DEFAULT_MEETINGS,
    val minAttendancePercent: Int = com.pengingatabsen.logic.AttendanceRule.DEFAULT_MIN_PERCENT,
    /** Riwayat dihitung sejak tanggal ini (awal semester); null = semua riwayat. */
    val semesterStartEpochDay: Long? = null,
    /** Persentase kehadiran resmi SiAdin terakhir per nama jadwal. */
    val official: Map<String, com.pengingatabsen.logic.OfficialSnapshot> = emptyMap(),
    /** Pesan Telegram cadangan bila presensi sudah dibuka ±5 menit tapi belum ditekan. */
    val telegramNudge: Boolean = true,
    /** Peringatan bila HP mode Senyap / Jangan Ganggu sebelum & saat kuliah dimulai. */
    val quietCheck: Boolean = true,
    /** Pengguna menyatakan NgiBsen sudah diizinkan menembus Jangan Ganggu. */
    val dndAllowed: Boolean = false,
    /** Peringatan baterai lemah sebelum kuliah. */
    val batteryCheck: Boolean = true,
    /** Pengingat biasa beberapa menit sebelum kuliah mulai. */
    val preClassReminder: Boolean = true,
    val preClassLead: Int = com.pengingatabsen.logic.PreClass.DEFAULT_LEAD,
    /** Radar presensi di luar jadwal (kartu lain yang terbaca + cek ringan berkala di hari kuliah). */
    val radar: Boolean = true,
    /** Beri tahu bila jadwal di KRS SiAdin berbeda dengan jadwal NgiBsen. */
    val scheduleDiffCheck: Boolean = true,
    /** Perbedaan jadwal KRS ↔ NgiBsen yang belum diterapkan/diabaikan. */
    val scheduleChanges: List<com.pengingatabsen.logic.ScheduleChange> = emptyList(),
) {
    val attendanceRule: com.pengingatabsen.logic.AttendanceRule
        get() = com.pengingatabsen.logic.AttendanceRule(meetingsPerSemester, minAttendancePercent)

    val semesterStart: java.time.LocalDate?
        get() = semesterStartEpochDay?.let(java.time.LocalDate::ofEpochDay)

    /** Mode pintar hanya berlaku untuk SiAdin web dengan login tersimpan. */
    val smartModeActive: Boolean
        get() = smartPresensi && hasSiadinLogin && com.pengingatabsen.logic.SiadinUrls.isSiadinUrl(deepLink)

    val telegramReady: Boolean get() = hasBotToken && !chatId.isNullOrBlank()
}

/** Rentang wajar jumlah pertemuan per semester. */
val MEETINGS_RANGE = 1..32

class SettingsStore(private val context: Context) {
    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val TARGET_PACKAGE = stringPreferencesKey("target_package")
        val TARGET_LABEL = stringPreferencesKey("target_label")
        val DEEP_LINK = stringPreferencesKey("deep_link")
        val BOT_TOKEN_ENC = stringPreferencesKey("bot_token_enc")
        val CHAT_ID = stringPreferencesKey("chat_id")
        val BOT_USERNAME = stringPreferencesKey("bot_username")
        val REMIND_INTERVAL = intPreferencesKey("remind_interval")
        val VIBRATE_ONLY = booleanPreferencesKey("vibrate_only")
        val SIADIN_NIM_ENC = stringPreferencesKey("siadin_nim_enc")
        val SIADIN_PASSWORD_ENC = stringPreferencesKey("siadin_password_enc")
        val AUTO_LOGIN = booleanPreferencesKey("auto_login")
        val SMART_PRESENSI = booleanPreferencesKey("smart_presensi")
        val FULL_SCREEN_ALERT = booleanPreferencesKey("full_screen_alert")
        /** Kemunculan ("courseId:epochDay") yang presensinya sudah terlihat dibuka dosen. */
        val PRESENSI_OPEN = stringSetPreferencesKey("presensi_open")
        val CHECK_BYTES = longPreferencesKey("check_bytes")
        val CHECK_BYTES_DAY = longPreferencesKey("check_bytes_day")
        val LAST_CHECK_DELAY = longPreferencesKey("last_check_delay_sec")
        val WEEKLY_SUMMARY = booleanPreferencesKey("weekly_summary")
        /** Senin (epoch day) minggu yang ringkasannya sudah terkirim. */
        val SUMMARY_SENT_WEEK = longPreferencesKey("summary_sent_week")
        /** Hari (epoch day) notifikasi "login gagal" terakhir ditampilkan. */
        val LOGIN_FAILED_DAY = longPreferencesKey("login_failed_day")
        val LAST_CHECK = stringPreferencesKey("last_check")
        val READINESS_CHECK = booleanPreferencesKey("readiness_check")
        val READINESS_OK = booleanPreferencesKey("readiness_ok")
        val READINESS_TEXT = stringPreferencesKey("readiness_text")
        /** Kemunculan ("courseId:epochDay") yang alarm jam bukanya sudah dipasang. */
        val ARMED = stringSetPreferencesKey("armed_open")
        /** Kemunculan yang alarm terlewatnya sudah diberitahukan (agar tidak berulang). */
        val MISSED_REPORTED = stringSetPreferencesKey("missed_reported")
        /** Pengecekan berturut-turut yang halamannya termuat tapi tidak dikenali ("courseId:epochDay,..."). */
        val LAYOUT_SUSPECTS = stringPreferencesKey("layout_suspects")
        /** Hari (epoch day) peringatan "tampilan SiAdin berubah" terakhir dikirim. */
        val LAYOUT_WARNED_DAY = longPreferencesKey("layout_warned_day")
        val UPDATE_CHECK = booleanPreferencesKey("update_check")
        val UPDATE_AUTO_DOWNLOAD = booleanPreferencesKey("update_auto_download")
        /** versionCode yang sedang dipasang lewat pembaruan sekali tap (untuk notifikasi "diperbarui"). */
        val UPDATE_INSTALLING = longPreferencesKey("update_installing")
        val MEETINGS = intPreferencesKey("meetings_per_semester")
        val MIN_PERCENT = intPreferencesKey("min_attendance_percent")
        val SEMESTER_START = longPreferencesKey("semester_start_day")
        /** Persentase resmi SiAdin per nama jadwal (JSON). */
        val OFFICIAL = stringPreferencesKey("official_attendance")
        /** Peringatan jatah resmi yang sudah dikirim (agar tidak berulang). */
        val OFFICIAL_WARNED = stringSetPreferencesKey("official_warned")
        /** versionCode terbaru yang sudah diberitahukan (agar notifikasi versi baru tidak berulang). */
        val UPDATE_NOTIFIED_CODE = longPreferencesKey("update_notified_code")
        val TELEGRAM_NUDGE = booleanPreferencesKey("telegram_nudge")
        val QUIET_CHECK = booleanPreferencesKey("quiet_check")
        val DND_ALLOWED = booleanPreferencesKey("dnd_allowed")
        val BATTERY_CHECK = booleanPreferencesKey("battery_check")
        val PRE_CLASS = booleanPreferencesKey("pre_class_reminder")
        val PRE_CLASS_LEAD = intPreferencesKey("pre_class_lead")
        val RADAR = booleanPreferencesKey("radar")
        val RADAR_LAST_RUN = longPreferencesKey("radar_last_run")
        val SCHEDULE_DIFF_CHECK = booleanPreferencesKey("schedule_diff_check")
        /** Perbedaan jadwal KRS yang menunggu keputusan (JSON) & sidik perbedaan terakhir yang sudah diberitahukan. */
        val SCHEDULE_CHANGES = stringPreferencesKey("schedule_changes")
        val SCHEDULE_CHANGES_SEEN = stringPreferencesKey("schedule_changes_seen")
        /** Kapan presensi pertama kali terlihat dibuka ("courseId:epochDay:millis"). */
        val PRESENSI_OPENED_AT = stringSetPreferencesKey("presensi_opened_at")
        /** Pemberitahuan sekali-saja yang sudah dikirim (Telegram cadangan, radar, mode senyap). */
        val NOTICES = stringSetPreferencesKey("notices_sent")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    // ---------- Cadangan (pindah HP) ----------
    // Hanya pengaturan yang AMAN dipindah. NIM/password/bot token TIDAK ikut (terenkripsi kunci HP lama), begitu
    // juga status sementara & onboarding (wizard di HP baru tetap jalan supaya izin & login diisi ulang).
    private val backupBooleans = listOf(
        Keys.VIBRATE_ONLY, Keys.AUTO_LOGIN, Keys.SMART_PRESENSI, Keys.FULL_SCREEN_ALERT,
        Keys.WEEKLY_SUMMARY, Keys.READINESS_CHECK, Keys.UPDATE_CHECK, Keys.UPDATE_AUTO_DOWNLOAD,
        Keys.TELEGRAM_NUDGE, Keys.QUIET_CHECK, Keys.BATTERY_CHECK, Keys.PRE_CLASS, Keys.RADAR, Keys.SCHEDULE_DIFF_CHECK,
    )
    private val backupInts = listOf(Keys.REMIND_INTERVAL, Keys.MEETINGS, Keys.MIN_PERCENT, Keys.PRE_CLASS_LEAD)
    private val backupLongs = listOf(Keys.SEMESTER_START)
    private val backupStrings = listOf(Keys.TARGET_PACKAGE, Keys.TARGET_LABEL, Keys.DEEP_LINK)

    /** Pengaturan yang ikut file cadangan (nama kunci DataStore → nilai). */
    suspend fun backupPrefs(): Map<String, Any> {
        val p = context.dataStore.data.first()
        val out = LinkedHashMap<String, Any>()
        backupBooleans.forEach { k -> p[k]?.let { out[k.name] = it } }
        backupInts.forEach { k -> p[k]?.let { out[k.name] = it } }
        backupLongs.forEach { k -> p[k]?.let { out[k.name] = it } }
        backupStrings.forEach { k -> p[k]?.let { out[k.name] = it } }
        return out
    }

    /**
     * Terapkan pengaturan dari cadangan (hanya kunci yang dikenal & tipenya cocok), lalu bersihkan status
     * sementara yang memakai id matkul lama (presensi dibuka, alarm terpasang, hitungan gagal).
     */
    suspend fun restorePrefs(values: Map<String, Any>) = context.dataStore.edit { p ->
        // Pengaturan yang tidak ada di cadangan dikembalikan ke bawaan (isi cadangan MENGGANTI, bukan digabung).
        backupBooleans.forEach { k -> (values[k.name] as? Boolean)?.let { p[k] = it } ?: p.remove(k) }
        backupInts.forEach { k -> (values[k.name] as? Number)?.let { p[k] = it.toInt() } ?: p.remove(k) }
        backupLongs.forEach { k -> (values[k.name] as? Number)?.let { p[k] = it.toLong() } ?: p.remove(k) }
        backupStrings.forEach { k -> (values[k.name] as? String)?.takeIf { it.isNotBlank() }?.let { p[k] = it } ?: p.remove(k) }
        // Tautan tujuan dari file: hanya SiAdin asli atau tautan aplikasi (bukan situs web lain).
        p[Keys.DEEP_LINK]?.let { link ->
            val web = link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)
            if (web && !com.pengingatabsen.logic.SiadinUrls.isSiadinUrl(link)) p.remove(Keys.DEEP_LINK)
        }
        p[Keys.SEMESTER_START]?.let { if (it !in com.pengingatabsen.logic.BackupCodec.PLAUSIBLE_DAYS) p.remove(Keys.SEMESTER_START) }
        p[Keys.REMIND_INTERVAL]?.let { p[Keys.REMIND_INTERVAL] = it.coerceIn(1, 30) }
        p[Keys.MEETINGS]?.let { p[Keys.MEETINGS] = it.coerceIn(MEETINGS_RANGE) }
        p[Keys.MIN_PERCENT]?.let { p[Keys.MIN_PERCENT] = it.coerceIn(0, 100) }
        p[Keys.PRE_CLASS_LEAD]?.let { if (it !in com.pengingatabsen.logic.PreClass.LEAD_CHOICES) p.remove(Keys.PRE_CLASS_LEAD) }
        p.remove(Keys.PRESENSI_OPEN)
        p.remove(Keys.PRESENSI_OPENED_AT)
        p.remove(Keys.NOTICES)
        // Id matkul berganti setelah pemulihan: perbedaan jadwal lama tidak berlaku lagi.
        p.remove(Keys.SCHEDULE_CHANGES)
        p.remove(Keys.SCHEDULE_CHANGES_SEEN)
        p.remove(Keys.ARMED)
        p.remove(Keys.MISSED_REPORTED)
        p.remove(Keys.LAYOUT_SUSPECTS)
        p.asMap().keys.map { it.name }.filter { it.startsWith("presensi_unknown_") }
            .forEach { p.remove(intPreferencesKey(it)) }
    }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        onboardingDone = this[Keys.ONBOARDING_DONE] ?: false,
        targetPackage = this[Keys.TARGET_PACKAGE],
        targetLabel = this[Keys.TARGET_LABEL],
        deepLink = this[Keys.DEEP_LINK]?.takeIf { it.isNotBlank() },
        hasBotToken = this[Keys.BOT_TOKEN_ENC] != null,
        chatId = this[Keys.CHAT_ID],
        botUsername = this[Keys.BOT_USERNAME],
        remindIntervalMinutes = this[Keys.REMIND_INTERVAL] ?: ScheduleMath.DEFAULT_REMIND_INTERVAL,
        vibrateOnly = this[Keys.VIBRATE_ONLY] ?: true,
        hasSiadinLogin = this[Keys.SIADIN_NIM_ENC] != null && this[Keys.SIADIN_PASSWORD_ENC] != null,
        autoLogin = this[Keys.AUTO_LOGIN] ?: true,
        smartPresensi = this[Keys.SMART_PRESENSI] ?: true,
        fullScreenAlert = this[Keys.FULL_SCREEN_ALERT] ?: true,
        checkBytesToday = if (this[Keys.CHECK_BYTES_DAY] == todayEpochDay()) this[Keys.CHECK_BYTES] ?: 0 else 0,
        lastCheckDelaySec = this[Keys.LAST_CHECK_DELAY],
        weeklySummary = this[Keys.WEEKLY_SUMMARY] ?: true,
        lastCheck = this[Keys.LAST_CHECK],
        readinessCheck = this[Keys.READINESS_CHECK] ?: true,
        readinessOk = this[Keys.READINESS_OK],
        readinessText = this[Keys.READINESS_TEXT],
        presensiOpenKeys = this[Keys.PRESENSI_OPEN] ?: emptySet(),
        updateCheck = this[Keys.UPDATE_CHECK] ?: true,
        updateAutoDownload = this[Keys.UPDATE_AUTO_DOWNLOAD] ?: true,
        meetingsPerSemester = this[Keys.MEETINGS] ?: com.pengingatabsen.logic.AttendanceRule.DEFAULT_MEETINGS,
        minAttendancePercent = this[Keys.MIN_PERCENT] ?: com.pengingatabsen.logic.AttendanceRule.DEFAULT_MIN_PERCENT,
        semesterStartEpochDay = this[Keys.SEMESTER_START],
        official = com.pengingatabsen.logic.OfficialAttendance.decode(this[Keys.OFFICIAL]),
        telegramNudge = this[Keys.TELEGRAM_NUDGE] ?: true,
        quietCheck = this[Keys.QUIET_CHECK] ?: true,
        dndAllowed = this[Keys.DND_ALLOWED] ?: false,
        batteryCheck = this[Keys.BATTERY_CHECK] ?: true,
        preClassReminder = this[Keys.PRE_CLASS] ?: true,
        preClassLead = this[Keys.PRE_CLASS_LEAD]?.takeIf { it in com.pengingatabsen.logic.PreClass.LEAD_CHOICES }
            ?: com.pengingatabsen.logic.PreClass.DEFAULT_LEAD,
        radar = this[Keys.RADAR] ?: true,
        scheduleDiffCheck = this[Keys.SCHEDULE_DIFF_CHECK] ?: true,
        scheduleChanges = com.pengingatabsen.logic.ScheduleDiff.decode(this[Keys.SCHEDULE_CHANGES]),
    )

    suspend fun setTelegramNudge(enabled: Boolean) = context.dataStore.edit { it[Keys.TELEGRAM_NUDGE] = enabled }
    suspend fun setQuietCheck(enabled: Boolean) = context.dataStore.edit { it[Keys.QUIET_CHECK] = enabled }
    suspend fun setDndAllowed(allowed: Boolean) = context.dataStore.edit { it[Keys.DND_ALLOWED] = allowed }
    suspend fun setBatteryCheck(enabled: Boolean) = context.dataStore.edit { it[Keys.BATTERY_CHECK] = enabled }
    suspend fun setPreClass(enabled: Boolean) = context.dataStore.edit { it[Keys.PRE_CLASS] = enabled }
    suspend fun setPreClassLead(minutes: Int) = context.dataStore.edit {
        it[Keys.PRE_CLASS_LEAD] = minutes.takeIf { m -> m in com.pengingatabsen.logic.PreClass.LEAD_CHOICES }
            ?: com.pengingatabsen.logic.PreClass.DEFAULT_LEAD
    }
    suspend fun setRadar(enabled: Boolean) = context.dataStore.edit { it[Keys.RADAR] = enabled }
    suspend fun setScheduleDiffCheck(enabled: Boolean) = context.dataStore.edit {
        it[Keys.SCHEDULE_DIFF_CHECK] = enabled
        if (!enabled) it.remove(Keys.SCHEDULE_CHANGES)
    }

    suspend fun radarLastRun(): Long = context.dataStore.data.first()[Keys.RADAR_LAST_RUN] ?: 0L
    suspend fun setRadarLastRun(millis: Long) = context.dataStore.edit { it[Keys.RADAR_LAST_RUN] = millis }

    /**
     * Simpan perbedaan jadwal KRS terbaru. True bila perbedaan ini BARU (belum pernah diberitahukan) → beri tahu.
     * Kosong = jadwal sudah sama, yang tertunda dihapus.
     */
    suspend fun putScheduleChanges(changes: List<com.pengingatabsen.logic.ScheduleChange>): Boolean {
        var fresh = false
        context.dataStore.edit { p ->
            if (changes.isEmpty()) {
                p.remove(Keys.SCHEDULE_CHANGES)
                p.remove(Keys.SCHEDULE_CHANGES_SEEN)
                return@edit
            }
            val sig = com.pengingatabsen.logic.ScheduleDiff.signature(changes)
            p[Keys.SCHEDULE_CHANGES] = com.pengingatabsen.logic.ScheduleDiff.encode(changes)
            if (p[Keys.SCHEDULE_CHANGES_SEEN] != sig) {
                p[Keys.SCHEDULE_CHANGES_SEEN] = sig
                fresh = true
            }
        }
        return fresh
    }

    /** Perbedaan sudah diterapkan/diabaikan: banner hilang; perbedaan yang sama tidak diberitahukan lagi. */
    suspend fun clearScheduleChanges() = context.dataStore.edit { it.remove(Keys.SCHEDULE_CHANGES) }

    /** Catat kapan presensi kemunculan ini pertama kali terlihat dibuka (sekali; dibersihkan setelah 7 hari). */
    suspend fun markPresensiOpenedAt(courseId: Long, epochDay: Long, millis: Long) = context.dataStore.edit { p ->
        val prefix = "$courseId:$epochDay:"
        val set = p[Keys.PRESENSI_OPENED_AT] ?: emptySet()
        if (set.any { it.startsWith(prefix) }) return@edit
        p[Keys.PRESENSI_OPENED_AT] = set.filter { (it.split(':').getOrNull(1)?.toLongOrNull() ?: 0L) >= epochDay - 7 }.toSet() +
            (prefix + millis)
    }

    suspend fun presensiOpenedAt(courseId: Long, epochDay: Long): Long? {
        val prefix = "$courseId:$epochDay:"
        return context.dataStore.data.first()[Keys.PRESENSI_OPENED_AT]
            ?.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull()
    }

    /** True bila pemberitahuan sekali-saja [key] belum pernah dikirim (lalu menandainya). Maks. 300 kunci. */
    suspend fun claimNotice(key: String): Boolean {
        var claimed = false
        context.dataStore.edit { p ->
            val set = p[Keys.NOTICES] ?: emptySet()
            if (key !in set) {
                p[Keys.NOTICES] = (set.toList().takeLast(299) + key).toSet()
                claimed = true
            }
        }
        return claimed
    }

    suspend fun setReadinessCheck(enabled: Boolean) = context.dataStore.edit { it[Keys.READINESS_CHECK] = enabled }

    suspend fun setReadiness(ok: Boolean, text: String) = context.dataStore.edit {
        it[Keys.READINESS_OK] = ok
        it[Keys.READINESS_TEXT] = text
    }

    /** Catat bahwa alarm jam buka kemunculan ini sudah dipasang (dibersihkan otomatis setelah 7 hari). */
    suspend fun markArmed(courseId: Long, epochDay: Long) {
        val key = "$courseId:$epochDay"
        if (context.dataStore.data.first()[Keys.ARMED]?.contains(key) == true) return
        context.dataStore.edit { prefs -> prefs[Keys.ARMED] = recent(prefs[Keys.ARMED], epochDay) + key }
    }

    suspend fun armed(): List<com.pengingatabsen.logic.ArmedOccurrence> =
        (context.dataStore.data.first()[Keys.ARMED] ?: emptySet()).mapNotNull { entry ->
            val (c, d) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val courseId = c.toLongOrNull() ?: return@mapNotNull null
            val day = d.toLongOrNull() ?: return@mapNotNull null
            com.pengingatabsen.logic.ArmedOccurrence(courseId, day)
        }

    /** True bila alarm terlewat ini BELUM pernah diberitahukan (lalu ditandai sudah). */
    suspend fun claimMissedReport(courseId: Long, epochDay: Long): Boolean {
        val key = "$courseId:$epochDay"
        var claimed = false
        context.dataStore.edit { prefs ->
            val set = prefs[Keys.MISSED_REPORTED] ?: emptySet()
            if (key !in set) {
                prefs[Keys.MISSED_REPORTED] = recent(set, epochDay) + key
                claimed = true
            }
        }
        return claimed
    }

    /** Buang entri "courseId:epochDay" yang lebih tua dari 7 hari sebelum [epochDay]. */
    private fun recent(set: Set<String>?, epochDay: Long): Set<String> =
        (set ?: emptySet()).filter { (it.substringAfter(':').toLongOrNull() ?: 0L) >= epochDay - 7 }.toSet()

    suspend fun setLastCheck(text: String) = context.dataStore.edit { it[Keys.LAST_CHECK] = text }

    suspend fun setLastCheckDelay(seconds: Long) = context.dataStore.edit { it[Keys.LAST_CHECK_DELAY] = seconds.coerceAtLeast(0) }

    suspend fun setWeeklySummary(enabled: Boolean) = context.dataStore.edit { it[Keys.WEEKLY_SUMMARY] = enabled }

    suspend fun summarySentWeek(): Long? = context.dataStore.data.first()[Keys.SUMMARY_SENT_WEEK]

    suspend fun setSummarySentWeek(mondayEpochDay: Long) = context.dataStore.edit { it[Keys.SUMMARY_SENT_WEEK] = mondayEpochDay }

    /** True bila notifikasi "login gagal" belum tampil hari ini (lalu menandainya sudah). */
    suspend fun claimLoginFailedNotice(): Boolean {
        val today = todayEpochDay()
        var claimed = false
        context.dataStore.edit {
            if (it[Keys.LOGIN_FAILED_DAY] != today) {
                it[Keys.LOGIN_FAILED_DAY] = today
                claimed = true
            }
        }
        return claimed
    }

    /** Gabungkan persentase resmi terbaru (yang lain tetap). */
    suspend fun mergeOfficial(update: Map<String, com.pengingatabsen.logic.OfficialSnapshot>) = context.dataStore.edit { p ->
        val current = com.pengingatabsen.logic.OfficialAttendance.decode(p[Keys.OFFICIAL])
        p[Keys.OFFICIAL] = com.pengingatabsen.logic.OfficialAttendance.encode(current + update)
    }

    suspend fun officialSnapshots(): Map<String, com.pengingatabsen.logic.OfficialSnapshot> =
        com.pengingatabsen.logic.OfficialAttendance.decode(context.dataStore.data.first()[Keys.OFFICIAL])

    /** True bila peringatan [key] belum pernah dikirim (lalu menandainya sudah). Maks. 200 kunci disimpan. */
    suspend fun claimOfficialWarning(key: String): Boolean {
        var claimed = false
        context.dataStore.edit { p ->
            val set = p[Keys.OFFICIAL_WARNED] ?: emptySet()
            if (key !in set) {
                p[Keys.OFFICIAL_WARNED] = (set.toList().takeLast(199) + key).toSet()
                claimed = true
            }
        }
        return claimed
    }

    suspend fun setUpdateCheck(enabled: Boolean) = context.dataStore.edit { it[Keys.UPDATE_CHECK] = enabled }

    suspend fun setUpdateAutoDownload(enabled: Boolean) = context.dataStore.edit { it[Keys.UPDATE_AUTO_DOWNLOAD] = enabled }

    suspend fun setUpdateInstalling(versionCode: Long?) = context.dataStore.edit {
        if (versionCode == null) it.remove(Keys.UPDATE_INSTALLING) else it[Keys.UPDATE_INSTALLING] = versionCode
    }

    /** versionCode pembaruan yang sedang dipasang (lalu dihapus), atau null. */
    suspend fun takeUpdateInstalling(): Long? {
        var value: Long? = null
        context.dataStore.edit {
            value = it[Keys.UPDATE_INSTALLING]
            it.remove(Keys.UPDATE_INSTALLING)
        }
        return value
    }

    suspend fun setMeetings(value: Int) = context.dataStore.edit { it[Keys.MEETINGS] = value.coerceIn(MEETINGS_RANGE) }

    suspend fun setMinPercent(value: Int) = context.dataStore.edit { it[Keys.MIN_PERCENT] = value.coerceIn(0, 100) }

    /** null = hitung semua riwayat. */
    suspend fun setSemesterStart(date: java.time.LocalDate?) = context.dataStore.edit {
        if (date == null) it.remove(Keys.SEMESTER_START) else it[Keys.SEMESTER_START] = date.toEpochDay()
    }

    /** True bila versi [versionCode] belum pernah diberitahukan (lalu menandainya sudah). */
    suspend fun claimUpdateNotice(versionCode: Long): Boolean {
        var claimed = false
        context.dataStore.edit {
            if ((it[Keys.UPDATE_NOTIFIED_CODE] ?: 0L) < versionCode) {
                it[Keys.UPDATE_NOTIFIED_CODE] = versionCode
                claimed = true
            }
        }
        return claimed
    }

    suspend fun layoutSuspects(): String? = context.dataStore.data.first()[Keys.LAYOUT_SUSPECTS]

    suspend fun setLayoutSuspects(value: String) = context.dataStore.edit {
        if (value.isEmpty()) it.remove(Keys.LAYOUT_SUSPECTS) else it[Keys.LAYOUT_SUSPECTS] = value
    }

    /** True bila peringatan "tampilan SiAdin berubah" belum dikirim hari ini (lalu menandainya sudah). */
    suspend fun claimLayoutWarning(): Boolean {
        val today = todayEpochDay()
        var claimed = false
        context.dataStore.edit {
            if (it[Keys.LAYOUT_WARNED_DAY] != today) {
                it[Keys.LAYOUT_WARNED_DAY] = today
                claimed = true
            }
        }
        return claimed
    }

    /** Tambah perkiraan data pengecekan; total di-reset otomatis saat ganti hari. */
    suspend fun addCheckBytes(bytes: Long) = context.dataStore.edit { prefs ->
        val today = todayEpochDay()
        val base = if (prefs[Keys.CHECK_BYTES_DAY] == today) prefs[Keys.CHECK_BYTES] ?: 0 else 0
        prefs[Keys.CHECK_BYTES_DAY] = today
        prefs[Keys.CHECK_BYTES] = base + bytes.coerceAtLeast(0)
    }

    private fun todayEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

    /** Bot token dalam bentuk asli; hanya dipakai saat memanggil Telegram, jangan di-log. */
    suspend fun botToken(): String? =
        context.dataStore.data.first()[Keys.BOT_TOKEN_ENC]?.let(TokenCipher::decrypt)

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }

    suspend fun setTarget(packageName: String, label: String) = context.dataStore.edit {
        it[Keys.TARGET_PACKAGE] = packageName
        it[Keys.TARGET_LABEL] = label
    }

    suspend fun setDeepLink(url: String) = context.dataStore.edit {
        if (url.isBlank()) it.remove(Keys.DEEP_LINK) else it[Keys.DEEP_LINK] = url.trim()
    }

    /** Menyimpan token baru; chat ID lama dihapus karena bisa jadi milik bot lain. */
    suspend fun setBotToken(token: String) = context.dataStore.edit {
        if (token.isBlank()) it.remove(Keys.BOT_TOKEN_ENC) else it[Keys.BOT_TOKEN_ENC] = TokenCipher.encrypt(token.trim())
        it.remove(Keys.CHAT_ID)
        it.remove(Keys.BOT_USERNAME)
    }

    suspend fun setBotUsername(username: String?) = context.dataStore.edit {
        if (username == null) it.remove(Keys.BOT_USERNAME) else it[Keys.BOT_USERNAME] = username
    }

    suspend fun setChatId(chatId: String) = context.dataStore.edit { it[Keys.CHAT_ID] = chatId }

    /** NIM & password SiAdin (didekripsi). Hanya untuk mengisi form login; jangan di-log. */
    suspend fun siadinLogin(): Pair<String, String>? {
        val prefs = context.dataStore.data.first()
        val nim = prefs[Keys.SIADIN_NIM_ENC]?.let(TokenCipher::decrypt) ?: return null
        val password = prefs[Keys.SIADIN_PASSWORD_ENC]?.let(TokenCipher::decrypt) ?: return null
        return nim to password
    }

    suspend fun setSiadinLogin(nim: String, password: String) = context.dataStore.edit {
        it[Keys.SIADIN_NIM_ENC] = TokenCipher.encrypt(nim.trim())
        it[Keys.SIADIN_PASSWORD_ENC] = TokenCipher.encrypt(password)
        it.remove(Keys.LOGIN_FAILED_DAY)
    }

    suspend fun clearSiadinLogin() = context.dataStore.edit {
        it.remove(Keys.SIADIN_NIM_ENC)
        it.remove(Keys.SIADIN_PASSWORD_ENC)
    }

    suspend fun unmarkPresensiOpen(courseId: Long, epochDay: Long) = context.dataStore.edit { prefs ->
        prefs[Keys.PRESENSI_OPEN] = (prefs[Keys.PRESENSI_OPEN] ?: emptySet()) - "$courseId:$epochDay"
    }

    /** Berapa kali berturut-turut pengecekan presensi gagal untuk satu kemunculan. */
    suspend fun presensiUnknownStreak(courseId: Long, epochDay: Long): Int =
        context.dataStore.data.first()[unknownKey(courseId, epochDay)] ?: 0

    suspend fun setPresensiUnknownStreak(courseId: Long, epochDay: Long, value: Int) = context.dataStore.edit {
        if (value <= 0) it.remove(unknownKey(courseId, epochDay)) else it[unknownKey(courseId, epochDay)] = value
    }

    private fun unknownKey(courseId: Long, epochDay: Long) = intPreferencesKey("presensi_unknown_${courseId}_$epochDay")

    suspend fun setFullScreenAlert(enabled: Boolean) = context.dataStore.edit { it[Keys.FULL_SCREEN_ALERT] = enabled }

    suspend fun setSmartPresensi(enabled: Boolean) = context.dataStore.edit { it[Keys.SMART_PRESENSI] = enabled }

    suspend fun isPresensiOpen(courseId: Long, epochDay: Long): Boolean =
        context.dataStore.data.first()[Keys.PRESENSI_OPEN]?.contains("$courseId:$epochDay") == true

    /** Tandai presensi sudah dibuka; sekalian buang catatan lebih dari 7 hari. */
    suspend fun markPresensiOpen(courseId: Long, epochDay: Long) = context.dataStore.edit { prefs ->
        val fresh = (prefs[Keys.PRESENSI_OPEN] ?: emptySet()).filter { entry ->
            val day = entry.substringAfter(':').toLongOrNull() ?: return@filter false
            day >= epochDay - 7
        }.toSet()
        prefs[Keys.PRESENSI_OPEN] = fresh + "$courseId:$epochDay"
    }

    suspend fun setAutoLogin(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_LOGIN] = enabled }

    suspend fun setVibrateOnly(enabled: Boolean) = context.dataStore.edit { it[Keys.VIBRATE_ONLY] = enabled }

    suspend fun setRemindInterval(minutes: Int) = context.dataStore.edit {
        it[Keys.REMIND_INTERVAL] = minutes.coerceIn(1, 30)
    }
}
