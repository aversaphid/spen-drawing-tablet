package com.tablet.penemulator

import android.app.Application

class PenTabletApplication : Application() {

    lateinit var bluetoothManager: BluetoothHidManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        bluetoothManager = BluetoothHidManager(applicationContext)
    }

    companion object {
        lateinit var instance: PenTabletApplication
            private set
    }
}

