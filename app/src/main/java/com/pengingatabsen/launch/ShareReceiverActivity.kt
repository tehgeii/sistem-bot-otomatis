package com.pengingatabsen.launch

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.pengingatabsen.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/**
 * Share target gambar: screenshot halaman sukses Dinusverse dibagikan ke sini,
 * dikaitkan ke matkul yang sedang aktif, lalu dikirim via sendPhoto.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = imageUri(intent)
        if (uri == null) {
            Toast.makeText(this, "Tidak ada gambar untuk dikirim", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Waktu bukti = saat dibagikan.
        val sharedAt = LocalDateTime.now()
        val context = applicationContext
        // Salin selagi activity hidup: izin baca URI dicabut setelah activity selesai.
        val file = try {
            copyToInternal(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan gambar: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        scope.launch {
            val record = Graph.repository.attachPhoto(file.absolutePath, sharedAt)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Bukti ${record.courseName} diantrekan ke Telegram", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private fun copyToInternal(uri: Uri): File {
        val dir = File(filesDir, "bukti").apply { mkdirs() }
        val file = File(dir, "bukti_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "gambar tidak bisa dibaca" }
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun imageUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
