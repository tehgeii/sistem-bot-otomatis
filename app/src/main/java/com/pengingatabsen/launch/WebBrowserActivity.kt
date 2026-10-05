package com.pengingatabsen.launch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.AlarmScheduler
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.alarm.PresensiCheckWorker
import com.pengingatabsen.logic.EventType
import com.pengingatabsen.ui.theme.PengingatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * Browser mini untuk SiAdin web (mis. halaman Presensi Online).
 * - Sesi login (cookie) disimpan selama website mengizinkan.
 * - Login otomatis: bila halaman login muncul, NIM & password tersimpan diisi lalu tombol Login ditekan.
 * - Tombol presensi di website TETAP ditekan pengguna sendiri.
 * - Tombol "Sudah, kirim bukti" dan "Kirim screenshot" langsung dari halaman ini.
 */
class WebBrowserActivity : ComponentActivity() {
    private var webView: WebView? = null
    private lateinit var targetUrl: String
    private var courseId = 0L
    private var epochDay = 0L
    /** Nama matkul untuk mencocokkan kartu presensi di SiAdin ("" = semua kartu). */
    private var courseName = ""

    // State UI
    private var pageTitle by mutableStateOf("SiAdin")
    private var loadProgress by mutableIntStateOf(0)
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    // State login otomatis
    private var loginAttempts = 0
    private var redirectAfterLogin = false
    private var loginPageUrl: String? = null
    private var credentials: Pair<String, String>? = null
    private var autoLogin = true
    private var settingsLoaded = false
    private var started = false

    // Bantuan presensi: tunggu sesi dibuka, sorot tombol, bukti otomatis setelah pengguna menekan
    private var waitingSince = 0L
    private var wasWaiting = false
    private var autoProofScheduled = false
    /** Pengguna sudah menekan "Presensi Sekarang"; menunggu kartu jadi "Berhasil Presensi". */
    private var awaitingSuccess = false
    private val reloadWhileWaiting = Runnable { webView?.reload() }

    // Izin & upload yang diminta halaman web
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFileCallback = null
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted.values.any { it }
        pendingGeo?.let { (origin, callback) -> callback.invoke(origin, ok, false) }
        pendingGeo = null
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingPermissionRequest?.let { request -> grantWebPermissions(request, ok) }
        pendingPermissionRequest = null
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        targetUrl = intent.getStringExtra(EXTRA_URL) ?: TargetApps.SIADIN_PRESENSI_URL
        courseId = intent.getLongExtra(AlarmScheduler.EXTRA_COURSE_ID, 0L)
        epochDay = intent.getLongExtra(AlarmScheduler.EXTRA_EPOCH_DAY, 0L)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else finish()
            }
        })

        setContent {
            PengingatTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Tutup") }
                            },
                            actions = {
                                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Muat ulang") }
                                TextButton(onClick = { openInChrome() }) { Text("Chrome") }
                            },
                        )
                    },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = !busy, onClick = { sendScreenshot() }, modifier = Modifier.weight(1f)) {
                                        Text("📷 Kirim screenshot")
                                    }
                                    Button(enabled = !busy, onClick = { confirmDone() }, modifier = Modifier.weight(1f)) {
                                        Text("✅ Sudah, kirim bukti")
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        AndroidView(factory = { ctx -> createWebView(ctx) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        lifecycleScope.launch {
            val settings = Graph.settings.current()
            autoLogin = settings.autoLogin
            credentials = if (settings.autoLogin) Graph.settings.siadinLogin() else null
            courseName = Graph.repository.course(courseId)?.name.orEmpty()
            settingsLoaded = true
            startLoadingIfReady()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setGeolocationEnabled(true)
        settings.useWideViewPort = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Tampil sebagai Chrome biasa (tanpa penanda "wv") supaya website memperlakukannya sama.
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(PresensiBridge(), "PengingatAbsen")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadProgress = 1
            }

            /** Login berbasis JS kadang pindah halaman tanpa onPageFinished. */
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null && redirectAfterLogin && loginAttempts > 0 && url != loginPageUrl) {
                    view.postDelayed({ onPageReady(view, url, retriesLeft = 0) }, 800)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loadProgress = 100
                view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                CookieManager.getInstance().flush()
                if (url != null) onPageReady(view, url, retriesLeft = 3)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadProgress = newProgress
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (_: Exception) {
                    pendingFileCallback = null
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeo = origin to callback
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                if (!wantsCamera || hasPermission(Manifest.permission.CAMERA)) {
                    grantWebPermissions(request, true)
                } else {
                    pendingPermissionRequest = request
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
        }
        webView = this
        startLoadingIfReady()
    }

    /** Muat URL setelah WebView dibuat DAN pengaturan login sudah terbaca (urutannya tidak pasti). */
    private fun startLoadingIfReady() {
        val wv = webView ?: return
        if (!settingsLoaded || started) return
        started = true
        if (autoLogin && credentials != null) {
            // Website tidak selalu mengarahkan ke login bila sesi habis (halaman tampil kosong),
            // jadi masuk lewat halaman depan dulu: login bila perlu, lalu lanjut ke halaman tujuan.
            redirectAfterLogin = true
            wv.loadUrl(siteRoot())
        } else {
            wv.loadUrl(targetUrl)
        }
    }

    private fun siteRoot(): String = SiadinScripts.siteRoot(targetUrl)

    /** Dipanggil tiap halaman selesai dimuat: login otomatis atau lanjut ke halaman tujuan. */
    private fun onPageReady(view: WebView, url: String, retriesLeft: Int) {
        if (!isTrustedUrl(url)) return
        val creds = credentials
        val canFill = autoLogin && creds != null && loginAttempts < MAX_LOGIN_ATTEMPTS
        val script = if (canFill) SiadinScripts.fillLoginScript(creds!!.first, creds.second) else SiadinScripts.DETECT_LOGIN_SCRIPT
        view.evaluateJavascript(script) { raw ->
            when (raw?.trim('"')) {
                "SUBMITTED" -> {
                    loginAttempts++
                    loginPageUrl = url
                    redirectAfterLogin = true
                    status = "Login otomatis…"
                }
                "LOGIN_PAGE" -> {
                    // Setelah login (otomatis atau manual) langsung lanjut ke halaman tujuan.
                    redirectAfterLogin = true
                    status = when {
                        !autoLogin -> "Login otomatis nonaktif. Silakan login."
                        credentials == null -> "Silakan login. Simpan NIM & password di Pengaturan agar login otomatis."
                        else -> "Login otomatis gagal. Cek NIM/password di Pengaturan, atau login manual."
                    }
                }
                else -> {
                    // Bukan halaman login. Halaman SPA kadang baru merender form setelah onPageFinished.
                    if (canFill && retriesLeft > 0 && loginAttempts == 0) {
                        view.postDelayed({ onPageReady(view, url, retriesLeft - 1) }, 700)
                        return@evaluateJavascript
                    }
                    if (status?.startsWith("Login otomatis") == true || status?.startsWith("Silakan login") == true) status = null
                    if (redirectAfterLogin) {
                        redirectAfterLogin = false
                        if (!url.startsWith(targetUrl)) {
                            view.loadUrl(targetUrl)
                            return@evaluateJavascript
                        }
                    }
                    if (url.startsWith(targetUrl)) checkPresensi(view)
                }
            }
        }
    }

    /**
     * Di halaman presensi: tunggu sesi dibuka (muat ulang berkala), lalu sorot tombol presensi.
     * Tombol presensi TIDAK pernah ditekan oleh aplikasi; pengguna yang menekannya.
     */
    private fun checkPresensi(view: WebView) {
        view.evaluateJavascript(SiadinScripts.highlightScript(courseName)) { raw ->
            view.removeCallbacks(reloadWhileWaiting)
            when (raw?.trim('"')) {
                "WAITING" -> {
                    healFalseOpen(view)
                    val now = System.currentTimeMillis()
                    if (waitingSince == 0L) waitingSince = now
                    wasWaiting = true
                    if (now - waitingSince < MAX_WAIT_MS) {
                        status = "Menunggu dosen membuka presensi… dicek otomatis tiap 20 detik."
                        view.postDelayed(reloadWhileWaiting, RELOAD_INTERVAL_MS)
                    } else {
                        status = "Berhenti menunggu (90 menit). Tekan ⟳ untuk cek lagi."
                    }
                }
                "OPEN" -> {
                    if (!awaitingSuccess) {
                        status = "Presensi sudah dibuka — tekan \"Presensi Sekarang\" (bingkai kuning) lalu \"Ya\". Bukti dikirim otomatis."
                    }
                    onSessionOpened()
                }
                "DONE" -> {
                    if (!awaitingSuccess && !autoProofScheduled) {
                        status = "✅ SiAdin: Berhasil Presensi. Tekan 📷 Kirim screenshot untuk mengirim bukti."
                    }
                    waitingSince = 0L
                    wasWaiting = false
                }
                else -> {
                    if (wasWaiting) {
                        status = "Halaman presensi berubah — cek apakah presensi sudah dibuka."
                        onSessionOpened()
                    }
                }
            }
        }
    }

    /**
     * Bila kemunculan ini pernah (keliru) dicatat "presensi sudah dibuka" padahal halaman—dalam keadaan
     * login—masih "Belum Ada Presensi", hapus catatan itu supaya mode pintar kembali menunggu dengan senyap.
     */
    private fun healFalseOpen(view: WebView) {
        if (courseId <= 0) return
        view.evaluateJavascript(SiadinScripts.presensiStateScript(courseName)) { raw ->
            if (raw?.trim('"') != "WAITING") return@evaluateJavascript
            val context = applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                if (Graph.settings.isPresensiOpen(courseId, epochDay)) {
                    Graph.settings.unmarkPresensiOpen(courseId, epochDay)
                    val dao = Graph.db.recordDao()
                    dao.find(courseId, epochDay)?.let { dao.update(it.copy(snoozeUntilMillis = null)) }
                    AlarmScheduler.reschedule(context, courseId)
                    PresensiCheckWorker.enqueue(context, courseId, epochDay, EventType.REMIND)
                }
            }
        }
    }

    private fun onSessionOpened() {
        waitingSince = 0L
        if (wasWaiting) {
            wasWaiting = false
            Notifications.showPresensiOpen(applicationContext, courseId, epochDay, targetUrl)
        }
    }

    /** Dipanggil dari listener klik pada tombol yang disorot — hanya terpicu oleh tap pengguna. */
    private inner class PresensiBridge {
        @JavascriptInterface
        fun onPresensiClicked() {
            runOnUiThread { onPresensiClickedByUser() }
        }
    }

    /**
     * Pengguna menekan "Presensi Sekarang". SiAdin lalu menampilkan kotak konfirmasi (Tidak/Ya),
     * jadi bukti BELUM dikirim di sini: tunggu sampai kartu matkul ini menjadi "Berhasil Presensi".
     * Bila pengguna menekan "Tidak" (atau gagal), tidak ada bukti yang dikirim.
     */
    private fun onPresensiClickedByUser() {
        val wv = webView ?: return
        val url = wv.url ?: return
        if (autoProofScheduled || awaitingSuccess || !isTrustedUrl(url)) return
        awaitingSuccess = true
        status = "Tekan \"Ya\" di kotak konfirmasi SiAdin — bukti dikirim otomatis setelah \"Berhasil Presensi\"."
        pollForSuccess(wv, attemptsLeft = SUCCESS_POLL_ATTEMPTS)
    }

    private fun pollForSuccess(wv: WebView, attemptsLeft: Int) {
        if (isFinishing || autoProofScheduled) return
        if (attemptsLeft <= 0) {
            awaitingSuccess = false
            status = "Presensi belum terkonfirmasi (\"Ya\" belum ditekan?). Tekan tombol kuning lagi bila perlu."
            return
        }
        wv.evaluateJavascript(SiadinScripts.highlightScript(courseName)) { raw ->
            if (raw?.trim('"') == "DONE") {
                autoProofScheduled = true
                awaitingSuccess = false
                status = "✅ Berhasil Presensi — mengirim bukti…"
                // Beri waktu kotak hijau tampil penuh & tergulir ke tengah sebelum di-screenshot.
                wv.postDelayed({ if (!isFinishing) sendScreenshot() }, AUTO_PROOF_DELAY_MS)
            } else {
                wv.postDelayed({ pollForSuccess(wv, attemptsLeft - 1) }, 1_000)
            }
        }
    }

    /** Kredensial hanya diisikan ke halaman HTTPS di domain yang sama dengan URL tujuan. */
    private fun isTrustedUrl(url: String): Boolean = SiadinScripts.isTrusted(url, targetUrl)

    private fun confirmDone() {
        busy = true
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) {
                Graph.repository.confirmFromBrowser(courseId, epochDay, LocalDateTime.now())
            }
            busy = false
            if (record == null) {
                status = "Tidak ada absen yang sedang dibuka. Bagikan screenshot saja bila perlu."
            } else {
                Toast.makeText(this@WebBrowserActivity, "Bukti ${record.courseName} diantrekan ke Telegram", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** Ambil gambar halaman yang sedang tampil lalu kirim via sendPhoto. */
    private fun sendScreenshot() {
        val wv = webView ?: return
        val bitmap = try {
            wv.drawToBitmap()
        } catch (e: Exception) {
            status = "Gagal mengambil screenshot: ${e.message}"
            return
        }
        busy = true
        val sharedAt = LocalDateTime.now()
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "bukti").apply { mkdirs() }
                val file = File(dir, "bukti_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                Graph.repository.attachPhoto(file.absolutePath, sharedAt)
            }
            busy = false
            Toast.makeText(this@WebBrowserActivity, "Screenshot ${record.courseName} diantrekan ke Telegram", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun openInChrome() {
        val url = webView?.url ?: targetUrl
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun grantWebPermissions(request: PermissionRequest, cameraGranted: Boolean) {
        val allowed = request.resources.filter { it != PermissionRequest.RESOURCE_VIDEO_CAPTURE || cameraGranted }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Dipanggil lagi dari notifikasi saat browser mini masih terbuka. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        courseId = intent.getLongExtra(AlarmScheduler.EXTRA_COURSE_ID, 0L)
        epochDay = intent.getLongExtra(AlarmScheduler.EXTRA_EPOCH_DAY, 0L)
        targetUrl = intent.getStringExtra(EXTRA_URL) ?: targetUrl
        lifecycleScope.launch { courseName = Graph.repository.course(courseId)?.name.orEmpty() }
        loginAttempts = 0
        redirectAfterLogin = false
        status = null
        webView?.removeCallbacks(reloadWhileWaiting)
        waitingSince = 0L
        autoProofScheduled = false
        awaitingSuccess = false
        if (started) webView?.loadUrl(targetUrl)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView?.removeCallbacks(reloadWhileWaiting)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val MAX_LOGIN_ATTEMPTS = 2
        private const val RELOAD_INTERVAL_MS = 20_000L
        private const val MAX_WAIT_MS = 90 * 60_000L
        private const val AUTO_PROOF_DELAY_MS = 1_500L
        /** Waktu menunggu pengguna menekan "Ya" di kotak konfirmasi (±90 detik, dicek tiap detik). */
        private const val SUCCESS_POLL_ATTEMPTS = 90

        fun intent(context: Context, url: String, courseId: Long = 0L, epochDay: Long = 0L): Intent =
            Intent(context, WebBrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(AlarmScheduler.EXTRA_COURSE_ID, courseId)
                putExtra(AlarmScheduler.EXTRA_EPOCH_DAY, epochDay)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
