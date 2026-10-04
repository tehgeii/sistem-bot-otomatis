# Pengingat Absen

Aplikasi Android pribadi untuk mengingatkan absen kuliah di **Dinusverse (SiAdin Mobile, UDINUS)**.

Saat jam absen dibuka, muncul notifikasi berisi nama mata kuliah dan tombol untuk membuka Dinusverse.
Kamu absen sendiri di sana, lalu aplikasi ini mengirim bukti ke Telegram kamu.

> **Yang TIDAK dilakukan aplikasi ini:** mengirim absen otomatis, memakai API Dinusverse,
> Accessibility Service, otomatisasi layar, memalsukan lokasi, atau menyimpan NIM/password.
> Aplikasi ini hanya **mengingatkan, membuka Dinusverse, mencatat, dan mengirim bukti ke Telegram**.

---

## Cara kerja harian (setelah setup sekali)

1. Jam absen dibuka → notifikasi heads-up berbunyi: **"Absen dibuka: Basis Data"**
   (jam buka–tutup dan ruang). Tombol: **Absen sekarang** · **Tunda 5 menit** · **Libur**.
2. Tap **Absen sekarang** (atau tap notifikasinya) → Dinusverse terbuka, notifikasi berubah
   menjadi **"Sudah absen Basis Data?"**.
3. Setelah absen berhasil, tap **Sudah, kirim bukti** → Telegram menerima:
   `✅ Absen Basis Data — Senin, 5 Oktober 2026 07:03:09`
   (waktunya adalah saat tombol ditekan, bukan saat pesan terkirim).
4. Mau bukti lebih kuat? Screenshot halaman sukses → **Bagikan** → **Kirim bukti absen**
   (Pengingat Absen). Screenshot dikirim lewat `sendPhoto` dengan caption yang sama dan otomatis
   dikaitkan ke matkul yang sedang aktif.

Kalau kamu belum menandai selesai, notifikasi berbunyi lagi **tiap 3 menit** (bisa diubah) sampai
absen ditutup. **5 menit sebelum ditutup** muncul peringatan terakhir yang lebih tegas. Kalau tetap
terlewat, riwayat mencatat **terlewat** dan Telegram menerima `❌ Terlewat absen …`.
Jika jam tutup kosong, pengingat berhenti 30 menit setelah dibuka.

Kalau sedang offline, bukti masuk antrean dan otomatis terkirim saat ada internet.

**Kapan pengingat berhenti?** Begitu kamu menekan **Sudah, kirim bukti** (atau **Libur**), atau
membagikan screenshot bukti. Menekan **Absen sekarang** saja belum menghentikannya: setelah satu
interval, muncul lagi "Sudah absen?" sampai kamu konfirmasi.

**Getar saja (default):** di *Pengaturan → Pengingat*, opsi **Getar saja (tanpa suara)** aktif
sejak awal supaya tidak berbunyi di kelas. Matikan jika ingin pakai suara notifikasi.
Ini notifikasi biasa, bukan nada dering alarm; AlarmManager hanya dipakai sebagai pewaktu
(karena itu ikon jam alarm tampil di status bar).

---

## Cara install APK

1. Buka tab **Actions** di repo GitHub ini → pilih run **Build APK** terbaru yang hijau.
2. Di bagian **Artifacts**, unduh **pengingat-absen-debug** (file `.zip`), lalu ekstrak →
   dapat `app-debug.apk`. (Bisa langsung dari browser HP, lalu buka dengan aplikasi Files.)
3. Buka file APK. Jika diminta, izinkan **Instal aplikasi tak dikenal** untuk browser/Files.
4. Selesai. Versi baru cukup diinstal di atas versi lama — data jadwal & riwayat tetap ada
   (semua build memakai keystore debug yang sama, lihat bagian *Catatan keputusan*).

---

## Setup pertama (wizard 3 langkah)

Saat pertama dibuka, aplikasi menjalankan wizard. Semua langkah boleh dilewati dan bisa diatur
lagi di tab **Pengaturan**.

### Langkah 1 — Izin

| Izin | Kenapa perlu | Cara mengaktifkan |
|---|---|---|
| **Notifikasi** (Android 13+) | Supaya pengingat muncul | Otomatis diminta; tekan *Izinkan* |
| **Alarm & pengingat** (exact alarm) | Supaya berbunyi tepat di jam absen | Android 13+ biasanya otomatis aktif. Jika tidak: *Buka* → aktifkan *Izinkan setel alarm dan pengingat* |
| **Tanpa optimasi baterai** | Supaya alarm tidak ditahan sistem saat HP tidur | *Izinkan* → pilih *Izinkan* di dialog |

Tekan **Tes notifikasi** untuk memastikan notifikasi muncul dengan suara.

**HP Xiaomi / Oppo / Vivo / Realme / Samsung:** pengaturan baterai pabrikan sering lebih agresif.
Buka *Pengaturan → Aplikasi → Pengingat Absen* lalu:
- aktifkan **Mulai otomatis / Autostart**,
- set **Baterai** ke *Tidak dibatasi* / *Tanpa batasan*,
- (Samsung) keluarkan dari *Aplikasi tidur*.

### Langkah 2 — Aplikasi tujuan

Aplikasi yang dibuka oleh tombol **Absen sekarang**. Jika Dinusverse/SiAdin terpasang, aplikasi
memilihnya otomatis; kalau tidak, tekan **Pilih aplikasi** dan pilih dari daftar.
Nama package tidak di-hardcode.

Opsional: isi **URL deep link**. Jika diisi, tombol membuka URL itu; jika kosong, membuka aplikasi
yang dipilih.

### Langkah 3 — Telegram

**Membuat bot lewat @BotFather:**

1. Buka Telegram, cari **@BotFather** (centang biru), tekan **Start**.
2. Kirim `/newbot`.
3. Isi nama bot, mis. `Absen Saya`.
4. Isi username bot (harus berakhiran `bot`), mis. `absen_namaku_bot`.
5. BotFather membalas dengan **token** seperti `123456789:AAH...`. Salin token itu.
   **Jangan bagikan token ke siapa pun.**

**Menghubungkan ke aplikasi:**

1. Tempel token di kolom **Bot token**, tekan **Simpan** (token dicek lewat `getMe`).
2. Tekan **Buka @nama_bot**, lalu kirim `/start` ke bot.
3. Aplikasi mengambil chat ID sendiri lewat `getUpdates` (dicek tiap 3 detik) — tidak perlu
   mencari chat ID manual. Jika belum muncul, tekan **Cek lagi**.
4. Tekan **Tes kirim** → pesan tes masuk ke Telegram kamu.

Token disimpan **terenkripsi** (AES-GCM, kunci di Android Keystore) di perangkat, tidak pernah
masuk repo, log, atau backup (`allowBackup=false`).

> Jika bot kamu pernah dipasangi webhook, `getUpdates` tidak berfungsi. Hapus dulu dengan membuka
> `https://api.telegram.org/bot<TOKEN>/deleteWebhook` di browser.

---

## Mengisi jadwal

Tab **Jadwal** → **Tambah**. Isi nama matkul, hari, jam absen dibuka, jam ditutup (opsional),
ruang (opsional). Jadwal berulang tiap minggu dan dikelompokkan per hari.

- **Simpan & tambah lagi**: simpan lalu langsung buka isian baru (hari sama, jam lanjut dari jam
  tutup sebelumnya) — semua matkul bisa diisi sekali duduk.
- Menu ⋮ tiap matkul: **Duplikat**, **Libur hari ini**, **Lewati minggu ini**,
  **Batalkan libur**, **Hapus**.
- Saklar di kanan: aktif/nonaktif.

## Widget

Tahan layar utama → **Widget** → **Pengingat Absen**. Widget menampilkan matkul berikutnya dan jam
absennya. Tap widget = buka Dinusverse.

## Riwayat

Tab **Riwayat** menampilkan setiap absen: matkul, waktu, dan status
**terkirim / antre / gagal / terlewat / libur**. Bukti yang gagal bisa **Kirim ulang**
(juga tersedia sebagai tombol di notifikasi "Gagal kirim bukti").

---

## Login ulang Dinusverse cukup satu tap (Autofill Google Password Manager)

Aplikasi ini **tidak** menyimpan NIM/password. Supaya login ulang di Dinusverse cepat, pakai
fitur Autofill bawaan Android:

1. Buka **Setelan → Sistem → Bahasa & input → Layanan isi otomatis** (di beberapa HP:
   **Setelan → Google → Isi otomatis**, atau cari "isi otomatis" / "autofill" di Setelan).
2. Pilih **Google** sebagai layanan isi otomatis (Google Password Manager).
3. Pastikan **Setelan → Google → Isi otomatis → Isi otomatis dengan Google** aktif dan
   **Pengelola Sandi** aktif.
4. Buka Dinusverse dan login sekali seperti biasa. Saat muncul **"Simpan sandi ke Google?"**,
   pilih **Simpan**.
5. Berikutnya, saat diminta login, tap kolom NIM → pilih akun yang tersimpan → login cukup
   satu tap (mungkin diminta sidik jari/PIN layar).

Jika tawaran simpan tidak muncul, simpan manual: buka **Pengelola Sandi Google**
(passwords.google.com atau *Setelan → Google → Isi otomatis → Pengelola Sandi*) → **Tambah**.

---

## Catatan keputusan (hal yang ambigu)

Bila spesifikasi ambigu, dipilih opsi paling sederhana:

- **Alarm** memakai `setAlarmClock` untuk semua bunyi (buka, ulang, terakhir, tutup) karena paling
  tepat waktu dan tidak dibatasi Doze. Efek sampingnya: ikon jam alarm tampil di status bar.
  Tanpa izin exact alarm, aplikasi memakai `setAndAllowWhileIdle` (bisa telat beberapa menit).
- **Satu alarm aktif per matkul**; setelah berbunyi, alarm berikutnya dihitung ulang
  (`ScheduleMath.plan`). Semua alarm dihitung ulang saat reboot, update aplikasi, jam/zona waktu
  berubah, dan setiap aplikasi dibuka.
- **Tanpa jam tutup** = jendela 30 menit; notifikasi terakhir tetap muncul 5 menit sebelum
  (menit ke-25). Jam tutup ≤ jam buka dianggap kosong.
- **Tunda 5 menit** tidak pernah melewati notifikasi terakhir.
- **Absen sekarang** memberi waktu satu interval (default 3 menit) sebelum pengingat berikutnya;
  pengingat berikutnya menampilkan lagi "Sudah absen?" dengan suara. **Belum** mengembalikan
  notifikasi utama tanpa suara.
- Ada tombol tambahan **Libur** di notifikasi (selain dua tombol wajib) agar kuliah kosong bisa
  dihentikan dengan satu tap.
- **Libur hari ini** hanya muncul bila matkul memang ada hari ini. **Lewati minggu ini** berlaku
  untuk minggu berjalan (Senin–Minggu).
- **Screenshot yang dibagikan** dikaitkan ke: (1) matkul yang sedang dibuka & belum selesai;
  (2) jika tidak ada, absen yang dikonfirmasi ≤ 2 jam lalu (foto menyusul setelah pesan teks);
  (3) jika tidak ada, matkul hari ini yang jam bukanya paling dekat; (4) jika tidak ada jadwal
  hari ini, dicatat sebagai "Tanpa matkul".
- **Terlewat** juga dicatat jika HP mati sepanjang jendela absen yang sudah berjalan.
  Menonaktifkan/menghapus matkul saat jendelanya sedang berlangsung mencatatnya sebagai "libur".
- **Gagal** = token/chat salah atau ditolak Telegram, atau 10 kali percobaan gagal. Saat offline
  status tetap **antre** sampai ada internet.
- **Keystore debug** (`app/debug.keystore`, password publik `android`) sengaja di-commit supaya
  semua APK dari CI bertanda tangan sama dan bisa saling menimpa tanpa uninstall. Ini bukan
  rahasia; APK ini hanya untuk pemakaian pribadi.
- **targetSdk/compileSdk 36** (Android 16).

---

## Teknologi & struktur

Kotlin · Jetpack Compose · Material 3 · Room · DataStore · AlarmManager · WorkManager · OkHttp.
Satu modul `app`, MVVM + repository sederhana (service locator `Graph`).

```
app/src/main/java/com/pengingatabsen/
├── App.kt                  Application + Graph (service locator)
├── data/                   Room (Course, AttendanceRecord), DataStore, enkripsi token, Repository
├── logic/                  ScheduleMath (hitung alarm & pengingat ulang), Formatters (teks Indonesia)
├── alarm/                  AlarmScheduler, receiver alarm/aksi/boot, notifikasi, izin
├── launch/                 Buka Dinusverse (LaunchTargetActivity), share target screenshot
├── telegram/               Bot API client (OkHttp) + SendWorker (WorkManager, retry offline)
├── widget/                 Widget layar utama
└── ui/                     Compose: jadwal, riwayat, pengaturan, wizard
app/src/test/…/ScheduleMathTest.kt   Unit test perhitungan waktu
```

## Build sendiri

Butuh JDK 17 dan Android SDK.

```bash
./gradlew testDebugUnitTest   # unit test
./gradlew assembleDebug       # APK: app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions (`.github/workflows/build.yml`) menjalankan unit test dan membangun APK debug di
setiap push, lalu mengunggahnya sebagai artifact **pengingat-absen-debug**.

## Privasi

- Tidak ada secret di repo. Bot token hanya ada di HP, terenkripsi.
- Tidak ada NIM/password Dinusverse yang diminta atau disimpan.
- Data jadwal & riwayat hanya di perangkat; yang keluar hanya pesan bukti ke bot Telegram milikmu.

## Kontributor

- [@tehgeii](https://github.com/tehgeii) — ide, kebutuhan, dan arah desain aplikasi
- Claude (Claude Code) — implementasi kode
