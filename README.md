# NgiBsen UDINUS

**Versi 3.3** · **NgiBsen** = pe**Ngi**ngat a**Bsen** — pengingat absen kuliah untuk mahasiswa UDINUS.

Aplikasi Android untuk mengingatkan absen kuliah **UDINUS** — lewat **Presensi Online SiAdin web**
(disarankan, dengan login otomatis) atau aplikasi **Dinusverse (SiAdin Mobile)**.

Saat jam absen dibuka, HP bergetar dan muncul notifikasi berisi nama mata kuliah. Satu tap membuka
halaman presensi, kamu menekan tombol presensi sendiri, lalu aplikasi ini mengirim bukti ke Telegram kamu.

> **Yang TIDAK dilakukan aplikasi ini:** menekan tombol presensi / mengirim absen otomatis,
> memakai API Dinusverse, Accessibility Service, memalsukan lokasi.
> Tombol presensi selalu ditekan sendiri oleh pemilik HP.
> Aplikasi ini hanya **mengingatkan, membuka halaman presensi (dan login otomatis bila diaktifkan),
> mencatat, dan mengirim bukti ke Telegram**.

**Kompatibel:** Android **8.0 (Oreo) sampai Android 16** dan yang lebih baru (minSdk 26, targetSdk 36).
Tidak tersedia untuk iPhone.

---

## Cara kerja harian (setelah setup sekali)

**Layar "Hari ini"** (paling atas tab Jadwal): matkul hari ini dengan status langsung — 🕒 Nanti (hitung
mundur ke jam buka) · ⏳ Menunggu · 🔵 Dibuka! (tombol **Presensi sekarang**) · ✅ Berhasil · 🏖 Libur ·
❌ Terlewat — plus hasil cek kesiapan dan peringatan alarm terlewat. Hari tanpa kuliah: matkul berikutnya.


1. Jam absen dibuka → notifikasi heads-up (getar): **"Waktunya absen: Basis Data"** (mode pintar: **"✅ Presensi sudah dibuka: Basis Data"** begitu dosen membuka presensi)
   (jam buka–tutup dan ruang). Tombol: **Absen sekarang** · **Tunda 5 menit** · **Libur**.
2. Tap **Absen sekarang** (atau tap notifikasinya):
   - **Mode SiAdin web (disarankan):** halaman **Presensi Online** terbuka di browser mini di dalam
     aplikasi. Jika sesi habis, aplikasi login otomatis lalu langsung ke halaman presensi.
   - **Mode Dinusverse:** aplikasi Dinusverse terbuka; buka menu **Kehadiran**.
3. **Tekan tombol presensi sendiri** di halaman tersebut.
4. Kirim bukti (pilih salah satu):
   - **📷 Kirim screenshot** di bawah browser mini → gambar halaman yang tampil dikirim ke Telegram
     sebagai foto. **Paling praktis: satu tap, bukti foto terkirim dan pengingat langsung berhenti.**
   - **✅ Sudah, kirim bukti** (di browser mini atau di notifikasi "Sudah absen Basis Data?") →
     Telegram menerima pesan teks `✅ Absen Basis Data — Senin, 5 Oktober 2026 07:03:09`.
   - Mode Dinusverse: screenshot halaman sukses → **Bagikan** → **Kirim bukti absen**.

Waktu di pesan adalah saat tombol ditekan / screenshot diambil, bukan saat pesan terkirim. Bukti
otomatis dikaitkan ke matkul yang sedang dibuka absennya.

Kalau kamu belum menandai selesai, notifikasi muncul lagi **tiap 3 menit** (bisa diubah) sampai
absen ditutup. **5 menit sebelum ditutup** muncul peringatan terakhir yang lebih tegas. Kalau tetap
terlewat, riwayat mencatat **terlewat** dan Telegram menerima `❌ Terlewat absen …`.
Jika jam tutup kosong, pengingat berhenti 30 menit setelah dibuka.

Kalau sedang offline, bukti masuk antrean dan otomatis terkirim saat ada internet.

**Kapan pengingat berhenti?** Begitu kamu menekan **Sudah, kirim bukti**, **Kirim screenshot**,
atau **Libur**, atau membagikan screenshot bukti. Menekan **Absen sekarang** saja belum menghentikannya: setelah satu
interval, muncul lagi "Sudah absen?" sampai kamu konfirmasi.
**Mode pintar** tidak menanyakan "Sudah absen?": setiap pengingat mengecek SiAdin dulu, dan begitu kartu
matkul berubah jadi **Berhasil Presensi** (lewat browser mini, Chrome, atau Dinusverse) pengingat
berhenti sendiri dan bukti terkirim.

**Getar saja (default):** di *Pengaturan → Pengingat*, opsi **Getar saja (tanpa suara)** aktif
sejak awal supaya tidak berbunyi di kelas. Matikan jika ingin pakai suara notifikasi.
Ini notifikasi biasa, bukan nada dering alarm; AlarmManager hanya dipakai sebagai pewaktu
(karena itu ikon jam alarm tampil di status bar).

---

## Cara install APK

1. Buka **[Releases → terbaru](https://github.com/tehgeii/sistem-bot-otomatis/releases/latest)** di repo ini (tidak perlu login GitHub).
2. Unduh **NgiBsen-UDINUS.apk** (sebelum versi 3.1 namanya `Pengingat.Absen.UDINUS.apk`; isinya
   aplikasi yang sama, cukup instal di atasnya).
3. Buka file APK. Jika diminta, izinkan **Instal aplikasi tak dikenal** untuk browser/Files.
   Jika Play Protect memperingatkan "aplikasi tidak dikenal", pilih **Tetap instal**.
4. Selesai. Versi baru cukup diinstal di atas versi lama — data jadwal & riwayat tetap ada.
   Mulai **3.3**, versi berikutnya cukup lewat **Perbarui sekarang** di dalam aplikasi (lihat
   [Versi baru & keaslian APK](#versi-baru--keaslian-apk)).

### Pindah dari versi lama (nama "Pengingat Absen", sebelum versi 1.1)

Versi lama ditandatangani dengan kunci berbeda, jadi **sekali ini** harus:
**uninstall aplikasi lama → instal NgiBsen UDINUS → jalankan setup lagi** (jadwal, bot Telegram,
login SiAdin). Setelah itu, update berikutnya cukup instal di atasnya.

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

Tekan **Tes notifikasi** untuk memastikan notifikasi muncul dan HP bergetar. Tombol pada notifikasi
tes hanya menutupnya (tidak mengirim apa pun ke Telegram), kecuali **Absen sekarang** yang membuka
halaman presensi untuk dicoba.

**Penting agar pengecekan & layar penuh tetap jalan saat HP terkunci** — pabrikan sering mematikan
aplikasi di latar belakang. Tombol **Buka Autostart** di *Pengaturan → Izin HP* langsung membuka
layar Autostart merek HP-mu bila tersedia. Atau buka *Pengaturan → Aplikasi → NgiBsen UDINUS*, set **Baterai** ke
*Tidak dibatasi*, lalu per merek:

| Merek | Yang perlu diaktifkan |
|---|---|
| **Xiaomi / Redmi / POCO** (HyperOS/MIUI) | Autostart **ON**; Baterai → *Tanpa batasan*; izin lain → **Tampilkan jendela pop-up saat berjalan di latar belakang** & **di layar kunci** |
| **Oppo / Realme / OnePlus** (ColorOS) | *Izinkan Mulai Otomatis*; Baterai → *Izinkan aktivitas latar belakang* / *Jangan optimalkan*; **kunci NgiBsen di Recent apps** (tahan kartunya → ikon gembok) supaya tidak ikut dibersihkan |
| **Vivo / iQOO** (Funtouch/OriginOS) | *Mulai otomatis* **ON**; Baterai → *Konsumsi daya tinggi di latar belakang* diizinkan |
| **Samsung** (One UI) | Baterai → *Tanpa batasan*; keluarkan dari *Aplikasi tidur* & *Aplikasi tidur lelap* |
| **Infinix / Tecno** (XOS) | *Autostart* **ON**; *Freezer*/penghemat → kecualikan NgiBsen |

Bila layar penuh "Presensi sudah dibuka!" tidak muncul padahal presensi sudah dibuka, biasanya izin
**pop-up di layar kunci / latar belakang** di tabel di atas belum aktif.

### Langkah 2 — SiAdin web atau aplikasi tujuan

**Paling cepat (disarankan): Presensi Online SiAdin web.** Tekan **Pakai Presensi Online SiAdin**,
isi **NIM** dan **password SiAdin** sekali, lalu **Simpan login**. Mulai sekarang
**Absen sekarang** membuka `https://mhs.dinus.ac.id/akademik/presensiOnline` di **browser mini**
di dalam aplikasi:

- Browser mini masuk lewat halaman depan `mhs.dinus.ac.id` dulu. Jika form login muncul, NIM &
  password diisi dan tombol **Masuk ke SiAdin** ditekan otomatis, lalu langsung ke halaman
  Presensi Online. Saat dibuka, layar bisa "loncat" 2–3 kali — itu normal.
- Halaman yang tampil adalah website SiAdin asli dari server UDINUS (real-time, sama seperti di
  Chrome). Tombol ⟳ di atas untuk memuat ulang bila dosen baru membuka presensi.
- Tombol **presensi di website tetap kamu yang tekan.**
- Di bawah halaman ada **✅ Sudah, kirim bukti** dan **📷 Kirim screenshot** (gambar halaman yang
  sedang tampil langsung dikirim ke Telegram), jadi tidak perlu kembali ke notifikasi.
- Tombol **Chrome** di atas membuka halaman yang sama di Chrome bila perlu (mis. untuk menu lain
  seperti KRS/KHS — menu samping SiAdin kadang tampil kosong di browser mini).
- Jika halaman presensi meminta lokasi atau kamera, Android akan meminta izin sekali
  (lokasi asli, tidak dipalsukan).
- Login otomatis bisa dimatikan, dan data login bisa dihapus kapan saja di Pengaturan → SiAdin web.
- Jika login otomatis gagal 2 kali (password berubah, ada captcha, atau tampilan login kampus
  berubah), browser mini berhenti dan menampilkan pesan; login manual seperti biasa.

**Getar hanya saat presensi sudah dibuka (mode pintar, aktif otomatis):** bila memakai SiAdin web
dan login tersimpan, mulai jam absen NgiBsen mengecek halaman Presensi Online **diam-diam** di latar
belakang. Hemat kuota: gambar tidak diunduh, dan di **Wi-Fi** dicek tiap 1 menit sedangkan di **data
seluler** tiap 2 menit (dipercepat jadi 1 menit menjelang jam tutup). Perkiraan pemakaian data hari
ini tampil di *Pengaturan → SiAdin web*. Selama masih "Belum Ada Presensi Hari Ini!", notifikasinya
**senyap** ("Menunggu presensi…"). NgiBsen membaca kartu **Presensi Kuliah Online** milik matkul tersebut (dicocokkan dari
nama matkul di jadwal):

| Di SiAdin | NgiBsen |
|---|---|
| "Belum Ada Presensi Hari Ini!" / tombol **"Belum Jadwalnya"** | senyap, "Menunggu presensi…" |
| tombol biru **"Presensi Sekarang"** (dibuka dosen) | ±1 menit kemudian **bergetar** "✅ Presensi sudah dibuka" |
| kotak hijau **"Berhasil Presensi"** | absen dicatat selesai, bukti dikirim ke Telegram, pengingat berhenti |

**Layar penuh anti-lupa (aktif otomatis):** tepat saat kartu matkul berubah menjadi **"Presensi
Sekarang"** (baik kartu yang tadinya "Belum Jadwalnya" maupun kartu yang baru dibuat dan langsung
dibuka), layar HP menyala penuh seperti alarm, walau terkunci. **Begitu kunci HP dibuka** (sidik
jari/PIN/wajah), halaman presensi **langsung terbuka** di browser mini tanpa tap tambahan; bila HP
memang sedang tidak terkunci, halaman presensi langsung terbuka. Tombol besar **"Presensi sekarang"**
tetap ada sebagai cadangan bila buka kunci dibatalkan. Kartu yang masih "Belum Jadwalnya" tidak memicunya. Android
14+ butuh izin **Layar penuh** sekali (Pengaturan → Izin). Bisa dimatikan di Pengaturan → SiAdin web.

Di browser mini, tombol "Presensi Sekarang" disorot kuning. Setelah kamu menekannya, SiAdin menampilkan
konfirmasi **Tidak / Ya**; bukti (screenshot kotak hijau "Berhasil Presensi") baru dikirim **setelah
kamu menekan "Ya"** dan SiAdin menampilkan "Berhasil Presensi". Menekan "Tidak" tidak mengirim apa pun.
**Nama matkul di jadwal:** paling pasti tambahkan **kode kelas (KLPK)** di belakang nama, mis. "MPTI 4515"
untuk kartu *KLPK: A11.4515*. Karena satu KLPK bisa dipakai beberapa matkul (mis. 4502 untuk
Technopreneurship, Penambangan Data, Kriptografi), kartu dipilih berurutan: kode **dan** nama cocok → nama
saja → kode saja bila hanya satu kartu berkode itu. Nama lengkap ("Pemrograman Sisi Klien") atau
singkatannya ("PSK", "MPTI") dikenali. Nama berkode tidak pernah memakai kartu matkul lain,
jadi presensi matkul lain yang sedang dibuka tidak dikira presensi matkul ini. Jika pengecekan gagal 3 kali berturut-turut (offline,
login bermasalah), HP bergetar dengan notifikasi **"Cek presensi"**. Jika sampai jam tutup dosen tidak
membuka presensi, riwayat mencatat **"tidak dibuka"** tanpa pesan ❌ ke Telegram.
**Isi jam tutup di jadwal sampai akhir kuliah.** Karena dosen sering membuka presensi menjelang
akhir kuliah, pengecekan diteruskan sampai **15 menit setelah jam tutup** (atau 45 menit setelah
dibuka bila jam tutup kosong). Saklarnya ada di *Pengaturan → SiAdin web*.

**Bantuan presensi di browser mini** (tombol presensi tetap kamu yang tekan):

1. **Menunggu sesi dibuka:** selama halaman menampilkan "Belum Ada Presensi Hari Ini!", halaman
   dimuat ulang otomatis tiap ±20 detik (maks. 90 menit). Begitu berubah, HP bergetar dengan
   notifikasi **"Presensi sudah dibuka!"**.
2. **Tombol disorot:** tombol presensi matkul ini diberi bingkai kuning, diperbesar, dan digulir ke
   tengah layar (sekali saja, jadi tidak mengganggu saat kamu menggulir); kartu matkul lain diredupkan
   supaya tidak salah pencet.
3. **Bukti otomatis:** setelah **kamu** menekan "Presensi Sekarang" lalu **"Ya"**, begitu SiAdin
   menampilkan "Berhasil Presensi", screenshot halaman dikirim ke Telegram, absen ditandai selesai,
   pengingat berhenti, dan browser tertutup. Menekan "Tidak" tidak mengirim apa pun.

Tombol dikenali dari tulisan asli SiAdin ("Belum Jadwalnya", "Presensi Sekarang", "Berhasil Presensi"). Jika tidak tersorot,
tekan tombolnya seperti biasa lalu pakai **📷 Kirim screenshot**. Aplikasi **tidak pernah menekan
tombol presensi** — itu sengaja tidak dibuat, karena presensi adalah pernyataan kehadiranmu sendiri.

**Atau aplikasi Dinusverse:** aplikasi yang dibuka oleh tombol **Absen sekarang**. Jika Dinusverse/SiAdin terpasang, aplikasi
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

Token disimpan **terenkripsi** di HP kamu saja.

> Jika bot kamu pernah dipasangi webhook, `getUpdates` tidak berfungsi. Hapus dulu dengan membuka
> `https://api.telegram.org/bot<TOKEN>/deleteWebhook` di browser.

---

## Mengisi jadwal

**Paling cepat — Impor dari SiAdin (KRS):** tab **Jadwal** → menu **⋮** → **Impor dari SiAdin (KRS)**. NgiBsen
membuka halaman *Akademik → KRS* dengan login otomatis yang sama, membaca setiap kartu matkul (nama, KLPK,
hari, jam, ruang), lalu menampilkan pratinjau: **Tambah yang baru** (yang sudah ada dilewati — termasuk nama
buatan sendiri seperti "MPTI 4515" selama hari, jam, dan kode kelasnya sama) atau **Ganti semua** (untuk
semester baru; riwayat tetap tersimpan). Nama jadwal otomatis = nama matkul + kode kelas, mis.
"Sistem Terdistribusi 4512". Hanya membaca KRS — tidak mengubah apa pun di SiAdin.

Manual: tab **Jadwal** → **Tambah**. Isi nama matkul, hari, jam absen dibuka, jam ditutup (opsional),
ruang (opsional). Jadwal berulang tiap minggu dan dikelompokkan per hari.

- **Simpan & tambah lagi**: simpan lalu langsung buka isian baru (hari sama, jam lanjut dari jam
  tutup sebelumnya) — semua matkul bisa diisi sekali duduk.
- Menu ⋮ tiap matkul: **Kelas pengganti…**, **Duplikat**, **Libur hari ini**, **Lewati minggu ini**,
  **Batalkan libur**, **Hapus**.
- Saklar di kanan: aktif/nonaktif.

**Bagikan / impor jadwal:** menu ⋮ di kanan atas tab Jadwal → **Bagikan jadwal** (kirim teks ke
teman lewat WhatsApp/Telegram, sekalian jadi cadangan) dan **Impor jadwal** (tempel teks → pratinjau
jumlah matkul baru → Impor). Matkul yang sudah ada (nama + hari + jam sama) dilewati, jadi aman
diimpor berulang. Teman sekelas cukup impor satu kali, tidak perlu mengetik ulang.

## Kelas pengganti (jadwal dipindah dosen)

Menu **⋮** pada matkul → **Kelas pengganti…** → pilih tanggal, jam buka/tutup, dan ruang. Kelas ini hanya
terjadi **sekali** pada tanggal itu, dengan nama matkul yang sama (kartu presensi SiAdin tetap dikenali).
Pengingat, pengecekan SiAdin, layar Hari ini, widget, dan riwayat berjalan seperti biasa. Bila kelas biasa
minggu itu ikut dipindah, centang **Liburkan jadwal biasa …** (bisa dibatalkan lewat **Batalkan libur**).
Kelas pengganti tampil di bagian tersendiri di atas daftar jadwal, dan yang sudah lewat hilang sendiri
setelah 7 hari (riwayatnya tetap). Batal: ⋮ → **Batalkan kelas ini**.

## Libur massal (UTS/UAS/libur semester)

Tab **Jadwal** → menu **⋮** → **Liburkan semua sampai…** → pilih tanggal terakhir libur. Semua
pengingat berhenti sampai tanggal itu (di atas daftar tampil "🏖 Semua jadwal libur s/d …"), lalu
otomatis aktif lagi. Mau batal lebih cepat: **⋮ → Aktifkan semua lagi**.

## Ringkasan mingguan

Tiap **Minggu jam 19.00** Telegram menerima ringkasan minggu itu: berapa presensi berhasil,
terlewat (beserta nama matkulnya), libur, dan tidak dibuka dosen. Matikan/coba di
*Pengaturan → Telegram* (**Ringkasan mingguan**, **Kirim ringkasan sekarang**).

## Kalau login SiAdin gagal

Bila NIM/password ditolak SiAdin (mis. password baru diganti), muncul notifikasi **"Login SiAdin
gagal"** sekali sehari. Tap → Pengaturan → SiAdin web, simpan login yang baru. Selama itu pengingat
tetap jalan (notifikasi "Cek presensi"), jadi tidak terlewat.

**Bukti foto otomatis:** bila kamu presensi lewat Chrome/Dinusverse, pengecek melihat kartu hijau
"Berhasil Presensi", memotretnya, dan mengirimkannya ke Telegram sebagai foto (bila gagal: bukti teks).

**Pengaturan:** paling atas ada ringkasan **"✅ Semua siap"** atau daftar yang perlu dibereskan dengan tombol
**Perbaiki**; bagian teknis (diagnosis, aplikasi tujuan) dilipat di **Lanjutan**.

**Cek kesiapan otomatis:** ±30 menit sebelum kuliah pertama tiap hari, NgiBsen diam-diam mengetes izin
penting (notifikasi, alarm tepat, baterai, layar penuh) dan pengecekan SiAdin untuk matkul berikutnya.
Notifikasi **"⚠️ NgiBsen belum siap"** hanya muncul bila ada masalah — jadi bisa dibereskan sebelum kelas.

**Alarm terlewat:** bila alarm jam buka tidak pernah berbunyi (HP mati, aplikasi baru diperbarui, atau NgiBsen
ditahan sistem), NgiBsen memberi tahu saat dibuka lagi / HP menyala, lengkap dengan izin yang perlu diaktifkan.

**Cek berjalan & tepat waktu?** Di *Pengaturan → Diagnosis*:
- **Tes cek sekarang** — menjalankan pengecek SiAdin yang sama persis dengan saat kuliah (login otomatis,
  buka halaman presensi, baca kartu) dengan dua cara — **layar virtual** (dipakai saat kuliah: halaman
  digambar seperti di browser walau tidak tampil di layar) dan **cara lama** — lalu menampilkan hasil
  keduanya, mis. "✅ terbaca: presensi BELUM dibuka". Coba sebelum kuliah; bila hasilnya ⚠️/❌, ada yang
  perlu dibereskan dulu.
- **Log diagnosis** — catatan otomatis: kapan alarm berbunyi, cek dimulai (dan telat berapa detik), apa
  yang terbaca di SiAdin (kartu mana, status apa, sudah login atau belum), dan notifikasi apa yang tampil.
  Bila ada yang meleset di kelas, tekan **Bagikan** — tidak perlu screenshot. Log tidak berisi NIM,
  password, atau token.

Pengecekan berjalan sebagai notifikasi singkat **"Mengecek presensi SiAdin…"** yang dimulai tepat saat
alarm berbunyi. Bila sesi SiAdin habis (halaman presensi tampil kosong tanpa form login), pengecek login
ulang sendiri lewat halaman depan; bila masih gagal, sesi lama dihapus lalu login dari awal.

## Widget

Tambahkan widget **NgiBsen** di layar utama (bisa diubah ukurannya): matkul hari ini atau berikutnya,
status presensi (🕒 dibuka jam berapa · ⏳ menunggu · 🔵 DIBUKA — tap! · ✅ sudah presensi), dan **hitung mundur
langsung** ke jam buka (berjalan sendiri, < 24 jam). Tap widget = buka halaman presensi matkul itu.

## Riwayat

Paling atas: **kehadiran** keseluruhan dan per matkul (persentase hadir, mis. "90% · 9/10", dengan bilah
warna: hijau ≥75%, kuning ≥50%, merah di bawahnya). Libur dan "tidak dibuka dosen" tidak dihitung;
bukti yang gagal terkirim tetap dihitung hadir. Tap nama matkul atau chip filter untuk melihat riwayat
matkul itu saja. Tiap baris punya ikon status (✅ terkirim · 📤 antre · ⚠️ gagal · ❌ terlewat · 🏖 libur ·
⏸ tidak dibuka); yang gagal bisa **Kirim ulang**.

**Sisa jatah tidak hadir** (per matkul, di bawah persentase): mis. "Jatah tidak hadir: sisa 2 dari 3". Bawaan
**14 pertemuan** dan **minimal hadir 75%** → wajib hadir 11, boleh tidak hadir paling banyak **3×**. Atur
sesuai aturan kampus di *Pengaturan → Kehadiran* (jumlah pertemuan, minimal %, dan **awal semester** supaya
riwayat semester lalu tidak ikut dihitung). Hanya "terlewat" yang mengurangi jatah. Begitu jatah tinggal 1
(atau habis), muncul notifikasi + pesan Telegram. Angka ini dari catatan NgiBsen; tetap cek angka resmi
di SiAdin.

**Kehadiran resmi SiAdin** (3.2): persentase di kartu KRS & Presensi Online SiAdin = **jumlah hadir resmi ÷ 14
pertemuan** (mis. 28.57% = hadir 4, 21.43% = hadir 3). NgiBsen membacanya otomatis di setiap pengecekan kuliah
(tanpa kuota tambahan), dari **Impor KRS**, dari **⋮ → Sinkronkan kehadiran resmi (SiAdin)**, dan seminggu sekali
bersama ringkasan Minggu malam. Per matkul tampil:
- **"SiAdin: 28.57% · hadir 4/14 · butuh 7 lagi (min. 11)"** — angka pasti dari kampus;
- **"Perkiraan: 4 pertemuan berlangsung · tidak hadir 0 · sisa jatah 3"** — bila *awal semester* diatur di
  *Pengaturan → Kehadiran*. SiAdin tidak menampilkan jumlah pertemuan yang sudah berlangsung, jadi angka ini
  ditaksir dari jadwal sejak awal semester (dikurangi libur & "tidak dibuka dosen"). Atur awal semester ke hari
  pertama kuliah minggu pertama supaya tepat.
- Peringatan (notifikasi + Telegram) bila perkiraan jatah tinggal 1/habis, atau minimal tidak mungkin tercapai lagi.

**Presensi benar-benar tercatat?** Setiap kali presensi selesai, NgiBsen membandingkan persentase resmi sebelum dan
sesudahnya: harus naik 1/14. Bila belum naik, dicek ulang ±10 menit, 1 jam, dan 3 jam kemudian (lewat KRS); bila
tetap tidak naik → peringatan **"Presensi … belum tercatat di SiAdin?"** supaya bisa segera lapor dosen.

**Grafik kehadiran per minggu** (8 minggu terakhir): kolom bertumpuk ✅ hadir (biru) + ❌ terlewat (oranye).
Warnanya dipilih supaya tetap terbedakan bagi pengguna buta warna. Tap kolom untuk rinciannya.

**Ekspor** (menu **⋮** di tab Riwayat): **PDF** (siap cetak/kirim: ringkasan per matkul + tabel lengkap
tanggal, matkul, status, waktu presensi) atau **CSV** untuk Excel/Google Sheets (pemisah titik koma,
cocok untuk Excel berbahasa Indonesia).

## Pintasan cepat

- **Tile Quick Settings "Presensi"**: tarik panel notifikasi → edit (✏️) → seret tile **Presensi** ke atas.
  Sekali tap membuka halaman presensi; tile menyala dan bertuliskan **DIBUKA** saat dosen sudah membuka
  presensi matkul yang sedang berjalan.
- **Tekan lama ikon NgiBsen**: **Buka presensi** dan **Riwayat**.

## Cadangan & pindah HP

*Pengaturan → Cadangan data* → **Cadangkan**: simpan jadwal (termasuk kelas pengganti), riwayat, dan
pengaturan ke satu file `.json` (pilih lokasinya, mis. Google Drive). Di HP baru: instal NgiBsen → di
langkah pertama wizard tekan **Pulihkan dari cadangan** (atau *Pengaturan → Cadangan data → Pulihkan*).
Semua jadwal & riwayat di HP itu **diganti** isi cadangan, lalu alarm dipasang ulang.

Yang **tidak** ikut (sengaja): NIM/password SiAdin, bot token Telegram (terenkripsi dengan kunci yang terkunci
di HP lama), dan foto bukti. Isi ulang login SiAdin dan sambungkan bot Telegram lagi setelah memulihkan.

## Peringatan tampilan SiAdin berubah

Bila halaman presensi SiAdin **termuat tapi isinya tidak dikenali** berkali-kali berturut-turut (minimal 4
pengecekan, di 2 matkul atau 2 hari berbeda), NgiBsen mengirim notifikasi + pesan Telegram **"⚠️ Tampilan
SiAdin sepertinya berubah"** (maks. sekali sehari). Internet putus atau login ditolak tidak dihitung. Saat itu:
cek SiAdin sendiri ketika kuliah, lalu kirim **log diagnosis** supaya pengecek bisa diperbaiki.

## Versi baru & keaslian APK

*Pengaturan → Tentang & versi*: versi terpasang dan **sidik jari SHA-256 sertifikat** APK. NgiBsen mengecek
`versi.json` di Release sehari sekali (±300 byte) dan memberi notifikasi **"⬆️ Versi baru NgiBsen …"**.
**Cek sekarang** juga membandingkan sidik jari APK di HP dengan rilis resmi: ✅ sama = APK asli dari Release
repo ini.

**Pembaruan sekali tap (3.3):** tap notifikasi **Perbarui** atau tombol **Perbarui sekarang** di *Tentang & versi*.
NgiBsen mengunduh `NgiBsen-UDINUS.apk` dari Release repo ini, lalu **memeriksanya sebelum dipasang**:
- isi file sama persis dengan rilis resmi (SHA-256 file di `versi.json`),
- nama paket `com.pengingatabsen`,
- **sertifikat sama** dengan aplikasi yang terpasang (dan dengan `versi.json`),
- versinya lebih baru (tidak bisa turun versi).

Gagal satu saja → file dibuang dan tidak dipasang. Yang lolos diserahkan ke pemasang Android: di **Android 12+**
aplikasi yang memperbarui dirinya sendiri biasanya terpasang **tanpa dialog**; di Android 8–11 (atau bila sistem
tetap meminta) muncul satu dialog **Perbarui**/**Instal** dari Android — pada pembaruan pertama bisa diminta
mengizinkan *Instal aplikasi tak dikenal* untuk NgiBsen (sekali saja). NgiBsen tertutup sebentar, lalu muncul
notifikasi **"✅ NgiBsen diperbarui ke …"**; data jadwal, riwayat, login, dan pengaturan tetap. Semua alarm
dipasang ulang otomatis.

- **Unduh lebih dulu lewat Wi-Fi** (bawaan aktif): versi baru diunduh & diperiksa di latar saat tersambung Wi-Fi,
  notifikasinya menjadi **"siap dipasang"**. Memasang **tetap menunggu tap-mu** — tidak pernah otomatis.
- Gagal/ragu? Tombol **Halaman unduhan** tetap ada untuk memasang manual seperti biasa.
- Pembaruan dari **3.2 ke 3.3** masih manual (3.2 belum punya fitur ini); setelah itu tinggal sekali tap.

Sidik jari rilis resmi:

```
6db7 e5f4 8092 aa56 d8ba 1877 4472 5b93 70aa 46de 8514 29c4 06f9 3bcb 4e24 4aab
```

## Bagikan ke teman

Boleh. Setiap orang memakai datanya sendiri — tidak ada yang tercampur:

1. Kirim link **[Releases → terbaru](https://github.com/tehgeii/sistem-bot-otomatis/releases/latest)** atau file
   **NgiBsen-UDINUS.apk** ke teman (lewat WhatsApp/Telegram/Drive).
2. Teman menginstal APK, lalu menjalankan wizard dengan **NIM, password, jadwal, dan bot Telegram
   milik mereka sendiri** (setiap orang membuat bot sendiri di @BotFather).
3. Semua data (jadwal, riwayat, token bot, NIM & password) tersimpan di HP masing-masing saja.

Yang perlu diketahui teman:
- APK ini di luar Play Store: Android/Play Protect akan memberi peringatan
  "aplikasi tidak dikenal" → pilih **Tetap instal**.
- Unduh hanya dari Releases repo ini.
- Tetap tekan tombol presensi sendiri; aplikasi tidak pernah absen otomatis.

## Login ulang Dinusverse cukup satu tap (Autofill Google Password Manager)

Jika memakai SiAdin web, login sudah otomatis (lihat Langkah 2). Untuk aplikasi Dinusverse,
supaya login ulang cepat, pakai fitur Autofill bawaan Android:

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
- **targetSdk/compileSdk 36** (Android 16).
- **Aturan awal "jangan simpan/isi NIM/password" dicabut oleh pemilik** demi login otomatis SiAdin
  web. Pengisian hanya ke form login asli: tepat satu kolom password yang tampil di layar, kolom
  NIM (nama/id/placeholder berisi `nim`, `user`, `login`, `email`, `induk`), dan tombol
  **Masuk/Login** — form tersembunyi seperti di menu dashboard diabaikan. Maksimal 2 percobaan per
  pembukaan; aplikasi tidak pernah menekan tombol presensi.
- **Browser mini** tampil sebagai Chrome biasa (penanda WebView dihapus dari user agent) supaya
  website memperlakukannya sama. Cookie sesi disimpan selama website mengizinkan.

---

## Teknologi & struktur

Kotlin · Jetpack Compose · Material 3 · Room · DataStore · AlarmManager · WorkManager · OkHttp.
Satu modul `app`, MVVM + repository sederhana (service locator `Graph`).

```
app/src/main/java/com/pengingatabsen/
├── App.kt                  Application + Graph (service locator)
├── data/                   Room (Course, AttendanceRecord), DataStore, enkripsi token, Repository
├── logic/                  Logika murni & teruji: ScheduleMath (alarm, kelas pengganti), CheckerBrain
│                           (otak pengecek SiAdin), TodayPlan, Readiness, Allowance (jatah tidak hadir),
│                           BackupCodec, HistoryExport, LayoutWatch, AppUpdate, Formatters
├── alarm/                  AlarmScheduler, receiver alarm/aksi/boot, notifikasi, izin
├── launch/                 Buka target (LaunchTargetActivity), browser mini SiAdin + login otomatis
│                           (WebBrowserActivity), share target screenshot
├── telegram/               Bot API client (OkHttp) + SendWorker (WorkManager, retry offline)
├── widget/                 Widget layar utama (+ TodayData, sumber bersama widget & tile)
├── tile/                   Tile Quick Settings "Presensi"
├── update/                 Versi baru (versi.json) + pembaruan sekali tap (unduh, periksa, PackageInstaller)
└── ui/                     Compose: jadwal, riwayat (grafik, ekspor), pengaturan, wizard, cadangan
app/src/test/…/logic/       Unit test semua logika murni
```

## Build sendiri

Butuh JDK 17 dan Android SDK.

```bash
./gradlew testDebugUnitTest   # unit test
./gradlew assembleDebug       # APK debug untuk uji coba sendiri
```

GitHub Actions menjalankan unit test, membangun APK, dan memperbarui Release **terbaru** setiap
ada perubahan di `main` (berisi `NgiBsen-UDINUS.apk` + `versi.json`: versi, sidik jari sertifikat, dan SHA-256
file APK untuk pembaruan sekali tap).
Log build juga mencetak **sidik jari SHA-256 sertifikat**; angka ini harus selalu sama di setiap versi.

**Bersihkan riwayat build lama:** tab **Actions → Bersihkan riwayat build lama → Run workflow**, ketik
`HAPUS`, lalu **Run workflow**. Semua riwayat build dihapus permanen kecuali build commit terbaru di `main`.
Kode, commit, dan Release tidak tersentuh.

## Untuk pemilik repo: kunci tanda tangan & aturan Google

**Cadangkan kunci tanda tangan (WAJIB).** APK ditandatangani dengan keystore yang tersimpan di GitHub Secrets
(`SIGNING_KEYSTORE_BASE64` + 3 password). Simpan **juga** salinan file `.jks` dan ketiga passwordnya di tempat
aman di luar GitHub (mis. pengelola password, atau Drive pribadi yang terkunci). Tanpa kunci yang sama, versi
baru tidak bisa dipasang menimpa yang lama (harus uninstall → data hilang), dan paket tidak bisa didaftarkan
ke Google. Kunci ini **tidak boleh** di-commit ke repo. Cocokkan dengan sidik jari di atas: sidik jarinya
harus selalu sama di setiap rilis.

**Verifikasi developer Google** (berlaku di Indonesia sejak 30 Sep 2026):
- **Sekarang:** aturan ini hanya untuk aplikasi dari toko resmi (Play Store, Galaxy Store, dst.). APK yang dipasang
  langsung dari Release repo ini **belum terkena**: pasang & update tetap seperti biasa.
- **Mulai 2027 (tahap global):** memasang/memperbarui aplikasi yang paketnya belum terdaftar hanya bisa lewat
  **advanced flow** (pengaturan sekali di HP: mode developer, konfirmasi, tunggu 24 jam, lalu izinkan aplikasi
  tak terverifikasi) atau **ADB**. Bila advanced flow dimatikan, update aplikasi tak terdaftar akan gagal.
- **Persiapan (gratis):** akun **limited distribution** di Android Developer Console (Google Account dengan
  verifikasi 2 langkah + profil pembayaran Google; tanpa KTP & tanpa biaya), lalu daftarkan paket
  `com.pengingatabsen` dengan kunci tanda tangan di atas. Bisa dibagikan ke maksimal 20 perangkat. Kunci
  hilang = paket tidak bisa didaftarkan.

Sumber: [FAQ verifikasi developer](https://developer.android.com/developer-verification/guides/faq) ·
[Limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution).

## Daftar uji setelah update

Setelah memasang versi baru, cek sekali:
1. *Pengaturan → Diagnosis → Tes cek sekarang* → ✅ terbaca.
2. *Pengaturan → Tentang & versi → Cek sekarang* → "Sudah versi terbaru" dan ✅ sidik jari sama.
   Bila memperbarui lewat **Perbarui sekarang**, notifikasi **"✅ NgiBsen diperbarui ke …"** muncul setelahnya.
3. *Pengaturan → Cadangan data → Cadangkan* → simpan file (sekalian cadangan pertama).
4. Layar **Hari ini** & widget menampilkan matkul yang benar; tile **Presensi** bisa dipasang.
5. Selama seminggu pertama tetap lirik SiAdin sendiri; bila ada yang meleset, kirim **log diagnosis**.

## Privasi

- Bot token Telegram hanya tersimpan di HP kamu, terenkripsi.
- NIM & password SiAdin **hanya** disimpan jika kamu mengisinya (untuk login otomatis), terenkripsi
  di HP kamu, dan hanya diisikan ke halaman login SiAdin. Bisa dihapus kapan saja
  (Pengaturan → SiAdin web → Hapus data login).
- Data jadwal & riwayat hanya di perangkat; yang keluar hanya pesan bukti ke bot Telegram milikmu.
- File cadangan & ekspor dibuat hanya saat kamu memintanya, di lokasi yang kamu pilih; isinya jadwal, riwayat,
  dan pengaturan (tanpa NIM, password, token, atau foto).
- Pemberitahuan versi baru hanya membaca `versi.json` publik di Release repo (tanpa data apa pun dari HP);
  pembaruan sekali tap hanya mengunduh APK dari Release repo yang sama dan tidak mengirim apa pun.

## Kontributor

- [@tehgeii](https://github.com/tehgeii) — ide, kebutuhan, dan arah desain aplikasi
