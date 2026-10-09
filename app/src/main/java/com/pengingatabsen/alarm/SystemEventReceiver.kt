package com.pengingatabsen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarm AlarmManager hilang saat reboot / update aplikasi dan bisa meleset
 * saat jam atau zona waktu diubah, jadi semua alarm dihitung ulang di sini.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> runAsync {
                com.pengingatabsen.data.DiagLog.add("sistem: ${intent.action?.substringAfterLast('.')} → semua alarm dipasang ulang")
                AlarmScheduler.rescheduleAll(context)
                // Setelah HP menyala lagi: beri tahu bila ada alarm jam buka yang terlewat saat HP mati.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) PresensiCheck.checkMissedAlarms(context)
                // Diperbarui lewat "Perbarui sekarang": beri tahu & tawarkan membuka lagi.
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    com.pengingatabsen.update.SelfUpdater.afterPackageReplaced(context)
                }
            }
        }
    }
}
