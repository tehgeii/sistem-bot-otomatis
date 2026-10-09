package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    // versi.json asli dari Release "terbaru" (9 Okt 2026).
    private val real = """{"versionCode":30,"versionName":"3.0","apk":"NgiBsen-UDINUS.apk","certSha256":"6db7e5f48092aa56d8ba187744725b9370aa46de851429c406f93bcb4e244aab","commit":"84fec7c4cb7472b4395cb4bf959052a16e49267c"}"""

    @Test
    fun parsesRealFile() {
        val v = AppUpdate.parse(real)!!
        assertEquals(30L, v.versionCode)
        assertEquals("3.0", v.versionName)
        assertEquals("NgiBsen-UDINUS.apk", v.apk)
        assertEquals("6db7e5f48092aa56d8ba187744725b9370aa46de851429c406f93bcb4e244aab", v.certSha256)
    }

    @Test
    fun rejectsBrokenFiles() {
        assertNull(AppUpdate.parse("<html>Not Found</html>"))
        assertNull(AppUpdate.parse("""{"versionName":"3.1"}"""))
        assertNull(AppUpdate.parse("""{"versionCode":31,"versionName":""}"""))
        // Sidik jari rusak → diabaikan, versi tetap terbaca.
        assertNull(AppUpdate.parse("""{"versionCode":31,"versionName":"3.1","certSha256":"xyz"}""")!!.certSha256)
    }

    @Test
    fun newerOnlyWhenCodeHigher() {
        val v = AppUpdate.parse(real)!!
        assertTrue(AppUpdate.isNewer(v, 29))
        assertFalse(AppUpdate.isNewer(v, 30))
        assertFalse(AppUpdate.isNewer(v, 31))
    }

    private val cert = "6db7e5f48092aa56d8ba187744725b9370aa46de851429c406f93bcb4e244aab"
    private val hash = "a".repeat(64)
    private val remote31 = RemoteVersion(31, "3.1", "NgiBsen-UDINUS.apk", cert, hash)

    private fun verdict(
        pkg: String? = "com.pengingatabsen",
        code: Long? = 31,
        archiveCert: String? = cert,
        fileHash: String? = hash,
        remote: RemoteVersion? = remote31,
        installedCode: Long = 30,
    ) = AppUpdate.verifyArchive("com.pengingatabsen", installedCode, cert, pkg, code, archiveCert, fileHash, remote)

    @Test
    fun archiveVerification() {
        assertEquals(ArchiveVerdict.OK, verdict())
        assertEquals(ArchiveVerdict.BAD_FILE, verdict(pkg = null))
        assertEquals(ArchiveVerdict.BAD_FILE, verdict(code = null))
        assertEquals(ArchiveVerdict.HASH_MISMATCH, verdict(fileHash = "b".repeat(64)))
        assertEquals(ArchiveVerdict.WRONG_PACKAGE, verdict(pkg = "com.lain"))
        assertEquals(ArchiveVerdict.WRONG_CERT, verdict(archiveCert = "0".repeat(64)))
        assertEquals(ArchiveVerdict.WRONG_CERT, verdict(archiveCert = null))
        // versi.json menyebut sertifikat lain (Release dibajak?) → tolak walau sama dengan yang terpasang.
        assertEquals(ArchiveVerdict.WRONG_CERT, verdict(remote = remote31.copy(certSha256 = "1".repeat(64))))
        assertEquals(ArchiveVerdict.NOT_NEWER, verdict(installedCode = 31))
        // versi.json lama tanpa hash: sertifikat tetap wajib cocok.
        assertEquals(ArchiveVerdict.OK, verdict(remote = remote31.copy(apkSha256 = null), fileHash = null))
        assertEquals(ArchiveVerdict.OK, verdict(remote = null))
    }

    @Test
    fun apkNameIsSanitized() {
        assertEquals("NgiBsen-UDINUS.apk", AppUpdate.safeApkName("NgiBsen-UDINUS.apk"))
        assertEquals("NgiBsen-UDINUS.apk", AppUpdate.safeApkName("../../evil.apk"))
        assertEquals("NgiBsen-UDINUS.apk", AppUpdate.safeApkName("x.exe"))
        assertEquals("NgiBsen-UDINUS.apk", AppUpdate.safeApkName(null))
        assertEquals("NgiBsen_3.3.apk", AppUpdate.safeApkName("NgiBsen_3.3.apk"))
    }

    @Test
    fun parsesApkHash() {
        val v = AppUpdate.parse(real.replace("}", ",\"apkSha256\":\"${"C".repeat(64)}\"}"))!!
        assertEquals("c".repeat(64), v.apkSha256)
        assertNull(AppUpdate.parse(real)!!.apkSha256)
    }

    @Test
    fun certComparisonIgnoresFormat() {
        val colon = "6D:B7:E5:F4:80:92:AA:56:D8:BA:18:77:44:72:5B:93:70:AA:46:DE:85:14:29:C4:06:F9:3B:CB:4E:24:4A:AB"
        assertEquals(true, AppUpdate.sameCert(colon, "6db7e5f48092aa56d8ba187744725b9370aa46de851429c406f93bcb4e244aab"))
        assertEquals(false, AppUpdate.sameCert(colon, "0".repeat(64)))
        assertNull(AppUpdate.sameCert(null, colon))
        assertEquals("6db7 e5f4 8092", AppUpdate.pretty(colon).take(14))
        assertEquals("-", AppUpdate.pretty("abc"))
    }
}
