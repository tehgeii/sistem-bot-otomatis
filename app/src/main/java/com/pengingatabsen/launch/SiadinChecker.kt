package com.pengingatabsen.launch

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class PresensiState { WAITING, OPEN, UNKNOWN }

/**
 * Mengecek halaman Presensi Online SiAdin di latar belakang dengan WebView tak terlihat.
 * Memakai cookie/sesi yang sama dengan browser mini dan login otomatis yang sama.
 * HANYA membaca status halaman; tidak pernah menekan tombol presensi.
 */
object SiadinChecker {
    private const val TOTAL_TIMEOUT_MS = 45_000L
    private const val MAX_PAGES = 8
    private const val MAX_LOGIN_ATTEMPTS = 2

    suspend fun check(context: Context, targetUrl: String, credentials: Pair<String, String>?): PresensiState =
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { runCheck(context.applicationContext, targetUrl, credentials) }
        } ?: PresensiState.UNKNOWN

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runCheck(context: Context, targetUrl: String, credentials: Pair<String, String>?): PresensiState {
        val pages = Channel<String>(Channel.CONFLATED)
        val webView = WebView(context)
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = webView.settings.userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
            CookieManager.getInstance().setAcceptCookie(true)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != null) pages.trySend(url)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (url != null) pages.trySend(url)
                }
            }

            // Lewat halaman depan dulu bila ada data login: website tidak selalu mengarahkan ke login.
            webView.loadUrl(if (credentials != null) SiadinScripts.siteRoot(targetUrl) else targetUrl)
            var loginAttempts = 0

            repeat(MAX_PAGES) {
                val url = pages.receive()
                if (!SiadinScripts.isTrusted(url, targetUrl)) return PresensiState.UNKNOWN
                delay(700) // beri waktu halaman SPA merender form/isi

                val canFill = credentials != null && loginAttempts < MAX_LOGIN_ATTEMPTS
                val loginScript = if (canFill) SiadinScripts.fillLoginScript(credentials!!.first, credentials.second)
                else SiadinScripts.DETECT_LOGIN_SCRIPT
                when (webView.eval(loginScript)) {
                    "SUBMITTED" -> {
                        loginAttempts++
                        return@repeat // tunggu halaman setelah login
                    }
                    "LOGIN_PAGE" -> return PresensiState.UNKNOWN // tidak bisa login
                }

                if (!url.startsWith(targetUrl)) {
                    webView.loadUrl(targetUrl)
                    return@repeat
                }

                // Di halaman presensi: tunggu data akun & presensi termuat (maks. ±15 detik).
                repeat(15) {
                    when (webView.eval(SiadinScripts.PRESENSI_STATE_SCRIPT)) {
                        "WAITING" -> return PresensiState.WAITING
                        "OPEN" -> return PresensiState.OPEN
                        "LOGIN" -> return PresensiState.UNKNOWN
                    }
                    delay(1_000)
                }
                return PresensiState.UNKNOWN
            }
            return PresensiState.UNKNOWN
        } finally {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun WebView.eval(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { raw -> if (cont.isActive) cont.resume(raw?.trim('"')) }
    }
}
