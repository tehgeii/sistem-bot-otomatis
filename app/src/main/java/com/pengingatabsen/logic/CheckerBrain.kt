package com.pengingatabsen.logic

/**
 * Hasil baca halaman SiAdin satu kali (dari skrip `probeScript`), tanpa ketergantungan Android.
 */
enum class Probe {
    /** Form login (NIM + password + tombol Masuk) benar-benar tampil. */
    LOGIN_FORM,
    /** Halaman/kartu belum selesai dimuat. */
    LOADING,
    /** Halaman sudah termuat tapi belum login (kotak masa studi kosong), tanpa tulisan "Belum Ada Presensi". */
    NOT_LOGGED,
    /** Seperti [NOT_LOGGED] tapi tertulis "Belum Ada Presensi" (halaman tanpa login juga menulis ini). */
    NOT_LOGGED_EMPTY,
    /** Sudah login, tanpa kartu, tertulis "Belum Ada Presensi". */
    EMPTY,
    /** Sudah login & termuat, tanpa kartu dan tanpa tulisan apa pun yang dikenali. */
    NO_TEXT,
    /** Kartu matkul ini "Belum Jadwalnya". */
    WAITING,
    /** Kartu matkul ini "Presensi Sekarang". */
    BUTTON,
    /** Kartu matkul ini "Berhasil Presensi". */
    DONE,
    ;

    companion object {
        fun parse(raw: String?): Probe = entries.firstOrNull { it.name == raw } ?: LOADING
    }
}

/** Jenis halaman yang sedang tampil di WebView pengecek. */
enum class PageKind { TARGET, OTHER, UNTRUSTED }

/** Kesimpulan akhir satu pengecekan. */
enum class Outcome { WAITING, OPEN, DONE, UNKNOWN, LOGIN_FAILED }

/** Langkah yang harus dikerjakan pelaksana (WebView) setelah satu kali baca. */
sealed class Step {
    data object Wait : Step()
    data object LoadTarget : Step()
    data object LoadRoot : Step()
    /** Sesi rusak: hapus cookie & penyimpanan situs, lalu buka halaman depan (form login pasti muncul). */
    data object ClearSessionAndLoadRoot : Step()
    data object FillLogin : Step()
    data class Finish(val outcome: Outcome, val reason: String) : Step()
}

/**
 * OTAK pengecek presensi SiAdin di latar — murni & teruji (lihat CheckerBrainTest).
 *
 * Pelaksana memanggil [next] kira-kira tiap detik dengan jenis halaman dan hasil baca halaman, lalu
 * mengerjakan langkah yang dikembalikan. Aturan utama:
 * - Status apa pun baru dipercaya bila terbaca sama beberapa kali BERTURUT-TURUT (tampilan sementara
 *   saat memuat tidak boleh menipu).
 * - Form login → isi otomatis (maks. [MAX_SUBMITS]x). Form tetap tampil setelah itu → [Outcome.LOGIN_FAILED].
 * - Halaman presensi termuat tapi BELUM login (SiAdin tidak selalu menampilkan form login saat sesi habis):
 *   login ulang lewat halaman depan; bila masih belum login, hapus sesi lama lalu login dari awal.
 *   Keraguan soal login TIDAK PERNAH disimpulkan "menunggu" (halaman tanpa login juga menulis
 *   "Belum Ada Presensi"), melainkan [Outcome.UNKNOWN] yang memicu notifikasi "Cek presensi".
 * - Halaman lain (dashboard) yang sudah termuat → buka halaman presensi.
 * - Batas waktu [deadline] detik → [Outcome.UNKNOWN] (alarm berikutnya mencoba lagi).
 */
class CheckerBrain(
    private val hasCredentials: Boolean,
    private val deadline: Int = DEADLINE_SECONDS,
) {
    private var lastKey: String? = null
    private var streak = 0
    private var cooldownUntil = -1
    private var submits = 0
    /** 0 = belum, 1 = sudah lewat halaman depan, 2 = sesi sudah dihapus. */
    private var reloginStage = 0
    private var targetLoads = 0
    /** Halaman presensi "kosong" (tanpa kartu & tulisan) sudah pernah dimuat ulang. */
    private var noTextReloaded = false

    /** Catatan singkat untuk log diagnosis. */
    val summary: String
        get() = "login dikirim ${submits}x, login ulang tahap $reloginStage, buka halaman presensi ${targetLoads}x"

    fun next(tick: Int, page: PageKind, probe: Probe): Step {
        if (tick >= deadline) {
            return Step.Finish(Outcome.UNKNOWN, "waktu habis ($deadline dtk), terakhir terbaca: $lastKey")
        }
        if (page == PageKind.UNTRUSTED) return Step.Finish(Outcome.UNKNOWN, "halaman di luar SiAdin")

        val key = "$page/$probe"
        streak = if (key == lastKey) streak + 1 else 1
        lastKey = key
        if (tick < cooldownUntil) return Step.Wait

        if (probe == Probe.LOGIN_FORM) {
            if (!hasCredentials) {
                return Step.Finish(Outcome.UNKNOWN, "SiAdin minta login, tapi NIM/password belum disimpan")
            }
            if (submits >= MAX_SUBMITS) {
                return Step.Finish(Outcome.LOGIN_FAILED, "form login tetap tampil setelah dikirim ${submits}x")
            }
            submits++
            cooldownUntil = tick + AFTER_SUBMIT_WAIT
            return Step.FillLogin
        }

        if (page == PageKind.OTHER) {
            // Halaman depan/dashboard sudah termuat (bukan form login): lanjut ke halaman presensi.
            // Halaman lain yang tak kunjung selesai "memuat" juga ditinggalkan setelah beberapa detik.
            val ready = if (probe == Probe.LOADING) streak >= STABLE_OTHER_LOADING else streak >= STABLE_OTHER
            if (!ready) return Step.Wait
            if (targetLoads >= MAX_TARGET_LOADS) {
                return Step.Finish(Outcome.UNKNOWN, "halaman presensi tidak bisa dibuka (dialihkan terus)")
            }
            targetLoads++
            cooldownUntil = tick + AFTER_LOAD_WAIT
            return Step.LoadTarget
        }

        // page == TARGET
        return when (probe) {
            Probe.LOADING, Probe.LOGIN_FORM -> Step.Wait
            Probe.BUTTON -> stable(STABLE_CARD, Outcome.OPEN, "kartu: Presensi Sekarang")
            Probe.DONE -> stable(STABLE_CARD, Outcome.DONE, "kartu: Berhasil Presensi")
            Probe.WAITING -> stable(STABLE_CARD, Outcome.WAITING, "kartu: Belum Jadwalnya")
            Probe.EMPTY -> stable(STABLE_EMPTY, Outcome.WAITING, "tanpa kartu: Belum Ada Presensi")
            Probe.NO_TEXT -> noText(tick)
            Probe.NOT_LOGGED, Probe.NOT_LOGGED_EMPTY -> notLogged(tick)
        }
    }

    private fun stable(needed: Int, outcome: Outcome, reason: String): Step =
        if (streak >= needed) Step.Finish(outcome, reason) else Step.Wait

    /**
     * Sudah login tapi halaman presensi tanpa kartu maupun tulisan: tunggu lebih lama (daftar kartu SiAdin
     * bisa termuat lambat), muat ulang sekali, baru menyerah sebagai UNKNOWN.
     */
    private fun noText(tick: Int): Step {
        if (streak < STABLE_NO_TEXT) return Step.Wait
        if (!noTextReloaded) {
            noTextReloaded = true
            cooldownUntil = tick + AFTER_LOAD_WAIT
            return Step.LoadTarget
        }
        return Step.Finish(Outcome.UNKNOWN, "halaman presensi tanpa kartu/tulisan (sudah dimuat ulang)")
    }

    /** Halaman presensi termuat tapi belum login: login ulang bertahap, berakhir UNKNOWN (bukan WAITING). */
    private fun notLogged(tick: Int): Step {
        if (streak < STABLE_NOT_LOGGED) return Step.Wait
        cooldownUntil = tick + AFTER_LOAD_WAIT
        return when (reloginStage) {
            0 -> {
                reloginStage = 1
                Step.LoadRoot
            }
            1 -> {
                reloginStage = 2
                Step.ClearSessionAndLoadRoot
            }
            else -> Step.Finish(Outcome.UNKNOWN, "tetap belum login setelah login ulang (data akun tidak tampil)")
        }
    }

    companion object {
        const val DEADLINE_SECONDS = 80
        const val MAX_SUBMITS = 2
        const val MAX_TARGET_LOADS = 4
        /** Kartu harus terbaca sama sekian kali berturut-turut. */
        const val STABLE_CARD = 3
        /** "Belum Ada Presensi"/tanpa kartu: tunggu lebih lama, kartu sering termuat belakangan. */
        const val STABLE_EMPTY = 10
        /** Halaman login tanpa kartu & tulisan: tunggu sekian detik sebelum muat ulang / menyerah. */
        const val STABLE_NO_TEXT = 15
        /** Belum login harus stabil sekian detik (data akun kadang termuat lambat). */
        const val STABLE_NOT_LOGGED = 6
        /** Halaman lain yang sudah termuat harus stabil sekian detik sebelum ditinggalkan. */
        const val STABLE_OTHER = 2
        /** Halaman lain yang tak kunjung selesai memuat ditinggalkan setelah sekian detik. */
        const val STABLE_OTHER_LOADING = 8
        const val AFTER_SUBMIT_WAIT = 5
        const val AFTER_LOAD_WAIT = 3
    }
}
