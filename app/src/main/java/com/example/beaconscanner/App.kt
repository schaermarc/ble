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
    lateinit var locationTracker: LocationTracker
        private set
    lateinit var scheduler: ScanScheduler
        private set

    private val bgScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    override fun onCreate() {
        super.onCreate()
        scanner = BeaconScanner(this)
        locationTracker = LocationTracker(this)
        uploader = BeaconUploader(
            getBeacons = { scanner.devices.value.values.mapNotNull { it.eddystone } },
            getLocation = { locationTracker.location.value },
        )
        scheduler = ScanScheduler(
            scope = bgScope,
            scanner = scanner,
            uploader = uploader,
            locationTracker = locationTracker,
            paramsProvider = { readSchedulerParams() },
        )
    }

    private fun readSchedulerParams(): ScanScheduler.Params {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val periodSec = prefs.getInt(PREF_SCAN_PERIOD_SECONDS, DEFAULT_SCAN_PERIOD_SECONDS)
            .coerceAtLeast(MIN_SCAN_PERIOD_SECONDS)
        val windowSec = prefs.getInt(PREF_SCAN_WINDOW_SECONDS, DEFAULT_SCAN_WINDOW_SECONDS)
            .coerceAtLeast(MIN_SCAN_WINDOW_SECONDS)
        return ScanScheduler.Params(
            target = prefs.readUploadTarget(),
            uploadEnabled = prefs.getBoolean(PREF_UPLOAD_ENABLED, false),
            periodMs = periodSec * 1000L,
            windowMs = windowSec * 1000L,
        )
    }
}
