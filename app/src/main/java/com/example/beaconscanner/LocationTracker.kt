package com.example.beaconscanner

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the device's WGS84 location via the platform LocationManager (no
 * Google Play Services dependency). Seeds with last-known location on start,
 * then subscribes to GPS + NETWORK (+ FUSED where available) updates and
 * keeps only the most recent fix in [location].
 */
class LocationTracker(context: Context) {

    private val lm = context.applicationContext
        .getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val listener = LocationListener { loc ->
        val current = _location.value
        if (current == null || loc.time >= current.time) _location.value = loc
    }

    @Volatile private var started = false

    @SuppressLint("MissingPermission")
    fun start() {
        val lm = lm ?: return
        if (started) return

        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }

        providers.forEach { p ->
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let(listener::onLocationChanged)
                }
            }
        }
        providers.forEach { p ->
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 5_000L, 0f, listener, Looper.getMainLooper())
                }
            }
        }

        started = true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        runCatching { lm?.removeUpdates(listener) }
        started = false
    }
}
