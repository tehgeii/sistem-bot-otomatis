package com.pengingatabsen.launch

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
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

enum class PresensiState {
    /** Belum dibuka dosen ("Belum Ada Presensi" / "Belum Jadwalnya"). */
    WAITING,
    /** Tombol presensi aktif. */
    OPEN,
    /** Kartu matkul ini sudah "Berhasil Presensi". */
    DONE,
    /** Gagal memastikan (offline, halaman tidak dikenali). */
    UNKNOWN,
    /** Form login tetap tampil setelah NIM/password dikirim: data login kemungkinan salah/berubah. */
    LOGIN_FAILED,
}

/**
 * Mengecek halaman Presensi Online SiAdin di latar belakang dengan WebView tak terlihat.
 * Memakai cookie/sesi yang sama dengan browser mini dan login otomatis yang sama.
 * HANYA membaca status halaman; tidak pernah menekan tombol presensi.
 */
object SiadinChecker {
    private const val TOTAL_TIMEOUT_MS = 55_000L
    private const val MAX_PAGES = 8
    private const val MAX_LOGIN_ATTEMPTS = 2

    suspend fun check(
        context: Context,
        targetUrl: String,
        credentials: Pair<String, String>?,
        courseName: String,
    ): PresensiState =
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { runCheck(context.applicationContext, targetUrl, credentials, courseName) }
        } ?: PresensiState.UNKNOWN

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runCheck(
        context: Context,
        targetUrl: String,
        credentials: Pair<String, String>?,
        courseName: String,
    ): PresensiState {
        val pages = Channel<String>(Channel.CONFLATED)
        val webView = WebView(context)
        try {
            // WebView tak terlihat berukuran 0×0: tanpa ukuran, tata letak & deteksi elemen tidak akurat.
            val width = 1080
            val height = 2400
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, width, height)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            // Hemat kuota: deteksi presensi memakai teks, jadi gambar (logo, dsb.) tidak perlu diunduh.
            webView.settings.loadsImagesAutomatically = false
            webView.settings.blockNetworkImage = true
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

            // Hemat kuota: bila sesi login (cookie) masih ada, langsung ke halaman presensi.
            // Hanya mampir halaman depan untuk login bila belum ada cookie (sesi habis / pertama kali).
            val hasCookie = !CookieManager.getInstance().getCookie(targetUrl).isNullOrBlank()
            webView.loadUrl(if (credentials != null && !hasCookie) SiadinScripts.siteRoot(targetUrl) else targetUrl)
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
                    // Form login masih tampil setelah dikirim → NIM/password ditolak; tanpa data login → tak bisa cek.
                    "LOGIN_PAGE" -> return if (credentials != null && loginAttempts > 0) PresensiState.LOGIN_FAILED
                    else PresensiState.UNKNOWN
                }

                if (!url.startsWith(targetUrl)) {
                    webView.loadUrl(targetUrl)
                    return@repeat
                }

                // Di halaman presensi: tunggu data akun & kartu presensi termuat (maks. ±20 detik).
                // "Dibuka" baru dipercaya bila tombol presensi terlihat 3 kali berturut-turut (±3 detik),
                // supaya kartu "Belum Ada Presensi" yang dimuat belakangan tidak disangka dibuka.
                val stateScript = SiadinScripts.presensiStateScript(courseName)
                var buttonStreak = 0
                var doneStreak = 0
                var noTextStreak = 0
                var relogin = false
                poll@ for (i in 0 until 20) {
                    when (webView.eval(stateScript)) {
                        "WAITING" -> return PresensiState.WAITING
                        "LOGIN" -> {
                            // Cookie lama tapi sesi sudah habis: login ulang lewat halaman depan, lalu cek lagi.
                            if (credentials == null) return PresensiState.UNKNOWN
                            if (loginAttempts >= MAX_LOGIN_ATTEMPTS) return PresensiState.LOGIN_FAILED
                            loginAttempts++
                            relogin = true
                            webView.loadUrl(SiadinScripts.siteRoot(targetUrl))
                            break@poll
                        }
                        "BUTTON" -> {
                            doneStreak = 0; noTextStreak = 0
                            if (++buttonStreak >= 3) return PresensiState.OPEN
                        }
                        "DONE" -> {
                            buttonStreak = 0; noTextStreak = 0
                            if (++doneStreak >= 3) return PresensiState.DONE
                        }
                        "NO_TEXT" -> {
                            buttonStreak = 0; doneStreak = 0
                            if (++noTextStreak >= 8) return PresensiState.UNKNOWN
                        }
                        else -> {
                            buttonStreak = 0; doneStreak = 0; noTextStreak = 0
                        }
                    }
                    delay(1_000)
                }
                if (!relogin) return PresensiState.UNKNOWN
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
