package com.pengingatabsen.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pengingatabsen.ui.theme.PengingatTheme
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Layar (dialog) pembaruan sekali tap: dibuka dari tombol "Perbarui sekarang" atau notifikasi versi baru.
 * Menutup dialog tidak membatalkan unduhan; langkah yang butuh kamu nanti muncul sebagai notifikasi.
 */
class UpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) maybeStart(intent)

        // Android meminta konfirmasi → buka dialognya saat layar ini tampil (tidak dari latar).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                SelfUpdater.state.collect { s -> if (s is UpdateState.AwaitingConfirm) launchConfirm(s) }
            }
        }

        setContent {
            PengingatTheme {
                val state by SelfUpdater.state.collectAsState()
                UpdateDialog(
                    state = state,
                    onClose = { finish() },
                    onCancel = {
                        SelfUpdater.cancel()
                        finish()
                    },
                    onRetry = { SelfUpdater.start(this) },
                    onOpenPage = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE)))
                        finish()
                    },
                    onOpenPermission = {
                        open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeStart(intent)
    }

    override fun onStart() {
        super.onStart()
        SelfUpdater.uiVisible = true
    }

    override fun onStop() {
        super.onStop()
        SelfUpdater.uiVisible = false
    }

    /** Tap "Perbarui" = mulai; dari notifikasi "lanjutkan" cukup tampilkan tahap yang sedang berjalan. */
    private fun maybeStart(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_START, true) || SelfUpdater.state.value is UpdateState.Idle) SelfUpdater.start(this)
    }

    private fun launchConfirm(s: UpdateState.AwaitingConfirm) {
        SelfUpdater.confirmLaunched(s.versionName)
        try {
            startActivity(s.confirm)
        } catch (e: ActivityNotFoundException) {
            SelfUpdater.confirmUnavailable(this)
        } catch (e: SecurityException) {
            SelfUpdater.confirmUnavailable(this)
        }
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        private const val EXTRA_START = "mulai"

        /** [start] = mulai/ulangi pembaruan; false = hanya tampilkan tahap sekarang (mis. dari notifikasi lanjutan). */
        fun intent(context: Context, start: Boolean): Intent =
            Intent(context, UpdateActivity::class.java).putExtra(EXTRA_START, start).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun UpdateDialog(
    state: UpdateState,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenPage: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val small = MaterialTheme.typography.bodySmall
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                when (state) {
                    is UpdateState.Failed -> "Pembaruan belum berhasil"
                    is UpdateState.UpToDate -> "Sudah terbaru"
                    else -> "Pembaruan NgiBsen"
                },
            )
        },
        text = {
            Column {
                when (state) {
                    UpdateState.Idle, UpdateState.Checking -> {
                        Text("Mengecek versi terbaru di Release GitHub…")
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    is UpdateState.UpToDate -> Text("NgiBsen ${state.versionName} sudah versi terbaru. Tidak ada yang perlu diperbarui.")
                    is UpdateState.Downloading -> {
                        val size = if (state.total > 0) "${mb(state.done)} / ${mb(state.total)} MB" else "${mb(state.done)} MB"
                        Text("Mengunduh NgiBsen ${state.versionName}… $size")
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        }
                        Text(
                            "Jadwal, riwayat, login, dan pengaturan tetap aman — tidak ada yang dihapus.",
                            style = small,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is UpdateState.Verifying -> {
                        Text("Memeriksa keaslian APK ${state.versionName}: sertifikat harus sama dengan aplikasi terpasang & isi file sama dengan rilis resmi…")
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    is UpdateState.Installing -> {
                        Text("Memasang NgiBsen ${state.versionName}…")
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        Text(
                            "NgiBsen akan tertutup sendiri sebentar. Setelah selesai muncul notifikasi \"✅ NgiBsen diperbarui\" " +
                                "— ketuk untuk membukanya lagi. Bila Android menampilkan dialog, pilih \"Perbarui\"/\"Instal\".",
                            style = small,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is UpdateState.AwaitingConfirm -> Text("Menunggu konfirmasi dari Android…")
                    is UpdateState.Failed -> {
                        Text(state.message)
                        if (state.suggestPermission) {
                            TextButton(onClick = onOpenPermission) { Text("Izinkan NgiBsen memasang pembaruan") }
                        }
                        TextButton(onClick = onOpenPage) { Text("Halaman unduhan (pasang manual)") }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Failed -> TextButton(onClick = onRetry) { Text("Coba lagi") }
                UpdateState.Idle, UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Verifying ->
                    TextButton(onClick = onClose) { Text("Sembunyikan") }
                else -> TextButton(onClick = onClose) { Text("Tutup") }
            }
        },
        dismissButton = {
            when (state) {
                UpdateState.Idle, UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Verifying ->
                    TextButton(onClick = onCancel) { Text("Batal") }
                is UpdateState.Failed -> TextButton(onClick = onClose) { Text("Tutup") }
                else -> Unit
            }
        },
    )
}

private fun mb(bytes: Long): String = String.format(Locale.forLanguageTag("id"), "%.1f", bytes / 1_048_576.0)
