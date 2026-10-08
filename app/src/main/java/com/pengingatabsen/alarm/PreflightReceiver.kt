package com.pengingatabsen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pengingatabsen.data.DiagLog

/** Alarm "cek kesiapan" ±30 menit sebelum kuliah pertama: jalankan pengecekan uji, lalu pasang yang berikutnya. */
class PreflightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_PREFLIGHT) return
        runAsync {
            DiagLog.add("⏰ cek kesiapan otomatis")
            PresensiCheck.startPreflight(context)
            AlarmScheduler.schedulePreflight(context)
        }
    }
}
