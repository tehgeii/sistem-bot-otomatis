package com.pengingatabsen.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiadinUrlsTest {
    @Test
    fun realSiadinUrls() {
        assertTrue(SiadinUrls.isSiadinUrl("https://mhs.dinus.ac.id/akademik/presensiOnline"))
        assertTrue(SiadinUrls.isSiadinUrl("https://MHS.Dinus.ac.id/"))
        assertTrue(SiadinUrls.isCampusUrl("https://sso.dinus.ac.id/login"))
        assertTrue(SiadinUrls.isCampusUrl("https://dinus.ac.id/"))
    }

    @Test
    fun lookalikesAreRejected() {
        assertFalse(SiadinUrls.isSiadinUrl("https://mhs.dinus.ac.id.contoh.com/akademik/presensiOnline"))
        assertFalse(SiadinUrls.isCampusUrl("https://mhs.dinus.ac.id.contoh.com/"))
        assertFalse(SiadinUrls.isCampusUrl("https://evildinus.ac.id/"))
        assertFalse(SiadinUrls.isCampusUrl("https://dinus.ac.id@contoh.com/"))
        assertFalse(SiadinUrls.isSiadinUrl("http://mhs.dinus.ac.id/akademik/presensiOnline"))
        assertFalse(SiadinUrls.isSiadinUrl("dinusverse://presensi"))
        assertFalse(SiadinUrls.isSiadinUrl(""))
        assertFalse(SiadinUrls.isSiadinUrl(null))
        assertFalse(SiadinUrls.isSiadinUrl("bukan url sama sekali"))
    }
}
