package com.pengingatabsen.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.pengingatabsen.data.AppSettings

data class InstalledApp(val packageName: String, val label: String)

/** Daftar aplikasi terpasang & cara membuka aplikasi tujuan (Dinusverse). */
object TargetApps {
    private val HINTS = listOf("dinus", "siadin", "udinus")

    /** Halaman Presensi Online SiAdin web (alamat publik portal mahasiswa). */
    const val SIADIN_ORIGIN = "https://mhs.dinus.ac.id"
    const val SIADIN_PRESENSI_URL = "$SIADIN_ORIGIN/akademik/presensiOnline"

    /** URL web dibuka di browser mini dalam aplikasi (sesi login tersimpan, login otomatis). */
    fun isWebUrl(url: String?): Boolean =
        url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))

    /** Semua aplikasi yang punya ikon launcher; yang mirip Dinusverse ditaruh paling atas. */
    fun installed(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(query, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
        return apps.sortedWith(compareBy<InstalledApp> { !looksLikeDinus(it) }.thenBy { it.label.lowercase() })
    }

    fun looksLikeDinus(app: InstalledApp): Boolean {
        val text = (app.label + " " + app.packageName).lowercase()
        return HINTS.any { it in text }
    }

    /** Tebakan otomatis bila pengguna belum memilih (supaya tidak perlu bertanya). */
    fun guess(context: Context): InstalledApp? = installed(context).firstOrNull(::looksLikeDinus)

    /**
     * Intent untuk tombol "Absen sekarang": URL deep link bila diisi,
     * kalau tidak launch intent aplikasi yang dipilih.
     */
    fun launchIntent(context: Context, settings: AppSettings, courseId: Long = 0L, epochDay: Long = 0L): Intent? {
        settings.deepLink?.takeIf(::isWebUrl)?.let { url ->
            return WebBrowserActivity.intent(context, url, courseId, epochDay)
        }
        settings.deepLink?.let { url ->
            return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pkg = settings.targetPackage ?: guess(context)?.packageName ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
