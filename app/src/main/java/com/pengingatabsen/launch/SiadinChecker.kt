package com.pengingatabsen.launch

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pengingatabsen.logic.CheckerBrain
import com.pengingatabsen.logic.Outcome
import com.pengingatabsen.logic.PageKind
import com.pengingatabsen.logic.Probe
import com.pengingatabsen.logic.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume

enum class PresensiState {
    /** Belum dibuka dosen ("Belum Ada Presensi" / "Belum Jadwalnya"). */
    WAITING,
    /** Tombol presensi aktif. */
    OPEN,
    /** Kartu matkul ini sudah "Berhasil Presensi". */
    DONE,
    /** Gagal memastikan (offline, belum login, halaman tidak dikenali). */
    UNKNOWN,
    /** Form login tetap tampil setelah NIM/password dikirim: data login kemungkinan salah/berubah. */
    LOGIN_FAILED,
}

/** Hasil satu pengecekan + keterangan untuk log diagnosis (tanpa data rahasia). */
data class CheckResult(val state: PresensiState, val detail: String)

/** Cara WebView pengecek "digambar". */
enum class RenderMode {
    /**
     * WebView di layar virtual pribadi (tak tampil di layar HP): bagi Android/Chromium halaman dianggap
     * TERLIHAT, jadi digambar normal seperti di browser. Bawaan; bila gagal dibuat → [DETACHED].
     */
    VIRTUAL_DISPLAY,
    /** WebView tanpa jendela (cara lama). Halaman bisa dianggap tersembunyi sehingga tidak digambar penuh. */
    DETACHED,
}

/**
 * Mengecek halaman Presensi Online SiAdin di latar belakang dengan WebView tak terlihat.
 * Memakai cookie/sesi yang sama dengan browser mini dan login otomatis yang sama.
 * HANYA membaca status halaman (dan mengisi form LOGIN); tidak pernah menekan tombol presensi.
 *
 * Keputusan diambil [CheckerBrain] (murni & teruji). Kelas ini hanya pelaksana: tiap detik membaca
 * alamat halaman + isi halaman (`probeScript`), lalu mengerjakan langkah dari otak.
 */
object SiadinChecker {
    private const val TOTAL_TIMEOUT_MS = (CheckerBrain.DEADLINE_SECONDS + 15) * 1_000L
    private const val WIDTH = 1080
    private const val HEIGHT = 2400

    suspend fun check(
        context: Context,
        targetUrl: String,
        credentials: Pair<String, String>?,
        courseName: String,
        mode: RenderMode = RenderMode.VIRTUAL_DISPLAY,
        log: (String) -> Unit = {},
    ): CheckResult {
        if (!hasInternet(context)) return CheckResult(PresensiState.UNKNOWN, "tidak ada internet")
        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { runCheck(context.applicationContext, targetUrl, credentials, courseName, mode, log) }
        } ?: CheckResult(PresensiState.UNKNOWN, "batas waktu total habis")
    }

    /** WebView beserta cara membereskannya. */
    private class Host(val webView: WebView, val label: String, private val release: () -> Unit) {
        fun close() = runCatching { release() }
    }

    /**
     * Buat WebView pengecek. Mode layar virtual: VirtualDisplay pribadi (tanpa izin khusus) + Presentation
     * berisi WebView, gambar yang dihasilkan langsung dibuang. Gagal dibuat → WebView tanpa jendela.
     */
    private fun createHost(context: Context, mode: RenderMode, log: (String) -> Unit): Host {
        if (mode == RenderMode.VIRTUAL_DISPLAY) {
            val virtual = runCatching {
                val reader = android.media.ImageReader.newInstance(WIDTH, HEIGHT, android.graphics.PixelFormat.RGBA_8888, 2)
                // Buang setiap gambar agar antrean tidak penuh (antrean penuh = halaman berhenti digambar).
                reader.setOnImageAvailableListener(
                    { r -> runCatching { r.acquireLatestImage()?.close() } },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
                val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .createVirtualDisplay("ngibsen-cek", WIDTH, HEIGHT, context.resources.displayMetrics.densityDpi, reader.surface, 0)
                try {
                    val presentation = android.app.Presentation(context, display.display)
                    val webView = WebView(presentation.context)
                    presentation.setContentView(
                        webView,
                        android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    presentation.show()
                    Host(webView, "layar virtual") {
                        runCatching { presentation.dismiss() }
                        display.release()
                        reader.close()
                    }
                } catch (e: Exception) {
                    display.release()
                    reader.close()
                    throw e
                }
            }
            virtual.onSuccess { return it }
            log("layar virtual gagal dibuat (${virtual.exceptionOrNull()?.javaClass?.simpleName}) → WebView tanpa jendela")
        }
        val webView = WebView(context)
        // WebView tanpa jendela diberi ukuran layar HP: tanpa ukuran, tata letak & deteksi elemen tidak akurat.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, WIDTH, HEIGHT)
        return Host(webView, "tanpa jendela") {}
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runCheck(
        context: Context,
        targetUrl: String,
        credentials: Pair<String, String>?,
        courseName: String,
        mode: RenderMode,
        log: (String) -> Unit,
    ): CheckResult {
        val host = createHost(context, mode, log)
        val webView = host.webView
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = webView.settings.userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
            CookieManager.getInstance().setAcceptCookie(true)
            // WebViewClient kosong: semua pindah halaman tetap di WebView ini (tidak membuka browser lain).
            webView.webViewClient = WebViewClient()

            val root = SiadinScripts.siteRoot(targetUrl)
            // Ada cookie → langsung ke halaman presensi (otak akan login ulang bila ternyata sesi habis).
            // Belum ada cookie → lewat halaman depan supaya form login muncul.
            val hasCookie = !CookieManager.getInstance().getCookie(targetUrl).isNullOrBlank()
            webView.loadUrl(if (credentials != null && !hasCookie) root else targetUrl)

            val brain = CheckerBrain(hasCredentials = credentials != null)
            val probeScript = SiadinScripts.probeScript(courseName)
            var tick = 0
            while (true) {
                delay(1_000)
                tick++
                val url = webView.url
                val page = when {
                    url.isNullOrBlank() || url == "about:blank" -> PageKind.OTHER
                    !SiadinScripts.isTrusted(url, targetUrl) -> PageKind.UNTRUSTED
                    SiadinScripts.isTargetPage(url, targetUrl) -> PageKind.TARGET
                    else -> PageKind.OTHER
                }
                val probe = Probe.parse(webView.evalString(probeScript))
                when (val step = brain.next(tick, page, probe)) {
                    Step.Wait -> Unit
                    Step.LoadTarget -> {
                        log("buka halaman presensi")
                        webView.loadUrl(targetUrl)
                    }
                    Step.LoadRoot -> {
                        log("halaman presensi belum login → login ulang lewat halaman depan")
                        webView.loadUrl(root)
                    }
                    Step.ClearSessionAndLoadRoot -> {
                        log("masih belum login → hapus sesi lama, login dari awal")
                        clearSession()
                        webView.loadUrl(root)
                    }
                    Step.FillLogin -> {
                        val creds = credentials ?: return CheckResult(PresensiState.UNKNOWN, "tanpa data login")
                        val r = webView.evalString(SiadinScripts.fillLoginScript(creds.first, creds.second))
                        log("isi login otomatis → $r")
                    }
                    is Step.Finish -> {
                        val summary = webView.evalString(SiadinScripts.cardsSummaryScript(courseName)).orEmpty()
                        return CheckResult(step.outcome.toState(), "${step.reason} | $summary | ${brain.summary} | ${host.label}")
                    }
                }
            }
        } finally {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
            host.close()
        }
    }

    private fun Outcome.toState() = when (this) {
        Outcome.WAITING -> PresensiState.WAITING
        Outcome.OPEN -> PresensiState.OPEN
        Outcome.DONE -> PresensiState.DONE
        Outcome.UNKNOWN -> PresensiState.UNKNOWN
        Outcome.LOGIN_FAILED -> PresensiState.LOGIN_FAILED
    }

    /** Hapus cookie & penyimpanan situs (sesi rusak). Browser mini akan login otomatis lagi saat dibuka. */
    private suspend fun clearSession() {
        suspendCancellableCoroutine { cont ->
            CookieManager.getInstance().removeAllCookies { if (cont.isActive) cont.resume(Unit) }
        }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().flush()
    }

    /** Jalankan skrip dan kembalikan hasil string-nya (sudah di-decode dari JSON), atau null. */
    private suspend fun WebView.evalString(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { raw ->
            val value = runCatching { JSONArray("[$raw]").opt(0) as? String }.getOrNull()
            if (cont.isActive) cont.resume(value)
        }
    }
}
