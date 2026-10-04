package com.pengingatabsen.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Hasil panggilan Bot API. Pesan error tidak pernah memuat token. */
sealed class TgResult {
    data class Ok(val result: Any?) : TgResult()
    data class Error(val message: String, val retryable: Boolean) : TgResult()
}

data class ChatInfo(val id: String, val name: String)

/** Klien minimal Telegram Bot API (getMe, getUpdates, sendMessage, sendPhoto). */
object TelegramClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun getMe(token: String): TgResult = call(token, "getMe", FormBody.Builder().build())

    suspend fun getUpdates(token: String): TgResult = call(token, "getUpdates", FormBody.Builder().build())

    suspend fun sendMessage(token: String, chatId: String, text: String): TgResult =
        call(token, "sendMessage", FormBody.Builder().add("chat_id", chatId).add("text", text).build())

    suspend fun sendPhoto(token: String, chatId: String, photo: File, caption: String): TgResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("photo", photo.name, photo.asRequestBody("image/jpeg".toMediaType()))
            .build()
        return call(token, "sendPhoto", body)
    }

    /** Username bot dari hasil getMe. */
    fun botUsername(result: TgResult.Ok): String? = (result.result as? JSONObject)?.optString("username")?.takeIf { it.isNotBlank() }

    /**
     * Cari chat dari hasil getUpdates. Utamakan pesan "/start" terakhir,
     * kalau tidak ada pakai pesan pribadi terakhir apa pun.
     */
    fun findChat(result: TgResult.Ok): ChatInfo? {
        val updates = result.result as? org.json.JSONArray ?: return null
        var any: ChatInfo? = null
        var start: ChatInfo? = null
        for (i in 0 until updates.length()) {
            val msg = updates.optJSONObject(i)?.optJSONObject("message") ?: continue
            val chat = msg.optJSONObject("chat") ?: continue
            if (!chat.has("id")) continue
            val name = listOf(chat.optString("first_name"), chat.optString("last_name"))
                .filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { chat.optString("title").ifBlank { chat.optString("username") } }
            val info = ChatInfo(chat.get("id").toString(), name)
            any = info
            if (msg.optString("text").startsWith("/start")) start = info
        }
        return start ?: any
    }

    private suspend fun call(token: String, method: String, body: RequestBody): TgResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext TgResult.Error("Bot token belum diisi", retryable = false)
        val request = Request.Builder().url("https://api.telegram.org/bot$token/$method").post(body).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (response.isSuccessful && json?.optBoolean("ok") == true) {
                    TgResult.Ok(json.opt("result"))
                } else {
                    val code = response.code
                    val desc = json?.optString("description")?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                    val message = when (code) {
                        401, 404 -> "Bot token salah ($desc)"
                        403 -> "Bot diblokir atau belum di-/start ($desc)"
                        else -> desc
                    }
                    TgResult.Error(message.replace(token, "***"), retryable = code == 429 || code >= 500)
                }
            }
        } catch (e: IOException) {
            val msg = (e.message ?: e.javaClass.simpleName).replace(token, "***")
            TgResult.Error("Tidak ada koneksi ($msg)", retryable = true)
        }
    }
}
