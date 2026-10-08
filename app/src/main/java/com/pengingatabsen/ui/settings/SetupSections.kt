package com.pengingatabsen.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.alarm.Permissions
import com.pengingatabsen.launch.InstalledApp
import com.pengingatabsen.launch.TargetApps

/** Angka yang bertambah setiap layar kembali tampil (untuk cek ulang izin). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun StatusRow(ok: Boolean, title: String, subtitle: String, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) OutlinedButton(onClick = onClick) { Text(button) }
    }
}

/** Langkah 1: izin notifikasi, exact alarm, optimasi baterai. */
@Composable
fun PermissionsSection(autoRequest: Boolean = false) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var notifTick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifTick++ }

    val notifOk = remember(tick, notifTick) { Permissions.notificationsGranted(context) }
    val exactOk = remember(tick) { Permissions.exactAlarmGranted(context) }
    val batteryOk = remember(tick) { Permissions.batteryUnrestricted(context) }

    fun requestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(Permissions.notificationSettingsIntent(context))
        }
    }

    // Supaya cukup satu tap: minta izin notifikasi langsung saat langkah ini dibuka.
    LaunchedEffect(autoRequest) { if (autoRequest && !notifOk) requestNotif() }

    Column {
        StatusRow(notifOk, "Notifikasi", "Untuk pengingat absen yang muncul di layar", "Izinkan") { requestNotif() }
        StatusRow(exactOk, "Alarm tepat waktu", "Supaya notifikasi berbunyi tepat di jam absen", "Buka") {
            runCatching { context.startActivity(Permissions.exactAlarmIntent(context)) }
        }
        val fullScreenOk = remember(tick) { Notifications.canUseFullScreen(context) }
        StatusRow(fullScreenOk, "Layar penuh", "Supaya layar menyala saat presensi dibuka dosen", "Izinkan") {
            runCatching { context.startActivity(Permissions.fullScreenIntent(context)) }
                .onFailure { context.startActivity(Permissions.appDetailsIntent(context)) }
        }
        StatusRow(batteryOk, "Tanpa optimasi baterai", "Supaya alarm tidak ditahan sistem", "Izinkan") {
            runCatching { context.startActivity(Permissions.batteryIntent(context)) }
                .onFailure { context.startActivity(Permissions.appDetailsIntent(context)) }
        }
        Text(
            "HP Xiaomi, Oppo/Realme, Vivo/iQOO, Infinix, Samsung: aktifkan juga \"Mulai otomatis/Autostart\" " +
                "untuk NgiBsen, supaya pengingat tetap jalan setelah aplikasi ditutup.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row {
            TextButton(onClick = { Permissions.openAutostart(context) }) { Text("Buka Autostart") }
            TextButton(onClick = { Notifications.showTest(context) }) { Text("Tes notifikasi") }
        }
    }
}

/** Langkah 2: aplikasi tujuan (Dinusverse) + deep link opsional. */
@Composable
fun TargetAppSection(vm: SetupViewModel, showDeepLink: Boolean = true) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.autoPickTarget(context) }

    Column {
        Text(
            settings?.targetLabel?.let { "Aplikasi tujuan: $it" } ?: "Belum memilih aplikasi tujuan",
            style = MaterialTheme.typography.bodyLarge,
        )
        settings?.targetPackage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.padding(4.dp))
        Button(onClick = { picking = true }) { Text(if (settings?.targetPackage == null) "Pilih aplikasi" else "Ganti aplikasi") }

        if (showDeepLink) {
            var link by remember(settings?.deepLink) { mutableStateOf(settings?.deepLink.orEmpty()) }
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; vm.setDeepLink(it) },
                label = { Text("URL deep link (opsional)") },
                supportingText = { Text("Jika diisi, tombol \"Absen sekarang\" membuka URL ini. Kosongkan untuk membuka aplikasi.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    if (picking) {
        val apps = remember { vm.installedApps(context) }
        AppPickerDialog(apps, onDismiss = { picking = false }) { vm.chooseApp(it); picking = false }
    }
}

@Composable
private fun AppPickerDialog(apps: List<InstalledApp>, onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih aplikasi Dinusverse") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 10.dp)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

/** Langkah 3: Telegram — tempel token, kirim /start, chat ID diambil otomatis. */
@Composable
fun TelegramSection(vm: SetupViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    var token by remember { mutableStateOf("") }
    val hasToken = settings?.hasBotToken == true
    val chatId = settings?.chatId

    // Token sudah ada tapi chat belum: langsung tunggu /start tanpa perlu ditekan.
    LaunchedEffect(hasToken, chatId) { if (hasToken && chatId == null) vm.startPolling() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "1. Buat bot lewat @BotFather (/newbot), salin tokennya.\n" +
                "2. Tempel token di bawah, tekan Simpan.\n" +
                "3. Buka bot kamu dan kirim /start — chat ID diambil otomatis.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (hasToken) "Bot token (tersimpan, isi untuk mengganti)" else "Bot token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !vm.busy && token.isNotBlank(), onClick = { vm.saveToken(token); token = "" }) { Text("Simpan") }
            settings?.botUsername?.let { username ->
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Buka @$username") }
            }
        }
        vm.tokenStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (hasToken) {
            if (chatId != null) {
                Text("✅ Chat ID: $chatId", style = MaterialTheme.typography.bodyMedium)
            }
            vm.chatStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !vm.polling, onClick = { vm.startPolling() }) {
                    Text(if (chatId == null) "Cek lagi" else "Ambil ulang chat ID")
                }
                Button(enabled = chatId != null && !vm.busy, onClick = { vm.testSend() }) { Text("Tes kirim") }
            }
            vm.testStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ringkasan mingguan")
                    Text(
                        "Tiap Minggu jam 19.00: jumlah berhasil, terlewat, libur, dan tidak dibuka dosen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings?.weeklySummary ?: true, onCheckedChange = { vm.setWeeklySummary(it, context) })
            }
            TextButton(enabled = chatId != null, onClick = { vm.sendSummaryNow(context) }) { Text("Kirim ringkasan sekarang") }
        }
    }
}

/**
 * SiAdin web: pakai halaman Presensi Online di browser mini + login otomatis.
 * NIM & password disimpan terenkripsi (Android Keystore) dan hanya diisikan ke *.dinus.ac.id via HTTPS.
 */
@Composable
fun SiadinWebSection(vm: SetupViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    var nim by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(settings?.hasSiadinLogin) { vm.loadSavedNim() }
    val usingWeb = settings?.deepLink == TargetApps.SIADIN_PRESENSI_URL

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (usingWeb) {
            Text("✅ \"Absen sekarang\" membuka Presensi Online SiAdin di browser mini.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "Buka halaman Presensi Online SiAdin langsung dari notifikasi, lengkap dengan login otomatis.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { vm.useSiadinWeb() }) { Text("Pakai Presensi Online SiAdin") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Login otomatis")
                Text("Isi NIM & password lalu tekan Login sendiri. Tombol presensi tetap kamu yang tekan.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings?.autoLogin ?: true, onCheckedChange = { vm.setAutoLogin(it) })
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Getar hanya saat presensi sudah dibuka")
                Text(
                    "Selama jam absen, SiAdin dicek otomatis tiap 1 menit. HP baru bergetar setelah dosen membuka " +
                        "presensi. Butuh login tersimpan. Isi jam tutup di jadwal sampai akhir kuliah.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings?.smartPresensi ?: true, onCheckedChange = { vm.setSmartPresensi(it, context) })
        }

        settings?.takeIf { it.smartPresensi }?.let {
            val mb = it.checkBytesToday / 1_000_000.0
            val text = if (it.checkBytesToday < 1_000_000) "${it.checkBytesToday / 1000} KB" else "%.1f MB".format(mb)
            Text(
                "Pengecekan SiAdin hari ini: ≈ $text. Hemat kuota: gambar tidak diunduh, dan di data " +
                    "seluler pengecekan tiap 2 menit (1 menit menjelang jam tutup).",
                style = MaterialTheme.typography.bodySmall,
            )
            // Catatan cek terakhir: untuk memastikan pengecekan benar-benar berjalan & apa yang terbaca.
            it.lastCheck?.let { last -> Text("Cek terakhir: $last", style = MaterialTheme.typography.bodySmall) }
            // Keterlambatan cek dari alarm: angka besar = HP menunda aplikasi (cek izin baterai/Autostart).
            it.lastCheckDelaySec?.let { delay ->
                Text(
                    if (delay <= 30) "Cek terakhir tepat waktu (telat $delay dtk dari alarm)."
                    else "⚠️ Cek terakhir telat $delay dtk dari alarm — aktifkan izin baterai & Autostart di Izin HP.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Layar penuh saat presensi dibuka")
                Text(
                    "Saat tombol berubah jadi \"Presensi Sekarang\", layar menyala seperti alarm (walau terkunci). " +
                        "Kamu tetap yang menekan presensi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings?.fullScreenAlert ?: true, onCheckedChange = { vm.setFullScreenAlert(it) })
        }

        vm.savedNim?.let { Text("Tersimpan: $it", style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(
            value = nim,
            onValueChange = { nim = it },
            label = { Text(if (vm.savedNim == null) "NIM" else "NIM baru") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password SiAdin") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = nim.isNotBlank() && password.isNotEmpty(),
                onClick = { vm.saveSiadinLogin(nim, password); nim = ""; password = "" },
            ) { Text("Simpan login") }
            if (vm.savedNim != null) {
                OutlinedButton(onClick = { vm.clearSiadinLogin() }) { Text("Hapus data login") }
            }
        }
        vm.loginStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Disimpan terenkripsi di HP ini saja dan hanya diisikan ke halaman https://*.dinus.ac.id.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Getar saja (tanpa suara), default aktif supaya aman di kelas. */
@Composable
fun VibrateOnlySection(vm: SetupViewModel) {
    val settings by vm.settings.collectAsState()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Getar saja (tanpa suara)")
            Text(
                "Notifikasi tetap muncul di layar dan HP bergetar, tanpa bunyi.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = settings?.vibrateOnly ?: true, onCheckedChange = { vm.setVibrateOnly(it) })
    }
}

/** Interval pengingat ulang (default 3 menit). */
@Composable
fun IntervalSection(vm: SetupViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val interval = settings?.remindIntervalMinutes ?: 3
    // Mode pintar: sebelum dibuka, SiAdin dicek otomatis (1–2 menit); interval ini hanya berlaku setelah dibuka.
    val label = if (settings?.smartModeActive == true) "Setelah presensi dibuka, ulangi tiap $interval menit"
    else "Ulangi pengingat tiap $interval menit"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(enabled = interval > 1, onClick = { vm.setInterval(interval - 1, context) }) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = interval < 30, onClick = { vm.setInterval(interval + 1, context) }) { Text("+") }
    }
}

/**
 * Diagnosis: "Tes cek sekarang" menjalankan pengecek SiAdin latar (yang sama dengan saat kuliah) dan
 * menampilkan hasilnya; "Log diagnosis" berisi jejak alarm, pengecekan, dan notifikasi (tanpa NIM,
 * password, atau token) yang bisa dibagikan untuk dicek.
 */
@Composable
fun DiagnosisSection(vm: SetupViewModel) {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf<String?>(null) }

    val settings by vm.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cek kesiapan otomatis")
                Text(
                    "±30 menit sebelum kuliah pertama tiap hari, NgiBsen mengetes login & pembacaan SiAdin. " +
                        "Notifikasi hanya muncul bila ada masalah.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = settings?.readinessCheck ?: true, onCheckedChange = { vm.setReadinessCheck(it, context) })
        }
        settings?.readinessText?.let { text ->
            Text(
                (if (settings?.readinessOk == true) "✅ " else "⚠️ ") + "Kesiapan terakhir: $text",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Bisa juga dites manual kapan saja: tekan Tes cek sekarang. Bila ada yang meleset di kelas, tekan " +
                "Bagikan log — tidak perlu screenshot.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !vm.diagBusy, onClick = { vm.testCheckNow(context) }) {
                Text(if (vm.diagBusy) "Mengecek…" else "Tes cek sekarang")
            }
            OutlinedButton(onClick = { showLog = vm.readLog() }) { Text("Log diagnosis") }
        }
        vm.diagResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    showLog?.let { full ->
        val lines = full.lines().filter { it.isNotBlank() }
        val shown = lines.takeLast(200).joinToString("\n").ifBlank { "Log masih kosong." }
        AlertDialog(
            onDismissRequest = { showLog = null },
            title = { Text("Log diagnosis") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(shown, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Log diagnosis NgiBsen")
                        .putExtra(Intent.EXTRA_TEXT, lines.takeLast(600).joinToString("\n"))
                    runCatching {
                        context.startActivity(Intent.createChooser(share, "Bagikan log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Bagikan") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.clearLog(); showLog = "" }) { Text("Hapus") }
                    TextButton(onClick = { showLog = null }) { Text("Tutup") }
                }
            },
        )
    }
}

/**
 * Ringkasan di paling atas Pengaturan: "Semua siap ✅" atau daftar yang belum beres + tombol perbaikannya.
 * Dicek ulang setiap layar kembali tampil (setelah pengguna mengubah izin di pengaturan HP).
 */
@Composable
fun StatusSummary(vm: SetupViewModel) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val settings by vm.settings.collectAsState()
    val s = settings ?: return

    data class Item(val ok: Boolean, val label: String, val fix: (() -> Unit)?)
    val items = remember(tick, s) {
        listOf(
            Item(s.smartModeActive, "Mode pintar SiAdin (login tersimpan)", null),
            Item(
                Permissions.notificationsGranted(context) &&
                    androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
                "Notifikasi",
            ) { runCatching { context.startActivity(Permissions.notificationSettingsIntent(context)) } },
            Item(Permissions.exactAlarmGranted(context), "Alarm tepat waktu") {
                runCatching { context.startActivity(Permissions.exactAlarmIntent(context)) }
            },
            Item(!s.fullScreenAlert || Notifications.canUseFullScreen(context), "Layar penuh saat dibuka") {
                runCatching { context.startActivity(Permissions.fullScreenIntent(context)) }
                    .onFailure { context.startActivity(Permissions.appDetailsIntent(context)) }
            },
            Item(Permissions.batteryUnrestricted(context), "Tanpa optimasi baterai") {
                runCatching { context.startActivity(Permissions.batteryIntent(context)) }
                    .onFailure { context.startActivity(Permissions.appDetailsIntent(context)) }
            },
            Item(s.telegramReady, "Telegram untuk bukti (opsional)", null),
        )
    }
    val problems = items.filter { !it.ok }
    val readinessBad = s.smartModeActive && s.readinessOk == false

    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (problems.isEmpty() && !readinessBad) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (problems.isEmpty() && !readinessBad) "✅ Semua siap" else "⚠️ Ada yang perlu dibereskan",
                style = MaterialTheme.typography.titleMedium,
            )
            problems.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• ${item.label}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    item.fix?.let { fix -> TextButton(onClick = fix) { Text("Perbaiki") } }
                }
            }
            if (problems.any { it.fix == null && !it.ok && it.label.startsWith("Mode pintar") }) {
                Text("Simpan NIM & password di bagian SiAdin web di bawah.", style = MaterialTheme.typography.bodySmall)
            }
            s.readinessText?.takeIf { s.smartModeActive }?.let {
                Text(
                    (if (s.readinessOk == true) "Kesiapan terakhir: " else "⚠️ Kesiapan terakhir: ") + it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !vm.diagBusy, onClick = { vm.testCheckNow(context) }) {
                    Text(if (vm.diagBusy) "Mengecek…" else "Tes cek sekarang")
                }
                TextButton(onClick = { Permissions.openAutostart(context) }) { Text("Autostart") }
            }
            vm.diagResult?.let { Text(it.lineSequence().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
