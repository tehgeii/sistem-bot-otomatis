package com.pengingatabsen.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pengingatabsen.launch.TargetApps
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
) {
    /** Mode pintar hanya berlaku untuk SiAdin web dengan login tersimpan. */
    val smartModeActive: Boolean
        get() = smartPresensi && hasSiadinLogin && deepLink?.startsWith(TargetApps.SIADIN_ORIGIN) == true

    val telegramReady: Boolean get() = hasBotToken && !chatId.isNullOrBlank()
}

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
        /** Kemunculan ("courseId:epochDay") yang presensinya sudah terlihat dibuka dosen. */
        val PRESENSI_OPEN = stringSetPreferencesKey("presensi_open")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

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
    )

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
