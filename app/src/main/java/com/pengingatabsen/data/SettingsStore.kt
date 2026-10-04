package com.pengingatabsen.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
) {
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

    suspend fun setVibrateOnly(enabled: Boolean) = context.dataStore.edit { it[Keys.VIBRATE_ONLY] = enabled }

    suspend fun setRemindInterval(minutes: Int) = context.dataStore.edit {
        it[Keys.REMIND_INTERVAL] = minutes.coerceIn(1, 30)
    }
}
