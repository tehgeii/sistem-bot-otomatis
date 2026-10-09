package com.pengingatabsen.logic

import java.net.URI

/**
 * Pemeriksaan alamat SiAdin yang ketat (murni & teruji di SiadinUrlsTest). NIM/password SiAdin HANYA boleh
 * diisikan di domain kampus lewat https; membandingkan awalan teks saja tidak cukup
 * ("https://mhs.dinus.ac.id.contoh.com" bukan SiAdin).
 */
object SiadinUrls {
    const val CAMPUS_DOMAIN = "dinus.ac.id"
    const val SIADIN_HOST = "mhs.dinus.ac.id"

    private fun parse(url: String?): URI? = url?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() }

    fun host(url: String?): String? = parse(url)?.host?.lowercase()?.trimEnd('.')

    fun isHttps(url: String?): Boolean = parse(url)?.scheme.equals("https", ignoreCase = true)

    /** dinus.ac.id atau subdomainnya (mis. SSO kampus). */
    fun isCampusHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == CAMPUS_DOMAIN || h.endsWith(".$CAMPUS_DOMAIN")
    }

    /** https://mhs.dinus.ac.id/... (halaman SiAdin mahasiswa). */
    fun isSiadinUrl(url: String?): Boolean = isHttps(url) && host(url) == SIADIN_HOST

    /** Alamat https di domain kampus. */
    fun isCampusUrl(url: String?): Boolean = isHttps(url) && isCampusHost(host(url))
}
