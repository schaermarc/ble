package com.example.beaconscanner

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    lateinit var scanner: BeaconScanner
        private set
    lateinit var uploader: BeaconUploader
        private set

    private val uploaderScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    override fun onCreate() {
        super.onCreate()
        scanner = BeaconScanner(this)
        uploader = BeaconUploader(
            scope = uploaderScope,
            getBeacons = { scanner.devices.value.values.mapNotNull { it.eddystone } },
        )
    }
}
