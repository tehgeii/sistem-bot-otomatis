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
    private const val TOTAL_TIMEOUT_MS = 75_000L
    private const val MAX_PAGES = 8
    private const val MAX_LOGIN_ATTEMPTS = 2
    /** Lama maksimal menunggu kartu presensi termuat di satu halaman (detik). */
    private const val POLL_SECONDS = 30
    /** Kartu terbaca sama sekian kali berturut-turut (±1 dtk sekali) baru dipercaya. */
    private const val STABLE_CARD = 3
    /** "Belum Ada Presensi"/tanpa kartu baru dipercaya setelah sekian kali berturut-turut. */
    private const val STABLE_EMPTY = 10

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

                if (!SiadinScripts.isTargetPage(url, targetUrl)) {
                    webView.loadUrl(targetUrl)
                    return@repeat
                }

                // Di halaman presensi: tunggu data akun & kartu presensi termuat (maks. ±30 detik).
                // Setiap status baru dipercaya bila terlihat beberapa kali BERTURUT-TURUT, supaya tampilan
                // sementara saat memuat (mis. "Belum Ada Presensi" sebelum kartu muncul) tidak menipu.
                val stateScript = SiadinScripts.presensiStateScript(courseName)
                var last: String? = null
                var streak = 0
                var relogin = false
                poll@ for (i in 0 until POLL_SECONDS) {
                    val result = webView.eval(stateScript)
                    streak = if (result == last) streak + 1 else 1
                    last = result
                    when (result) {
                        "LOGIN" -> {
                            // Cookie lama tapi sesi sudah habis: login ulang lewat halaman depan, lalu cek lagi.
                            if (credentials == null) return PresensiState.UNKNOWN
                            if (loginAttempts >= MAX_LOGIN_ATTEMPTS) return PresensiState.LOGIN_FAILED
                            loginAttempts++
                            relogin = true
                            webView.loadUrl(SiadinScripts.siteRoot(targetUrl))
                            break@poll
                        }
                        "WAITING" -> if (streak >= STABLE_CARD) return PresensiState.WAITING
                        "BUTTON" -> if (streak >= STABLE_CARD) return PresensiState.OPEN
                        "DONE" -> if (streak >= STABLE_CARD) return PresensiState.DONE
                        // Tanpa kartu: tunggu lebih lama, daftar kartu SiAdin sering termuat belakangan.
                        "EMPTY" -> if (streak >= STABLE_EMPTY) return PresensiState.WAITING
                        "NO_TEXT" -> if (streak >= STABLE_EMPTY) return PresensiState.UNKNOWN
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
