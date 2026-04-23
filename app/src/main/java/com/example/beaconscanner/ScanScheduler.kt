package com.example.beaconscanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives a duty-cycled BLE scan: every `periodSec` seconds, scans for
 * `windowSec` seconds, stops scanning (to save power), then POSTs the
 * beacons captured in that window to the configured upload target
 * (if any) and sleeps until the next period boundary.
 *
 * Re-reads its parameters each cycle via [paramsProvider], so changes
 * to period / window / upload target / enabled flag take effect at the
 * next cycle without requiring a restart.
 */
class ScanScheduler(
    private val scope: CoroutineScope,
    private val scanner: BeaconScanner,
    private val uploader: BeaconUploader,
    private val paramsProvider: () -> Params,
) {
    data class Params(
        val target: UploadTarget?,
        val uploadEnabled: Boolean,
        val periodMs: Long,
        val windowMs: Long,
    )

    private var job: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        stop()
        _running.value = true
        uploader.setRunning(paramsProvider().uploadEnabled)
        job = scope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    val p = paramsProvider()
                    val window = p.windowMs.coerceAtMost(p.periodMs).coerceAtLeast(500L)
                    val cycleStart = System.currentTimeMillis()

                    // Fresh beacon set per window — upload reflects only what was
                    // heard during this scan burst.
                    scanner.clear()
                    scanner.start()
                    delay(window)
                    scanner.stop()

                    uploader.setRunning(p.uploadEnabled)
                    if (p.uploadEnabled && p.target != null) {
                        runCatching { uploader.uploadOnce(p.target) }
                    }

                    val elapsed = System.currentTimeMillis() - cycleStart
                    val sleep = p.periodMs - elapsed
                    if (sleep > 0) delay(sleep)
                }
            } finally {
                scanner.stop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scanner.stop()
        uploader.setRunning(false)
        _running.value = false
    }
}
