package com.pengingatabsen.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.AlarmScheduler
import com.pengingatabsen.data.AppSettings
import com.pengingatabsen.launch.InstalledApp
import com.pengingatabsen.launch.TargetApps
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.telegram.SummaryWorker
import com.pengingatabsen.telegram.TelegramClient
import com.pengingatabsen.telegram.TgResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Batas wajar ukuran file cadangan (riwayat bertahun-tahun pun jauh di bawah ini). */
private const val MAX_BACKUP_BYTES = 20 * 1024 * 1024

/** State untuk wizard & layar Pengaturan (aplikasi tujuan, Telegram, interval). */
class SetupViewModel : ViewModel() {
    private val store = Graph.settings

    val settings: StateFlow<AppSettings?> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var tokenStatus by mutableStateOf<String?>(null)
        private set
    var chatStatus by mutableStateOf<String?>(null)
        private set
    var testStatus by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var polling by mutableStateOf(false)
        private set

    private var pollJob: Job? = null

    // ---------- Kehadiran (jatah tidak hadir) ----------

    fun setMeetings(value: Int) = viewModelScope.launch { store.setMeetings(value) }
    fun setMinPercent(value: Int) = viewModelScope.launch { store.setMinPercent(value) }
    fun setSemesterStart(date: java.time.LocalDate?) = viewModelScope.launch { store.setSemesterStart(date) }

    // ---------- Tentang & versi baru ----------

    var updateStatus by mutableStateOf<String?>(null)
        private set
    var updateAvailable by mutableStateOf(false)
        private set
    /** Sidik jari sertifikat APK terpasang sama dengan rilis resmi? null = belum dicek / tak bisa dibandingkan. */
    var certMatches by mutableStateOf<Boolean?>(null)
        private set
    var checkingUpdate by mutableStateOf(false)
        private set

    fun installedVersion(context: Context) = runCatching { com.pengingatabsen.update.UpdateChecker.installed(context) }.getOrNull()

    fun checkUpdateNow(context: Context) = viewModelScope.launch {
        if (checkingUpdate) return@launch
        checkingUpdate = true
        updateStatus = "Mengecek Release di GitHub…"
        val remote = com.pengingatabsen.update.UpdateChecker.fetchRemote()
        val mine = installedVersion(context)
        if (remote == null || mine == null) {
            updateStatus = "Tidak bisa mengecek (internet?). Coba lagi nanti."
            updateAvailable = false
        } else {
            certMatches = com.pengingatabsen.logic.AppUpdate.sameCert(mine.certSha256, remote.certSha256)
            updateAvailable = com.pengingatabsen.logic.AppUpdate.isNewer(remote, mine.versionCode)
            updateStatus = if (updateAvailable) {
                "Versi baru ${remote.versionName} tersedia (terpasang ${mine.versionName})."
            } else {
                "Sudah versi terbaru (${mine.versionName})."
            }
        }
        checkingUpdate = false
    }

    fun setUpdateCheck(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setUpdateCheck(enabled)
        com.pengingatabsen.update.UpdateChecker.schedule(context.applicationContext, enabled)
    }

    fun setUpdateAutoDownload(enabled: Boolean) = viewModelScope.launch { store.setUpdateAutoDownload(enabled) }

    // ---------- Penjaga presensi (3.3.2) ----------

    fun setTelegramNudge(enabled: Boolean) = viewModelScope.launch { store.setTelegramNudge(enabled) }
    fun setDndAllowed(allowed: Boolean) = viewModelScope.launch { store.setDndAllowed(allowed) }
    fun setScheduleDiffCheck(enabled: Boolean) = viewModelScope.launch { store.setScheduleDiffCheck(enabled) }

    fun setPreClass(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setPreClass(enabled)
        com.pengingatabsen.alarm.AlarmScheduler.schedulePreClass(context.applicationContext)
    }

    fun setPreClassLead(minutes: Int, context: Context) = viewModelScope.launch {
        store.setPreClassLead(minutes)
        com.pengingatabsen.alarm.AlarmScheduler.schedulePreClass(context.applicationContext)
    }

    fun setQuietCheck(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setQuietCheck(enabled)
        com.pengingatabsen.alarm.AlarmScheduler.schedulePreClass(context.applicationContext)
    }

    fun setBatteryCheck(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setBatteryCheck(enabled)
        com.pengingatabsen.alarm.AlarmScheduler.schedulePreClass(context.applicationContext)
    }

    fun setRadar(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setRadar(enabled)
        com.pengingatabsen.alarm.RadarWorker.schedule(context.applicationContext, enabled && store.current().smartModeActive)
    }

    // ---------- Cadangan & pulihkan (pindah HP) ----------

    sealed class BackupUi {
        data object Idle : BackupUi()
        data class Working(val message: String) : BackupUi()
        /** File cadangan terbaca; menunggu konfirmasi karena SEMUA data di HP ini akan diganti. */
        data class Preview(val backup: com.pengingatabsen.logic.Backup, val summary: String) : BackupUi()
        data class Done(val message: String) : BackupUi()
        data class Failed(val message: String) : BackupUi()
    }

    var backupUi by mutableStateOf<BackupUi>(BackupUi.Idle)
        private set

    fun closeBackup() { backupUi = BackupUi.Idle }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Tulis cadangan ke file yang dipilih pengguna (Storage Access Framework). */
    fun exportBackup(context: Context, uri: android.net.Uri) = viewModelScope.launch {
        backupUi = BackupUi.Working("Menyimpan cadangan…")
        backupUi = runCatching {
            val backup = Graph.repository.buildBackup(appVersion(context))
            val bytes = com.pengingatabsen.logic.BackupCodec.encode(backup).toByteArray(Charsets.UTF_8)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val resolver = context.contentResolver
                val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri)
                checkNotNull(out) { "file tidak bisa ditulis" }.use { it.write(bytes) }
            }
            com.pengingatabsen.data.DiagLog.add("cadangan: disimpan (${backup.courses.size} jadwal, ${backup.records.size} riwayat)")
            BackupUi.Done(
                "Cadangan tersimpan: ${backup.courses.size} jadwal, ${backup.records.size} riwayat, dan pengaturan.\n\n" +
                    "Simpan file ini di tempat aman (mis. Google Drive). NIM/password SiAdin dan bot Telegram " +
                    "sengaja tidak ikut; isi ulang di HP baru.",
            )
        }.getOrElse { BackupUi.Failed("Gagal menyimpan cadangan (${it.message ?: it.javaClass.simpleName}).") }
    }

    /** Baca file cadangan lalu tampilkan ringkasan untuk dikonfirmasi. */
    fun readBackup(context: Context, uri: android.net.Uri) = viewModelScope.launch {
        backupUi = BackupUi.Working("Membaca file cadangan…")
        backupUi = runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                checkNotNull(context.contentResolver.openInputStream(uri)) { "file tidak bisa dibuka" }.use { input ->
                    // Dibaca bertahap dengan batas ukuran: file salah pilih (video, dsb.) tidak membuat aplikasi kehabisan memori.
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                        require(out.size() <= MAX_BACKUP_BYTES) { "File terlalu besar, ini bukan cadangan NgiBsen." }
                    }
                    out.toString("UTF-8")
                }
            }
            val backup = com.pengingatabsen.logic.BackupCodec.decode(text)
            val created = runCatching { Formatters.dateTime(LocalDateTime.parse(backup.createdAt)) }.getOrDefault(backup.createdAt)
            val oneOffs = backup.courses.count { it.oneOffEpochDay != null }
            BackupUi.Preview(
                backup,
                "Cadangan dibuat $created (NgiBsen ${backup.appVersion.ifBlank { "?" }}):\n" +
                    "• ${backup.courses.size} jadwal" + (if (oneOffs > 0) " (termasuk $oneOffs kelas pengganti)" else "") + "\n" +
                    "• ${backup.records.size} riwayat\n" +
                    "• ${backup.settings.size} pengaturan\n\n" +
                    "SEMUA jadwal & riwayat di HP ini akan DIGANTI isi cadangan. Foto bukti, NIM/password SiAdin, " +
                    "dan bot Telegram tidak ikut — isi ulang setelah ini.",
            )
        }.getOrElse {
            val msg = (it as? IllegalArgumentException)?.message ?: "File tidak bisa dibaca (${it.message ?: it.javaClass.simpleName})."
            BackupUi.Failed(msg)
        }
    }

    /** Salinan otomatis data HP ini sesaat sebelum memulihkan (untuk "Kembalikan data sebelum pemulihan"). */
    private fun safetyFile(context: Context) = java.io.File(context.filesDir, "sebelum-pulihkan.json")

    fun hasSafetyCopy(context: Context): Boolean = safetyFile(context).exists()

    /** Tampilkan pratinjau salinan otomatis sebelum pemulihan terakhir (lalu dikonfirmasi seperti biasa). */
    fun readSafetyCopy(context: Context) = readBackup(context, android.net.Uri.fromFile(safetyFile(context)))

    fun confirmRestore(context: Context) = viewModelScope.launch {
        val preview = backupUi as? BackupUi.Preview ?: return@launch
        backupUi = BackupUi.Working("Memulihkan data…")
        // Simpan dulu data HP ini; kalau salah pilih file, bisa dikembalikan. Gagal menyimpan = batal memulihkan.
        val saved = runCatching {
            val current = com.pengingatabsen.logic.BackupCodec.encode(Graph.repository.buildBackup(appVersion(context)))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { safetyFile(context).writeText(current) }
        }
        if (saved.isFailure) {
            backupUi = BackupUi.Failed("Batal: salinan data HP ini tidak bisa dibuat dulu, jadi tidak ada yang diubah.")
            return@launch
        }
        backupUi = runCatching {
            val result = Graph.repository.restoreBackup(preview.backup)
            com.pengingatabsen.data.DiagLog.add(
                "cadangan: dipulihkan (${preview.backup.courses.size} jadwal, ${result.records} riwayat)" +
                    if (result.problems.isEmpty()) "" else " — ${result.problems.joinToString("; ")}",
            )
            val warn = if (result.problems.isEmpty()) "Alarm sudah dipasang ulang." else "⚠️ ${result.problems.joinToString("; ")}."
            BackupUi.Done(
                "Data dipulihkan: ${preview.backup.courses.size} jadwal, ${result.records} riwayat. $warn\n\n" +
                    "Langkah berikutnya: isi NIM/password di SiAdin web dan sambungkan bot Telegram lagi.\n\n" +
                    "Salah file? Pengaturan → Cadangan data → \"Kembalikan data sebelum pemulihan\".",
            )
        }.getOrElse {
            // Penggantian jadwal & riwayat berjalan dalam satu transaksi: bila gagal di sini, tidak ada yang berubah.
            BackupUi.Failed("Gagal memulihkan; jadwal & riwayat di HP ini tidak berubah (${it.message ?: it.javaClass.simpleName}).")
        }
    }

    // ---------- Aplikasi tujuan ----------

    fun installedApps(context: Context): List<InstalledApp> = TargetApps.installed(context)

    fun chooseApp(app: InstalledApp) = viewModelScope.launch { store.setTarget(app.packageName, app.label) }

    /** Pilih Dinusverse otomatis bila terdeteksi dan belum ada pilihan. */
    fun autoPickTarget(context: Context) = viewModelScope.launch {
        if (store.current().targetPackage == null) {
            TargetApps.guess(context)?.let { store.setTarget(it.packageName, it.label) }
        }
    }

    fun setDeepLink(url: String) = viewModelScope.launch { store.setDeepLink(url) }

    // ---------- Telegram ----------

    /** Simpan token setelah divalidasi dengan getMe, lalu mulai menunggu /start. */
    fun saveToken(token: String) = viewModelScope.launch {
        val clean = token.trim()
        if (clean.isEmpty()) {
            tokenStatus = "Token masih kosong"
            return@launch
        }
        busy = true
        tokenStatus = "Memeriksa token…"
        when (val r = TelegramClient.getMe(clean)) {
            is TgResult.Ok -> {
                store.setBotToken(clean)
                val username = TelegramClient.botUsername(r)
                store.setBotUsername(username)
                tokenStatus = "Token benar${username?.let { " (bot @$it)" }.orEmpty()}."
                startPolling()
            }
            is TgResult.Error -> tokenStatus = r.message
        }
        busy = false
    }

    /** Cek getUpdates tiap 3 detik (maks. 3 menit) sampai ada pesan /start. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            polling = true
            chatStatus = "Menunggu kamu mengirim /start ke bot…"
            repeat(60) {
                if (checkChatOnce()) {
                    polling = false
                    return@launch
                }
                delay(3_000)
            }
            polling = false
            chatStatus = "Belum ada pesan. Kirim /start ke bot, lalu tekan \"Cek lagi\"."
        }
    }

    private suspend fun checkChatOnce(): Boolean {
        val token = store.botToken() ?: return false
        return when (val r = TelegramClient.getUpdates(token)) {
            is TgResult.Ok -> {
                val chat = TelegramClient.findChat(r) ?: return false
                store.setChatId(chat.id)
                chatStatus = "Terhubung dengan ${chat.name.ifBlank { "chat ${chat.id}" }}."
                true
            }
            is TgResult.Error -> {
                chatStatus = r.message
                false
            }
        }
    }

    fun testSend() = viewModelScope.launch {
        val token = store.botToken()
        val chat = store.current().chatId
        if (token == null || chat == null) {
            testStatus = "Selesaikan pengaturan Telegram dulu"
            return@launch
        }
        busy = true
        testStatus = "Mengirim…"
        val text = "🔔 Tes dari NgiBsen UDINUS — ${Formatters.dateTime(LocalDateTime.now())}"
        testStatus = when (val r = TelegramClient.sendMessage(token, chat, text)) {
            is TgResult.Ok -> "Terkirim! Cek Telegram kamu."
            is TgResult.Error -> "Gagal: ${r.message}"
        }
        busy = false
    }

    // ---------- Lain-lain ----------

    fun setInterval(minutes: Int, context: Context) = viewModelScope.launch {
        store.setRemindInterval(minutes)
        AlarmScheduler.rescheduleAll(context.applicationContext)
    }

    // ---------- SiAdin web & login otomatis ----------

    /** NIM tersimpan (untuk ditampilkan); password tidak pernah ditampilkan. */
    var savedNim by mutableStateOf<String?>(null)
        private set
    var loginStatus by mutableStateOf<String?>(null)
        private set

    fun loadSavedNim() = viewModelScope.launch { savedNim = store.siadinLogin()?.first }

    fun useSiadinWeb() = viewModelScope.launch { store.setDeepLink(TargetApps.SIADIN_PRESENSI_URL) }

    fun saveSiadinLogin(nim: String, password: String) = viewModelScope.launch {
        if (nim.isBlank() || password.isEmpty()) {
            loginStatus = "NIM dan password wajib diisi"
            return@launch
        }
        store.setSiadinLogin(nim, password)
        savedNim = nim.trim()
        loginStatus = "Tersimpan terenkripsi di HP ini."
    }

    fun clearSiadinLogin() = viewModelScope.launch {
        store.clearSiadinLogin()
        savedNim = null
        loginStatus = "Data login dihapus."
    }

    fun setAutoLogin(enabled: Boolean) = viewModelScope.launch { store.setAutoLogin(enabled) }

    fun setFullScreenAlert(enabled: Boolean) = viewModelScope.launch { store.setFullScreenAlert(enabled) }

    fun setSmartPresensi(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setSmartPresensi(enabled)
        AlarmScheduler.rescheduleAll(context.applicationContext)
    }

    fun setWeeklySummary(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setWeeklySummary(enabled)
        SummaryWorker.schedule(context.applicationContext, enabled)
    }

    fun sendSummaryNow(context: Context) {
        SummaryWorker.sendNow(context.applicationContext)
        testStatus = "Ringkasan minggu ini dikirim ke Telegram (butuh internet)."
    }

    // ---------- Diagnosis ----------

    var diagBusy by mutableStateOf(false)
        private set
    var diagResult by mutableStateOf<String?>(null)
        private set

    /**
     * "Tes cek sekarang": jalankan pengecek SiAdin latar (persis yang dipakai saat kuliah) untuk matkul
     * yang sedang berlangsung, atau matkul aktif berikutnya. Hanya membaca & menampilkan hasil;
     * notifikasi dan riwayat tidak diubah.
     */
    fun testCheckNow(context: Context) = viewModelScope.launch {
        if (diagBusy) return@launch
        diagBusy = true
        diagResult = "Mengecek SiAdin seperti saat kuliah… (bisa sampai ±2 menit)"
        val settings = store.current()
        val now = LocalDateTime.now()
        val grace = if (settings.smartModeActive) com.pengingatabsen.logic.ScheduleMath.SMART_GRACE_MINUTES else 0
        val courses = Graph.repository.allCourses().filter { it.active }
        val course = courses.firstOrNull { com.pengingatabsen.logic.ScheduleMath.currentOccurrence(it.toSlot(grace), now) != null }
            ?: courses.mapNotNull { c -> com.pengingatabsen.logic.ScheduleMath.nextOccurrence(c.toSlot(grace), now)?.let { c to it.open } }
                .minByOrNull { it.second }?.first
        if (course == null) {
            diagResult = "Belum ada jadwal aktif untuk dites."
            diagBusy = false
            return@launch
        }
        val credentials = if (settings.autoLogin) store.siadinLogin() else null
        com.pengingatabsen.data.DiagLog.add("tes manual: ${course.name}")
        // Dua cara menggambar dibandingkan: layar virtual (dipakai saat kuliah) vs WebView tanpa jendela (cara lama).
        val lines = mutableListOf("Matkul: ${course.name}")
        if (credentials == null) lines += "(Data login belum disimpan / login otomatis mati.)"
        for (mode in listOf(com.pengingatabsen.launch.RenderMode.VIRTUAL_DISPLAY, com.pengingatabsen.launch.RenderMode.DETACHED)) {
            val label = if (mode == com.pengingatabsen.launch.RenderMode.VIRTUAL_DISPLAY) "Layar virtual (dipakai)" else "Cara lama"
            diagResult = (lines + "$label: mengecek… (bisa sampai ±1 menit)").joinToString("\n")
            val result = com.pengingatabsen.launch.SiadinChecker.check(
                context.applicationContext,
                settings.deepLink ?: TargetApps.SIADIN_PRESENSI_URL,
                credentials,
                course.name,
                mode,
            ) { com.pengingatabsen.data.DiagLog.add("tes ${course.name} [$label]: $it") }
            com.pengingatabsen.data.DiagLog.add("tes HASIL ${course.name} [$label]: ${result.state} — ${result.detail}")
            val meaning = when (result.state) {
                com.pengingatabsen.launch.PresensiState.WAITING -> "✅ terbaca: presensi BELUM dibuka"
                com.pengingatabsen.launch.PresensiState.OPEN -> "✅ terbaca: presensi SUDAH DIBUKA"
                com.pengingatabsen.launch.PresensiState.DONE -> "✅ terbaca: \"Berhasil Presensi\""
                com.pengingatabsen.launch.PresensiState.LOGIN_FAILED -> "❌ login ditolak SiAdin — cek NIM/password"
                com.pengingatabsen.launch.PresensiState.UNKNOWN -> "⚠️ gagal membaca"
            }
            lines += "\n$label: $meaning\nDetail: ${result.detail}"
        }
        diagResult = lines.joinToString("\n")
        diagBusy = false
    }

    fun setReadinessCheck(enabled: Boolean, context: Context) = viewModelScope.launch {
        store.setReadinessCheck(enabled)
        AlarmScheduler.schedulePreflight(context.applicationContext)
    }

    fun readLog(): String = com.pengingatabsen.data.DiagLog.read()

    fun clearLog() = com.pengingatabsen.data.DiagLog.clear()

    fun setVibrateOnly(enabled: Boolean) = viewModelScope.launch { store.setVibrateOnly(enabled) }

    fun finishOnboarding(context: Context) = viewModelScope.launch {
        store.setOnboardingDone(true)
        AlarmScheduler.rescheduleAll(context.applicationContext)
    }
}
