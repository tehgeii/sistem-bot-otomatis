package com.pengingatabsen.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menguji otak pengecek dengan tiruan perilaku SiAdin yang nyata (diamati dari screenshot & laporan):
 * - halaman presensi TANPA login tampil kosong "Belum Ada Presensi" tanpa form login;
 * - daftar kartu termuat belakangan, kadang didahului tulisan "Belum Ada Presensi";
 * - halaman depan menampilkan form login bila belum login (kecuali sesi rusak).
 */
class CheckerBrainTest {

    private class FakeSiadin(
        var sessionValid: Boolean,
        /** Halaman depan menampilkan form login saat sesi habis (false = sesi rusak, perlu dihapus). */
        val rootShowsForm: Boolean = true,
        val passwordOk: Boolean = true,
        /** Isi kartu matkul setelah termuat. */
        val cards: Probe = Probe.BUTTON,
        /** Lama kartu termuat (detik) setelah halaman presensi terbuka. */
        val cardsDelay: Int = 3,
        /** Selama kartu dimuat, tampil tulisan "Belum Ada Presensi" (menipu). */
        val placeholder: Boolean = true,
        /** Halaman presensi selalu dialihkan ke dashboard. */
        val redirectsAway: Boolean = false,
    ) {
        var onTarget = true
        var loadedAt = 0
        var cleared = false

        fun observe(tick: Int): Pair<PageKind, Probe> {
            val age = tick - loadedAt
            if (!onTarget) {
                if (age < 1) return PageKind.OTHER to Probe.LOADING
                val showForm = !sessionValid && (rootShowsForm || cleared)
                return PageKind.OTHER to if (showForm) Probe.LOGIN_FORM else Probe.NO_TEXT
            }
            if (age < 1) return PageKind.TARGET to Probe.LOADING
            if (!sessionValid) return PageKind.TARGET to Probe.NOT_LOGGED_EMPTY
            if (age < 1 + cardsDelay) return PageKind.TARGET to if (placeholder) Probe.EMPTY else Probe.LOADING
            return PageKind.TARGET to cards
        }

        fun apply(step: Step, tick: Int) {
            when (step) {
                Step.LoadTarget -> if (redirectsAway) { onTarget = false; loadedAt = tick } else { onTarget = true; loadedAt = tick }
                Step.LoadRoot -> { onTarget = false; loadedAt = tick }
                Step.ClearSessionAndLoadRoot -> { cleared = true; sessionValid = false; onTarget = false; loadedAt = tick }
                Step.FillLogin -> if (passwordOk) { sessionValid = true; onTarget = false; loadedAt = tick + 2 }
                else -> Unit
            }
        }
    }

    private data class Run(val outcome: Outcome, val reason: String, val tick: Int, val steps: List<Step>)

    private fun run(site: FakeSiadin, hasCredentials: Boolean = true): Run {
        val brain = CheckerBrain(hasCredentials)
        val steps = mutableListOf<Step>()
        for (tick in 0..200) {
            val (page, probe) = site.observe(tick)
            val step = brain.next(tick, page, probe)
            if (step != Step.Wait) steps += step
            if (step is Step.Finish) return Run(step.outcome, step.reason, tick, steps)
            site.apply(step, tick)
        }
        error("tidak selesai")
    }

    @Test
    fun validSession_presensiOpen_detectedQuickly() {
        val r = run(FakeSiadin(sessionValid = true))
        assertEquals(Outcome.OPEN, r.outcome)
        assertTrue("terlalu lama: ${r.tick}", r.tick <= 10)
    }

    @Test
    fun placeholderBeforeCards_isNotMistakenForWaiting() {
        // "Belum Ada Presensi" tampil 6 detik sebelum kartu "Presensi Sekarang" muncul.
        val r = run(FakeSiadin(sessionValid = true, cardsDelay = 6, placeholder = true))
        assertEquals(Outcome.OPEN, r.outcome)
    }

    @Test
    fun expiredSessionWithoutLoginForm_relogsInViaRoot() {
        // Kasus 7 Okt: cookie lama ada, sesi habis, halaman presensi kosong TANPA form login.
        val r = run(FakeSiadin(sessionValid = false, rootShowsForm = true))
        assertEquals(Outcome.OPEN, r.outcome)
        assertTrue(r.steps.contains(Step.LoadRoot))
        assertTrue(r.steps.contains(Step.FillLogin))
        assertTrue("terlalu lama: ${r.tick}", r.tick < CheckerBrain.DEADLINE_SECONDS)
    }

    @Test
    fun brokenSession_rootWithoutForm_clearsSessionThenLogsIn() {
        val r = run(FakeSiadin(sessionValid = false, rootShowsForm = false))
        assertEquals(Outcome.OPEN, r.outcome)
        assertTrue(r.steps.contains(Step.ClearSessionAndLoadRoot))
        assertTrue("terlalu lama: ${r.tick}", r.tick < CheckerBrain.DEADLINE_SECONDS)
    }

    @Test
    fun wrongPassword_reportsLoginFailed() {
        val r = run(FakeSiadin(sessionValid = false, passwordOk = false))
        assertEquals(Outcome.LOGIN_FAILED, r.outcome)
        assertEquals(CheckerBrain.MAX_SUBMITS, r.steps.count { it == Step.FillLogin })
    }

    @Test
    fun noCredentials_loginFormMeansUnknown() {
        val site = FakeSiadin(sessionValid = false).apply { onTarget = false }
        val r = run(site, hasCredentials = false)
        assertEquals(Outcome.UNKNOWN, r.outcome)
    }

    @Test
    fun notLoggedNeverBecomesWaiting() {
        // Sesi tak pernah bisa dipulihkan (form tak pernah muncul): harus UNKNOWN (terasa), bukan WAITING (diam).
        val site = object {
            val brain = CheckerBrain(hasCredentials = true)
        }
        var last: Step = Step.Wait
        for (tick in 0..200) {
            last = site.brain.next(tick, PageKind.TARGET, Probe.NOT_LOGGED_EMPTY)
            if (last is Step.Finish) break
        }
        assertEquals(Outcome.UNKNOWN, (last as Step.Finish).outcome)
    }

    @Test
    fun waitingCard_and_emptyDay_and_done() {
        assertEquals(Outcome.WAITING, run(FakeSiadin(sessionValid = true, cards = Probe.WAITING)).outcome)
        assertEquals(Outcome.WAITING, run(FakeSiadin(sessionValid = true, cards = Probe.EMPTY)).outcome)
        assertEquals(Outcome.DONE, run(FakeSiadin(sessionValid = true, cards = Probe.DONE)).outcome)
    }

    @Test
    fun flickeringButton_needsThreeInARow() {
        val brain = CheckerBrain(hasCredentials = true)
        val seq = listOf(Probe.BUTTON, Probe.LOADING, Probe.BUTTON, Probe.BUTTON)
        seq.forEachIndexed { i, p -> assertEquals(Step.Wait, brain.next(i, PageKind.TARGET, p)) }
        val step = brain.next(seq.size, PageKind.TARGET, Probe.BUTTON)
        assertEquals(Outcome.OPEN, (step as Step.Finish).outcome)
    }

    @Test
    fun endlessLoading_hitsDeadlineAsUnknown() {
        val brain = CheckerBrain(hasCredentials = true)
        var step: Step = Step.Wait
        var tick = 0
        while (step !is Step.Finish) step = brain.next(tick++, PageKind.TARGET, Probe.LOADING)
        assertEquals(Outcome.UNKNOWN, (step as Step.Finish).outcome)
        assertEquals(CheckerBrain.DEADLINE_SECONDS + 1, tick)
    }

    @Test
    fun targetAlwaysRedirected_givesUpAsUnknown() {
        val site = FakeSiadin(sessionValid = true, redirectsAway = true).apply { onTarget = false }
        val r = run(site)
        assertEquals(Outcome.UNKNOWN, r.outcome)
        assertEquals(CheckerBrain.MAX_TARGET_LOADS, r.steps.count { it == Step.LoadTarget })
    }

    @Test
    fun untrustedPage_stopsImmediately() {
        val step = CheckerBrain(true).next(0, PageKind.UNTRUSTED, Probe.LOADING)
        assertEquals(Outcome.UNKNOWN, (step as Step.Finish).outcome)
    }
}
