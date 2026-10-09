package com.pengingatabsen.logic

import org.json.JSONException
import org.json.JSONObject

/** Isi `versi.json` di Release "terbaru" (dibuat CI). */
data class RemoteVersion(
    val versionCode: Long,
    val versionName: String,
    val apk: String?,
    /** Sidik jari SHA-256 sertifikat penandatangan APK rilis (hex huruf kecil). */
    val certSha256: String?,
    /** SHA-256 isi file APK (memastikan unduhan utuh & sama dengan yang dibangun CI); null di versi.json lama. */
    val apkSha256: String? = null,
)

/** Hasil pemeriksaan APK unduhan sebelum dipasang. */
enum class ArchiveVerdict(val message: String) {
    OK("APK asli dan lebih baru."),
    BAD_FILE("File unduhan rusak / bukan APK."),
    HASH_MISMATCH("Isi file tidak sama dengan rilis resmi (unduhan rusak atau Release baru saja berganti)."),
    WRONG_PACKAGE("APK ini bukan NgiBsen."),
    WRONG_CERT("Tanda tangan APK BERBEDA dengan aplikasi terpasang — ditolak demi keamanan."),
    NOT_NEWER("Versi di file ini tidak lebih baru dari yang terpasang."),
}

/** Logika murni pemberitahuan versi baru & cek keaslian (teruji di AppUpdateTest). */
object AppUpdate {
    fun parse(json: String): RemoteVersion? = try {
        val o = JSONObject(json.trim())
        val code = o.optLong("versionCode", -1)
        val name = o.optString("versionName").trim()
        if (code <= 0 || name.isEmpty()) null else RemoteVersion(
            versionCode = code,
            versionName = name,
            apk = o.optString("apk").trim().ifEmpty { null },
            certSha256 = normalize(o.optString("certSha256")),
            apkSha256 = normalize(o.optString("apkSha256")),
        )
    } catch (e: JSONException) {
        null
    }

    fun isNewer(remote: RemoteVersion, installedVersionCode: Long): Boolean = remote.versionCode > installedVersionCode

    /** Hex huruf kecil tanpa ":"/spasi; null bila bukan SHA-256 (64 digit hex). */
    fun normalize(fingerprint: String?): String? =
        fingerprint?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }

    /** true = sama, false = berbeda, null = tidak bisa dibandingkan. */
    fun sameCert(a: String?, b: String?): Boolean? {
        val x = normalize(a) ?: return null
        val y = normalize(b) ?: return null
        return x == y
    }

    /**
     * Pemeriksaan APK unduhan SEBELUM dipasang (urutan penting, yang pertama gagal yang dilaporkan):
     * file terbaca → isi sama dengan versi.json (bila ada hash) → paket sama → sertifikat SAMA dengan aplikasi
     * terpasang (dan dengan versi.json) → versi lebih baru. Android sendiri juga menolak sertifikat berbeda,
     * tapi diperiksa lebih dulu supaya pesannya jelas dan file palsu langsung dibuang.
     */
    fun verifyArchive(
        installedPackage: String,
        installedVersionCode: Long,
        installedCertSha256: String?,
        archivePackage: String?,
        archiveVersionCode: Long?,
        archiveCertSha256: String?,
        fileSha256: String?,
        remote: RemoteVersion?,
    ): ArchiveVerdict {
        if (archivePackage == null || archiveVersionCode == null) return ArchiveVerdict.BAD_FILE
        val expectedHash = normalize(remote?.apkSha256)
        if (expectedHash != null && normalize(fileSha256) != expectedHash) return ArchiveVerdict.HASH_MISMATCH
        if (archivePackage != installedPackage) return ArchiveVerdict.WRONG_PACKAGE
        if (sameCert(archiveCertSha256, installedCertSha256) != true) return ArchiveVerdict.WRONG_CERT
        if (remote?.certSha256 != null && sameCert(archiveCertSha256, remote.certSha256) != true) return ArchiveVerdict.WRONG_CERT
        if (archiveVersionCode <= installedVersionCode) return ArchiveVerdict.NOT_NEWER
        return ArchiveVerdict.OK
    }

    /** Nama file APK dari versi.json hanya boleh huruf/angka/titik/strip (tidak bisa dipakai menyusup ke path lain). */
    fun safeApkName(name: String?): String =
        name?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}\\.apk")) } ?: "NgiBsen-UDINUS.apk"

    /** "6db7 e5f4 8092 …" (kelompok 4 digit) supaya mudah dicocokkan dengan mata. */
    fun pretty(fingerprint: String?): String = normalize(fingerprint)?.chunked(4)?.joinToString(" ") ?: "-"
}
