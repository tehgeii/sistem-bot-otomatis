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
        StatusRow(batteryOk, "Tanpa optimasi baterai", "Supaya alarm tidak ditahan sistem", "Izinkan") {
            runCatching { context.startActivity(Permissions.batteryIntent(context)) }
                .onFailure { context.startActivity(Permissions.appDetailsIntent(context)) }
        }
        TextButton(onClick = { Notifications.showTest(context) }) { Text("Tes notifikasi") }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Ulangi pengingat tiap $interval menit", modifier = Modifier.weight(1f))
        OutlinedButton(enabled = interval > 1, onClick = { vm.setInterval(interval - 1, context) }) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = interval < 30, onClick = { vm.setInterval(interval + 1, context) }) { Text("+") }
    }
}
