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
import com.pengingatabsen.telegram.TelegramClient
import com.pengingatabsen.telegram.TgResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

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

    fun setVibrateOnly(enabled: Boolean) = viewModelScope.launch { store.setVibrateOnly(enabled) }

    fun finishOnboarding(context: Context) = viewModelScope.launch {
        store.setOnboardingDone(true)
        AlarmScheduler.rescheduleAll(context.applicationContext)
    }
}
