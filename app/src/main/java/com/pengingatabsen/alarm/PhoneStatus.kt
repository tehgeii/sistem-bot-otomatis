package com.pengingatabsen.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import com.pengingatabsen.Graph
import com.pengingatabsen.logic.PhoneState

/** Membaca keadaan HP yang menentukan apakah getar presensi terasa (Jangan Ganggu, mode Senyap, baterai). */
object PhoneStatus {
    suspend fun read(context: Context): PhoneState {
        val nm = context.getSystemService(NotificationManager::class.java)
        val filter = runCatching { nm.currentInterruptionFilter }.getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL)
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val bypass = runCatching { nm.getNotificationChannel(Notifications.presensiChannel())?.canBypassDnd() == true }.getOrDefault(false)
        val audio = context.getSystemService(AudioManager::class.java)
        val silent = runCatching { audio?.ringerMode == AudioManager.RINGER_MODE_SILENT }.getOrDefault(false)
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        return PhoneState(
            dndActive = dnd,
            bypassDnd = bypass,
            dndAllowedByUser = Graph.settings.current().dndAllowed,
            ringerSilent = silent,
            batteryPercent = percent,
            charging = battery?.isCharging == true,
        )
    }

    /** Pengaturan channel presensi NgiBsen (tempat "Abaikan Jangan Ganggu"/"Override Do Not Disturb"). */
    fun dndSettingsIntent(context: Context, channel: String = Notifications.presensiChannel()): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Pengaturan suara (mengganti mode Senyap → Getar). */
    fun soundSettingsIntent(): Intent = Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
