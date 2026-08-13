package br.etc.victor.myhealthbridge

import android.app.Application

class MyHealthBridgeApplication : Application() {

    val graph: SyncGraph by lazy { SyncGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Asked for here rather than from the screen: the hourly request has to exist after a reboot,
        // whether or not the Data Owner opens the application again.
        SyncScheduler(this).scheduleHourly()
    }
}
