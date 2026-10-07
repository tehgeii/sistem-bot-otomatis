package com.pengingatabsen.data

import com.pengingatabsen.Graph
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Log diagnosis permanen di HP (file kecil, maks. ±[MAX_LINES] baris terakhir): alarm, pengecekan SiAdin,
 * hasil, dan notifikasi yang ditampilkan. Supaya kegagalan di kelas selalu meninggalkan jejak walau
 * lupa screenshot. TIDAK PERNAH berisi NIM, password, atau token Telegram.
 */
object DiagLog {
    private const val MAX_LINES = 1500
    private const val TRIM_AT_BYTES = 300_000L
    private val lock = Any()
    private val format = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    private fun file() = File(Graph.appContext.filesDir, "diagnosis.log")

    fun add(message: String) {
        runCatching {
            synchronized(lock) {
                val f = file()
                f.appendText("${LocalDateTime.now().format(format)}  ${message.replace('\n', ' ')}\n")
                if (f.length() > TRIM_AT_BYTES) f.writeText(f.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Isi log (paling baru di bawah); kosong bila belum ada. */
    fun read(): String = runCatching {
        synchronized(lock) { file().takeIf { it.exists() }?.readText() }
    }.getOrNull().orEmpty()

    fun clear() {
        runCatching { synchronized(lock) { file().delete() } }
    }
}
