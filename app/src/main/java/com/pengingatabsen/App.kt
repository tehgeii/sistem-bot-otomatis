package com.pengingatabsen

import android.app.Application
import android.content.Context
import com.pengingatabsen.alarm.Notifications
import com.pengingatabsen.data.AppDatabase
import com.pengingatabsen.data.Repository
import com.pengingatabsen.data.SettingsStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
    }
}

/** Service locator sederhana; cukup untuk aplikasi pribadi satu modul. */
object Graph {
    lateinit var appContext: Context
        private set
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val repository: Repository by lazy { Repository(db) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
