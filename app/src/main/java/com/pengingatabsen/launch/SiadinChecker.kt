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

/**
 * Hasil satu pengecekan + keterangan untuk log diagnosis (tanpa data rahasia).
 * [photoPath]: foto halaman "Berhasil Presensi" (bukti otomatis), bila diminta dan berhasil diambil.
 */
data class CheckResult(
    val state: PresensiState,
    val detail: String,
    val photoPath: String? = null,
    /** Hasil skrip ekstraksi (mis. teks kartu KRS) bila diminta dan halaman berhasil dibaca. */
    val extracted: String? = null,
)

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
        /** Bila kartu "Berhasil Presensi", potret halamannya sebagai bukti (hanya di layar virtual). */
        captureProof: Boolean = false,
        /** Skrip yang dijalankan setelah halaman berhasil dibaca; hasilnya di [CheckResult.extracted]. */
        extractScript: String? = null,
        log: (String) -> Unit = {},
    ): CheckResult {
        if (!hasInternet(context)) return CheckResult(PresensiState.UNKNOWN, "tidak ada internet")
        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                runCheck(context.applicationContext, targetUrl, credentials, courseName, mode, captureProof, extractScript, log)
            }
        } ?: CheckResult(PresensiState.UNKNOWN, "batas waktu total habis")
    }

    /** WebView beserta cara membereskannya. */
    private class Host(
        val webView: WebView,
        val label: String,
        /** Ambil gambar halaman yang sedang tampil (null bila mode ini tidak bisa memotret). */
        val capture: (suspend () -> android.graphics.Bitmap?)? = null,
        private val release: () -> Unit,
    ) {
        fun close() = runCatching { release() }
    }

    /** Gambar dari layar virtual (RGBA_8888) → Bitmap seukuran layar. */
    private fun android.media.Image.toBitmap(): android.graphics.Bitmap {
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val padded = android.graphics.Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) padded else android.graphics.Bitmap.createBitmap(padded, 0, 0, width, height)
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
                // Bila sedang diminta foto bukti, gambar berikutnya diubah jadi Bitmap; selain itu dibuang.
                var pendingShot: kotlinx.coroutines.CompletableDeferred<android.graphics.Bitmap?>? = null
                reader.setOnImageAvailableListener(
                    { r ->
                        runCatching {
                            val image = r.acquireLatestImage()
                            if (image != null) {
                                try {
                                    pendingShot?.let { shot ->
                                        pendingShot = null
                                        shot.complete(runCatching { image.toBitmap() }.getOrNull())
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                        }
                    },
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
                    val capture: suspend () -> android.graphics.Bitmap? = {
                        val shot = kotlinx.coroutines.CompletableDeferred<android.graphics.Bitmap?>()
                        pendingShot = shot
                        // Paksa halaman digambar ulang supaya layar virtual mengirim gambar baru.
                        webView.invalidate()
                        webView.evaluateJavascript("window.scrollBy(0,1);window.scrollBy(0,-1);", null)
                        withTimeoutOrNull(3_000) { shot.await() }.also { pendingShot = null }
                    }
                    Host(webView, "layar virtual", capture) {
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
        return Host(webView, "tanpa jendela", capture = null) {}
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
        captureProof: Boolean,
        extractScript: String?,
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
                        val photo = if (captureProof && step.outcome == Outcome.DONE) {
                            captureProofPhoto(context, host, courseName, log)
                        } else {
                            null
                        }
                        val readOk = step.outcome == Outcome.WAITING || step.outcome == Outcome.OPEN || step.outcome == Outcome.DONE
                        val extracted = if (extractScript != null && readOk) webView.evalString(extractScript) else null
                        return CheckResult(
                            step.outcome.toState(),
                            "${step.reason} | $summary | ${brain.summary} | ${host.label}",
                            photo,
                            extracted,
                        )
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

    /**
     * Foto bukti otomatis: gulir kartu "Berhasil Presensi" ke tengah (skrip sorotan, hanya tampilan),
     * tunggu sebentar, potret layar virtual, simpan JPEG di folder bukti. Null bila tidak bisa.
     */
    private suspend fun captureProofPhoto(context: Context, host: Host, courseName: String, log: (String) -> Unit): String? {
        val capture = host.capture ?: return null
        return runCatching {
            host.webView.evalString(SiadinScripts.highlightScript(courseName))
            delay(1_200)
            val bitmap = capture() ?: return@runCatching null
            withContext(Dispatchers.IO) {
                val dir = java.io.File(context.filesDir, "bukti").apply { mkdirs() }
                val file = java.io.File(dir, "bukti_otomatis_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                file.absolutePath
            }
        }.onFailure { log("foto bukti gagal: ${it.javaClass.simpleName}") }
            .getOrNull()
            .also { log(if (it != null) "foto bukti diambil" else "foto bukti tidak tersedia → bukti teks") }
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
