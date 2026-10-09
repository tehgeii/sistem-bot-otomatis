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
