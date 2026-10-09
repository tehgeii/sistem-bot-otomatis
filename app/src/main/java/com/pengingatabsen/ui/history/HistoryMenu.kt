package com.pengingatabsen.ui.history

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pengingatabsen.logic.HistoryExport
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Menu tab Riwayat (TopAppBar): ekspor ke CSV (Excel/Sheets) atau PDF. */
@Composable
fun HistoryMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    fun doExport(uri: android.net.Uri, pdf: Boolean) {
        scope.launch {
            val msg = runCatching { HistoryExporter.export(context.applicationContext, uri, pdf) }
                .fold(
                    onSuccess = { n -> "$n catatan riwayat diekspor ke ${if (pdf) "PDF" else "CSV"}." },
                    onFailure = { "Ekspor gagal (${it.message ?: it.javaClass.simpleName})." },
                )
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { doExport(it, pdf = false) }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { doExport(it, pdf = true) }
    }

    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu riwayat") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Ekspor PDF (siap cetak/kirim)") },
                onClick = { menu = false; pdf.launch(HistoryExport.fileName(LocalDate.now(), "pdf")) },
            )
            DropdownMenuItem(
                text = { Text("Ekspor CSV (Excel/Sheets)") },
                onClick = { menu = false; csv.launch(HistoryExport.fileName(LocalDate.now(), "csv")) },
            )
        }
    }
}
