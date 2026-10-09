package com.pengingatabsen.alarm

import android.content.Context
import com.pengingatabsen.Graph
import com.pengingatabsen.data.DiagLog
import com.pengingatabsen.launch.CheckResult
import com.pengingatabsen.launch.PresensiState
import com.pengingatabsen.logic.LayoutWatch
import com.pengingatabsen.logic.SuspectCheck
import com.pengingatabsen.telegram.NoticeWorker

/**
 * Peringatan dini bila tampilan SiAdin berubah: setiap hasil pengecekan dicatat; yang terbaca normal mereset,
 * yang "termuat tapi tidak dikenali" dihitung (aturan di [LayoutWatch]). Peringatan maks. sekali sehari,
 * lewat notifikasi + Telegram. Pengingat presensi tetap berjalan seperti biasa ("Cek presensi").
 */
object SiadinHealth {
    suspend fun record(context: Context, courseId: Long, epochDay: Long, result: CheckResult) {
        val store = Graph.settings
        when {
            result.state == PresensiState.WAITING || result.state == PresensiState.OPEN || result.state == PresensiState.DONE -> {
                if (!store.layoutSuspects().isNullOrEmpty()) store.setLayoutSuspects("")
            }
            result.layoutSuspect -> {
                val list = LayoutWatch.add(LayoutWatch.decode(store.layoutSuspects()), SuspectCheck(courseId, epochDay))
                store.setLayoutSuspects(LayoutWatch.encode(list))
                DiagLog.add("halaman SiAdin termuat tapi tidak dikenali (${list.size}x berturut-turut)")
                if (LayoutWatch.shouldWarn(list) && store.claimLayoutWarning()) {
                    val names = Graph.repository.allCourses().associate { it.id to it.name }
                    val text = LayoutWatch.message(list, names::get)
                    DiagLog.add("PERINGATAN: tampilan SiAdin kemungkinan berubah")
                    Notifications.showLayoutChanged(context, text)
                    NoticeWorker.enqueue(context, "tampilan-siadin", "⚠️ $text")
                }
            }
            // Internet putus, login ditolak, dsb.: bukan tanda tampilan berubah; hitungan tidak diubah.
            else -> Unit
        }
    }
}
