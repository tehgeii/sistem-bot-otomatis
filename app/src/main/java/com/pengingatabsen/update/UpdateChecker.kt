package com.pengingatabsen.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pengingatabsen.Graph
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.logic.AppUpdate
import com.pengingatabsen.logic.RemoteVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Versi & sidik jari sertifikat aplikasi yang terpasang di HP ini. */
data class InstalledVersion(val versionName: String, val versionCode: Long, val certSha256: String?)

/**
 * Pemberitahuan versi baru: membaca `versi.json` di Release "terbaru" repo (publik, tanpa login, ±300 byte),
 * membandingkan dengan versi terpasang. Bila diizinkan, APK diunduh & diperiksa lebih dulu lewat Wi-Fi supaya
 * tinggal sekali tap; MEMASANG selalu atas tap pengguna ([SelfUpdater]).
 */
object UpdateChecker {
    const val VERSION_URL = "https://github.com/tehgeii/sistem-bot-otomatis/releases/download/terbaru/versi.json"
    const val RELEASE_PAGE = "https://github.com/tehgeii/sistem-bot-otomatis/releases/latest"
    private const val RELEASE_DOWNLOAD = "https://github.com/tehgeii/sistem-bot-otomatis/releases/download/terbaru/"
    private const val PERIODIC_NAME = "cek-versi-baru"

    /** Alamat APK di Release (nama file dari versi.json disaring dulu). */
    fun apkUrl(remote: RemoteVersion): String = RELEASE_DOWNLOAD + AppUpdate.safeApkName(remote.apk)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchRemote(): RemoteVersion? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(VERSION_URL).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string().orEmpty()
                if (body.length > 4096) null else AppUpdate.parse(body)
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun installed(context: Context): InstalledVersion {
        val pm = context.packageManager
        val name = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(name, PackageManager.GET_SIGNATURES)
        }
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            info.signatures?.firstOrNull()
        }
        val sha = signer?.let { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        return InstalledVersion(info.versionName ?: "?", PackageInfoCompat.getLongVersionCode(info), sha)
    }

    /**
     * Cek sekarang; bila ada versi lebih baru: unduh & periksa lebih dulu ([prefetch], hanya lewat Wi-Fi/jaringan
     * tak berbayar), lalu notifikasi sekali per versi. Tidak pernah memasang.
     */
    suspend fun checkAndNotify(context: Context, prefetch: Boolean): RemoteVersion? {
        val remote = fetchRemote() ?: return null
        val mine = installed(context)
        if (!AppUpdate.isNewer(remote, mine.versionCode)) return remote
        var ready = false
        if (prefetch && SelfUpdater.onUnmeteredNetwork(context)) {
            val prepared = SelfUpdater.prepare(context, remote)
            ready = prepared is SelfUpdater.Prepared.Ready
            if (prepared is SelfUpdater.Prepared.Error) DiagLog.add("unduh otomatis ${remote.versionName} gagal: ${prepared.message}")
        }
        if (Graph.settings.claimUpdateNotice(remote.versionCode)) {
            DiagLog.add("versi baru tersedia: ${remote.versionName} (terpasang ${mine.versionName})" + if (ready) ", sudah diunduh" else "")
            Notifications.showUpdateAvailable(context, remote.versionName, mine.versionName, ready)
        }
        return remote
    }

    /** Pasang/hapus cek harian sesuai pengaturan. Aman dipanggil berulang (KEEP). */
    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Graph.settings.current()
        if (!settings.updateCheck) return Result.success()
        UpdateChecker.checkAndNotify(applicationContext, prefetch = settings.updateAutoDownload)
        return Result.success()
    }
}
