package com.pengingatabsen.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.pengingatabsen.alarm.runAsync

/** Menerima hasil dari pemasang Android untuk sesi pembaruan [SelfUpdater] (tidak terekspor). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        val app = context.applicationContext
        runAsync { SelfUpdater.onInstallResult(app, status, message, confirm) }
    }
}
