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
)

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

    /** "6db7 e5f4 8092 …" (kelompok 4 digit) supaya mudah dicocokkan dengan mata. */
    fun pretty(fingerprint: String?): String = normalize(fingerprint)?.chunked(4)?.joinToString(" ") ?: "-"
}
