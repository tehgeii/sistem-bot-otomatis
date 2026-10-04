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

    // State UI
    private var pageTitle by mutableStateOf("SiAdin")
    private var progress by mutableIntStateOf(0)
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    // State login otomatis
    private var loginAttempts = 0
    private var redirectAfterLogin = false
    private var credentials: Pair<String, String>? = null
    private var autoLogin = true
    private var settingsLoaded = false
    private var started = false

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
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        AndroidView(factory = { ctx -> createWebView(ctx) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        lifecycleScope.launch {
            val settings = Graph.settings.current()
            autoLogin = settings.autoLogin
            credentials = if (settings.autoLogin) Graph.settings.siadinLogin() else null
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
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress = 1
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress = 100
                view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                CookieManager.getInstance().flush()
                if (url != null) onPageReady(view, url, retriesLeft = 3)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
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
        wv.loadUrl(targetUrl)
    }

    /** Dipanggil tiap halaman selesai dimuat: login otomatis atau kembali ke halaman tujuan. */
    private fun onPageReady(view: WebView, url: String, retriesLeft: Int) {
        if (!isTrustedUrl(url)) return
        val creds = credentials
        val canFill = autoLogin && creds != null && loginAttempts < MAX_LOGIN_ATTEMPTS
        val script = if (canFill) fillLoginScript(creds!!.first, creds.second) else DETECT_LOGIN_SCRIPT
        view.evaluateJavascript(script) { raw ->
            when (raw?.trim('"')) {
                "SUBMITTED" -> {
                    loginAttempts++
                    redirectAfterLogin = true
                    status = "Login otomatis…"
                }
                "LOGIN_PAGE", "NO_USER" -> status = when {
                    !autoLogin -> "Login otomatis nonaktif. Silakan login."
                    credentials == null -> "Silakan login. Simpan NIM & password di Pengaturan agar login otomatis."
                    else -> "Login otomatis gagal. Cek NIM/password di Pengaturan, atau login manual."
                }
                "NO_FORM" -> {
                    // Halaman SPA kadang baru merender form setelah onPageFinished.
                    if (canFill && retriesLeft > 0 && !redirectAfterLogin) {
                        view.postDelayed({ onPageReady(view, url, retriesLeft - 1) }, 700)
                        return@evaluateJavascript
                    }
                    if (redirectAfterLogin) {
                        redirectAfterLogin = false
                        status = null
                        if (!url.startsWith(targetUrl)) view.loadUrl(targetUrl)
                    } else if (status?.startsWith("Login otomatis…") == true) {
                        status = null
                    }
                }
            }
        }
    }

    /** Kredensial hanya diisikan ke halaman HTTPS di domain yang sama dengan URL tujuan. */
    private fun isTrustedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val targetHost = Uri.parse(targetUrl).host?.lowercase() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (host == targetHost) return true
        val labels = targetHost.split('.')
        // mhs.dinus.ac.id → izinkan juga *.dinus.ac.id (mis. halaman SSO kampus).
        val parent = if (labels.size >= 4) labels.drop(1).joinToString(".") else targetHost
        return host == parent || host.endsWith(".$parent")
    }

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
        loginAttempts = 0
        redirectAfterLogin = false
        status = null
        if (started) webView?.loadUrl(targetUrl)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val MAX_LOGIN_ATTEMPTS = 2

        fun intent(context: Context, url: String, courseId: Long = 0L, epochDay: Long = 0L): Intent =
            Intent(context, WebBrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(AlarmScheduler.EXTRA_COURSE_ID, courseId)
                putExtra(AlarmScheduler.EXTRA_EPOCH_DAY, epochDay)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        /** Hanya mendeteksi apakah ada form login yang tampil. */
        private const val DETECT_LOGIN_SCRIPT = """
            (function(){
              var p = Array.prototype.slice.call(document.querySelectorAll('input[type=password]'))
                .filter(function(e){ return e.offsetParent !== null; })[0];
              return p ? 'LOGIN_PAGE' : 'NO_FORM';
            })();
        """

        /**
         * Isi kolom NIM & password pada form login yang tampil, lalu tekan tombol Login.
         * Kolom NIM ditebak dari nama/id/placeholder (nim, user, login, email), atau kolom teks pertama.
         */
        private fun fillLoginScript(nim: String, password: String): String = """
            (function(nim, pw){
              function visible(e){ return e.offsetParent !== null; }
              var p = Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(visible)[0];
              if (!p) return 'NO_FORM';
              var scope = p.form || document;
              var inputs = Array.prototype.slice.call(scope.querySelectorAll('input')).filter(function(e){
                var t = (e.getAttribute('type') || 'text').toLowerCase();
                return visible(e) && ['text','email','number','tel'].indexOf(t) >= 0;
              });
              var u = inputs.filter(function(e){
                return /nim|user|login|email|npm|nomor/i.test((e.name||'') + ' ' + (e.id||'') + ' ' + (e.placeholder||''));
              })[0] || inputs[0];
              if (!u) return 'NO_USER';
              var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
              function put(el, v){
                el.focus();
                setter.call(el, v);
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                el.blur();
              }
              put(u, nim);
              put(p, pw);
              var btn = scope.querySelector('button[type=submit],input[type=submit]') ||
                Array.prototype.slice.call(scope.querySelectorAll('button,input[type=button],a')).filter(function(b){
                  return /login|masuk|sign\s*in/i.test(b.innerText || b.value || '');
                })[0];
              setTimeout(function(){
                if (btn) { btn.click(); }
                else if (p.form) { if (p.form.requestSubmit) p.form.requestSubmit(); else p.form.submit(); }
              }, 300);
              return 'SUBMITTED';
            })(${JSONObject.quote(nim)}, ${JSONObject.quote(password)});
        """
    }
}
