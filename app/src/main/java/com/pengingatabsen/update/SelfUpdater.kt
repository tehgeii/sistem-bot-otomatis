package com.pengingatabsen.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.logic.AppUpdate
import com.pengingatabsen.logic.ArchiveVerdict
import com.pengingatabsen.logic.RemoteVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Tahap pembaruan sekali tap (ditampilkan [UpdateActivity]). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    /** [total] = -1 bila ukuran tidak diketahui. */
    data class Downloading(val versionName: String, val done: Long, val total: Long) : UpdateState
    data class Verifying(val versionName: String) : UpdateState
    data class Installing(val versionName: String, val sinceElapsed: Long = SystemClock.elapsedRealtime()) : UpdateState
    /** Android meminta konfirmasi (atau izin "instal aplikasi tak dikenal"); [confirm] dibuka oleh layar pembaruan. */
    data class AwaitingConfirm(val versionName: String, val confirm: Intent) : UpdateState
    data class Failed(val message: String, val suggestPermission: Boolean = false) : UpdateState
}

/**
 * Pembaruan sekali tap: unduh APK dari Release resmi repo, periksa (isi file = versi.json, nama paket, sertifikat
 * SAMA dengan aplikasi terpasang, versi lebih baru), lalu serahkan ke pemasang Android (PackageInstaller).
 * Di Android 12+ aplikasi yang memperbarui dirinya sendiri boleh dipasang tanpa dialog konfirmasi bila sistem
 * mengizinkan; selain itu Android menampilkan satu dialog "Perbarui". Hanya berjalan atas tap pengguna;
 * di latar paling jauh MENGUNDUH lebih dulu (Wi-Fi), tidak pernah memasang.
 */
object SelfUpdater {
    private const val DIR = "pembaruan"
    private const val FILE = "NgiBsen-baru.apk"
    private const val MAX_BYTES = 60L * 1024 * 1024
    private const val ACTION_RESULT = "com.pengingatabsen.update.HASIL_PASANG"
    /** Selama ini setelah diserahkan ke Android, tap "Perbarui" lagi tidak membuat sesi baru. */
    private const val INSTALL_GRACE_MS = 60_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Satu unduhan/pemeriksaan pada satu waktu (tap pengguna vs unduhan otomatis di latar). */
    private val fileLock = Mutex()
    private var job: Job? = null
    private var target: RemoteVersion? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Layar pembaruan sedang tampil? Bila tidak, tahap yang butuh pengguna diberitahukan lewat notifikasi. */
    @Volatile var uiVisible = false

    sealed interface Prepared {
        data class Ready(val file: File, val remote: RemoteVersion) : Prepared
        data class Error(val message: String) : Prepared
    }

    /** Mulai pembaruan atas tap pengguna. Aman dipanggil berulang (diabaikan bila sedang berjalan/menunggu Android). */
    fun start(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        when (val s = _state.value) {
            is UpdateState.AwaitingConfirm -> return
            is UpdateState.Installing -> if (SystemClock.elapsedRealtime() - s.sinceElapsed < INSTALL_GRACE_MS) return
            else -> Unit
        }
        job = scope.launch { runFlow(app) }
    }

    /** Batalkan pengecekan/unduhan (pemasangan yang sudah diserahkan ke Android tidak bisa dibatalkan dari sini). */
    fun cancel() {
        job?.cancel()
        if (_state.value !is UpdateState.Installing && _state.value !is UpdateState.AwaitingConfirm) _state.value = UpdateState.Idle
    }

    /** Layar pembaruan sudah membuka dialog Android. */
    fun confirmLaunched(versionName: String) {
        _state.value = UpdateState.Installing(versionName)
    }

    fun confirmUnavailable(context: Context) =
        fail(context.applicationContext, "Dialog pemasang Android tidak bisa dibuka. Pasang manual lewat \"Halaman unduhan\".")

    private suspend fun runFlow(app: Context) {
        try {
            _state.value = UpdateState.Checking
            val remote = UpdateChecker.fetchRemote() ?: return fail(app, "Tidak bisa mengecek versi terbaru (internet?). Coba lagi nanti.")
            val mine = UpdateChecker.installed(app)
            if (!AppUpdate.isNewer(remote, mine.versionCode)) {
                _state.value = UpdateState.UpToDate(mine.versionName)
                return
            }
            target = remote
            when (val p = prepare(app, remote) { _state.value = it }) {
                is Prepared.Error -> fail(app, p.message)
                is Prepared.Ready -> {
                    target = p.remote
                    install(app, p.file, p.remote)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(app, "Pembaruan gagal: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Pastikan APK versi [remote] ada di penyimpanan aplikasi DAN lolos pemeriksaan. File unduhan sebelumnya
     * (mis. otomatis lewat Wi-Fi) dipakai ulang bila masih lolos. Dipakai juga oleh pengecek harian (tanpa [report]).
     */
    suspend fun prepare(context: Context, remote: RemoteVersion, report: (UpdateState) -> Unit = {}): Prepared =
        fileLock.withLock { withContext(Dispatchers.IO) { prepareLocked(context.applicationContext, remote, report) } }

    private suspend fun prepareLocked(app: Context, first: RemoteVersion, report: (UpdateState) -> Unit): Prepared {
        val mine = UpdateChecker.installed(app)
        val apk = apkFile(app)
        var remote = first
        if (apk.exists()) {
            report(UpdateState.Verifying(remote.versionName))
            if (inspect(app, apk, mine, remote) == ArchiveVerdict.OK) return Prepared.Ready(apk, remote)
            apk.delete()
        }
        repeat(2) {
            download(remote, apk, report)?.let { return Prepared.Error(it) }
            report(UpdateState.Verifying(remote.versionName))
            val verdict = inspect(app, apk, mine, remote)
            DiagLog.add("pembaruan ${remote.versionName}: pemeriksaan APK → $verdict")
            when (verdict) {
                ArchiveVerdict.OK -> return Prepared.Ready(apk, remote)
                ArchiveVerdict.HASH_MISMATCH -> {
                    // Release bisa saja sedang diganti CI (APK & versi.json diunggah bergantian): baca ulang & unduh sekali lagi.
                    apk.delete()
                    remote = UpdateChecker.fetchRemote() ?: return Prepared.Error("Tidak bisa membaca ulang versi.json. Coba lagi nanti.")
                    if (!AppUpdate.isNewer(remote, mine.versionCode)) return Prepared.Error("Rilis sedang berganti. Coba lagi beberapa menit lagi.")
                }
                else -> {
                    apk.delete()
                    return Prepared.Error(verdict.message)
                }
            }
        }
        return Prepared.Error(ArchiveVerdict.HASH_MISMATCH.message + " Coba lagi beberapa menit lagi.")
    }

    /** Unduh APK ke [dest] (lewat file .part). null = berhasil, selain itu pesan kesalahan. */
    private suspend fun download(remote: RemoteVersion, dest: File, report: (UpdateState) -> Unit): String? {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        report(UpdateState.Downloading(remote.versionName, 0, -1))
        try {
            val request = Request.Builder().url(UpdateChecker.apkUrl(remote)).header("Cache-Control", "no-cache").build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return "Unduhan gagal (HTTP ${r.code}). Coba lagi nanti."
                val body = r.body ?: return "Unduhan kosong. Coba lagi nanti."
                val total = body.contentLength()
                if (total > MAX_BYTES) return "File terlalu besar (${total / 1_048_576} MB) — dibatalkan."
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > MAX_BYTES) return "File terlalu besar — dibatalkan."
                            if (done - lastReport >= 128 * 1024) {
                                lastReport = done
                                report(UpdateState.Downloading(remote.versionName, done, total))
                            }
                        }
                        if (total > 0 && done != total) return "Unduhan terputus. Coba lagi."
                        out.fd.sync()
                    }
                }
            }
            if (!part.renameTo(dest)) return "Tidak bisa menyimpan file unduhan (penyimpanan penuh?)."
            return null
        } catch (e: IOException) {
            return "Unduhan terputus (${e.javaClass.simpleName}). Periksa internet lalu coba lagi."
        } finally {
            part.delete()
        }
    }

    private fun inspect(context: Context, apk: File, mine: InstalledVersion, remote: RemoteVersion): ArchiveVerdict {
        val info = archiveInfo(context, apk)
        return AppUpdate.verifyArchive(
            installedPackage = context.packageName,
            installedVersionCode = mine.versionCode,
            installedCertSha256 = mine.certSha256,
            archivePackage = info?.packageName,
            archiveVersionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) },
            archiveCertSha256 = info?.let { archiveCert(context, apk, it) },
            fileSha256 = sha256(apk),
            remote = remote,
        )
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
    }

    /** Sertifikat penandatangan APK unduhan (cara lama dicoba bila cara baru tidak memberi hasil di HP tertentu). */
    @Suppress("DEPRECATION")
    private fun archiveCert(context: Context, apk: File, info: PackageInfo): String? {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners?.firstOrNull() else null
        val signer = modern ?: info.signatures?.firstOrNull()
            ?: runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES) }
                .getOrNull()?.signatures?.firstOrNull()
        return signer?.let { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    }

    private fun sha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        hex(md.digest())
    }.getOrNull()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun apkFile(context: Context) = File(File(context.filesDir, DIR), FILE)

    // ---------- Pemasangan ----------

    private suspend fun install(app: Context, apk: File, remote: RemoteVersion) {
        _state.value = UpdateState.Installing(remote.versionName)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                Graph.settings.setUpdateInstalling(remote.versionCode)
                session.commit(resultIntent(app, sessionId).intentSender)
            }
            DiagLog.add("pembaruan ${remote.versionName}: diserahkan ke pemasang Android")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            Graph.settings.setUpdateInstalling(null)
            fail(app, "Pemasang Android menolak (${e.javaClass.simpleName}). Pakai \"Halaman unduhan\" untuk memasang manual.")
        }
    }

    private fun resultIntent(context: Context, sessionId: Int): PendingIntent {
        // MUTABLE: Android mengisi status hasil ke intent ini. Aman karena intent eksplisit ke receiver tak-terekspor.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context, sessionId,
            Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    /** Hasil dari pemasang Android (lihat [InstallResultReceiver]). */
    suspend fun onInstallResult(context: Context, status: Int, message: String?, confirm: Intent?) {
        val name = target?.versionName ?: "baru"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return fail(context, "Android meminta konfirmasi tapi dialognya tidak tersedia. Coba lagi.")
                DiagLog.add("pembaruan $name: Android meminta konfirmasi")
                _state.value = UpdateState.AwaitingConfirm(name, confirm)
                if (!uiVisible) {
                    Notifications.showUpdateStep(context, "Pembaruan NgiBsen $name", "Ketuk untuk menyelesaikan pemasangan (satu konfirmasi dari Android).")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Biasanya proses ini sudah dihentikan Android sebelum sampai sini; notifikasi dikirim versi baru.
                DiagLog.add("pembaruan $name: berhasil")
                _state.value = UpdateState.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Graph.settings.setUpdateInstalling(null)
                DiagLog.add("pembaruan $name: dibatalkan")
                fail(context, "Pemasangan dibatalkan. Ketuk \"Coba lagi\" bila ingin memperbarui.", notify = false)
            }
            else -> {
                Graph.settings.setUpdateInstalling(null)
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "diblokir sistem (izin \"Instal aplikasi tak dikenal\" / Play Protect)"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "bentrok dengan aplikasi terpasang"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "tidak cocok dengan HP ini"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "file APK tidak valid"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "penyimpanan HP penuh"
                    else -> "gagal (kode $status)"
                }
                DiagLog.add("pembaruan $name: $reason — ${message.orEmpty().take(120)}")
                if (status == PackageInstaller.STATUS_FAILURE_INVALID || status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                    apkFile(context).delete()
                }
                val blocked = status == PackageInstaller.STATUS_FAILURE_BLOCKED && !context.packageManager.canRequestPackageInstalls()
                fail(context, "Pemasangan $reason. Coba lagi, atau pasang manual lewat \"Halaman unduhan\".", suggestPermission = blocked)
            }
        }
    }

    private fun fail(context: Context, message: String, suggestPermission: Boolean = false, notify: Boolean = true) {
        _state.value = UpdateState.Failed(message, suggestPermission)
        if (notify && !uiVisible) Notifications.showUpdateStep(context, "Pembaruan NgiBsen belum berhasil", message)
    }

    // ---------- Setelah diperbarui & bersih-bersih ----------

    /** Dipanggil versi BARU saat MY_PACKAGE_REPLACED: beri tahu bila pembaruan ini dimulai dari NgiBsen sendiri. */
    suspend fun afterPackageReplaced(context: Context) {
        val wanted = Graph.settings.takeUpdateInstalling() ?: return
        val mine = UpdateChecker.installed(context)
        if (mine.versionCode >= wanted) {
            DiagLog.add("NgiBsen diperbarui ke ${mine.versionName}")
            Notifications.showUpdated(context, mine.versionName)
        }
    }

    /** Hapus APK unduhan yang sudah terpasang/usang & sisa unduhan terputus. Tidak mengganggu unduhan yang berjalan. */
    suspend fun cleanup(context: Context): Unit = withContext(Dispatchers.IO) {
        val apk = apkFile(context)
        val part = File(apk.parentFile, apk.name + ".part")
        if (!apk.exists() && !part.exists()) return@withContext
        if (!fileLock.tryLock()) return@withContext
        try {
            part.delete()
            if (apk.exists()) {
                val code = archiveInfo(context, apk)?.let { PackageInfoCompat.getLongVersionCode(it) }
                if (code == null || code <= UpdateChecker.installed(context).versionCode) apk.delete()
            }
        } finally {
            fileLock.unlock()
        }
    }

    /** Wi-Fi / jaringan tak berbayar (unduhan otomatis di latar hanya lewat ini). */
    fun onUnmeteredNetwork(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.let { !it.isActiveNetworkMetered } ?: false
}
