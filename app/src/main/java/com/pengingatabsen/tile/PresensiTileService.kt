package com.pengingatabsen.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pengingatabsen.launch.LaunchTargetActivity
import com.pengingatabsen.logic.Formatters
import com.pengingatabsen.logic.TodayState
import com.pengingatabsen.widget.TodayData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tile Quick Settings "Presensi": sekali tap membuka halaman presensi (sama seperti widget). Tile menyala saat
 * presensi matkul yang sedang berjalan sudah terlihat dibuka dosen. Tombol presensi tetap kamu tekan sendiri.
 */
class PresensiTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val loaded = runCatching { TodayData.load() }.getOrNull()
            val (courseId, epochDay) = TodayData.launchTarget(loaded?.focus)
            val intent = LaunchTargetActivity.intent(this@PresensiTileService, courseId, epochDay)
            val open = Runnable { openActivity(intent) }
            if (isLocked) unlockAndRun(open) else open.run()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openActivity(intent: android.content.Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private suspend fun refresh() {
        val tile = qsTile ?: return
        val loaded = runCatching { TodayData.load() }.getOrNull()
        val focus = loaded?.focus
        tile.label = "Presensi"
        tile.state = if (focus?.state == TodayState.OPEN) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle = when {
            loaded == null -> "Belum ada jadwal"
            focus == null -> loaded.view.next?.let { "Berikutnya ${Formatters.dayName(it.open.dayOfWeek.value).take(3)} ${Formatters.hm(it.open)}" }
                ?: "Tidak ada kuliah"
            focus.state == TodayState.OPEN -> "DIBUKA: ${focus.name}"
            focus.state == TodayState.WAITING -> "Menunggu: ${focus.name}"
            else -> "${Formatters.hm(focus.open)} ${focus.name}"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = "Buka presensi. $subtitle"
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
