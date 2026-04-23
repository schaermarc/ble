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
 * Drives BLE scan bursts. Two modes:
 *
 * - [startPeriodic]: every `periodMs`, scan for `windowMs`, upload, idle.
 * - [runOnce]: a single scan burst of `windowMs`, upload, then done.
 *
 * Parameters are re-read via [paramsProvider] at the start of each cycle
 * so live edits take effect without a restart. Beacons captured within
 * a window accumulate (the scanner deduplicates by MAC and keeps entries
 * for the whole window); the list is cleared only at the start of each
 * new scan burst.
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

    fun startPeriodic() {
        launchLoop(periodic = true, onComplete = null)
    }

    fun runOnce(onComplete: (() -> Unit)? = null) {
        launchLoop(periodic = false, onComplete = onComplete)
    }

    private fun launchLoop(periodic: Boolean, onComplete: (() -> Unit)?) {
        stop()
        _running.value = true
        uploader.setRunning(paramsProvider().uploadEnabled)
        job = scope.launch(Dispatchers.Default) {
            try {
                if (periodic) {
                    while (isActive) runCycle(sleepAfterUpload = true)
                } else {
                    runCycle(sleepAfterUpload = false)
                }
            } finally {
                scanner.stop()
                uploader.setRunning(false)
                _running.value = false
                onComplete?.invoke()
            }
        }
    }

    private suspend fun runCycle(sleepAfterUpload: Boolean) {
        val p = paramsProvider()
        val window = p.windowMs.coerceAtMost(p.periodMs).coerceAtLeast(500L)
        val cycleStart = System.currentTimeMillis()

        // Fresh beacon set per window — the upload at the end of the
        // window reflects only beacons heard during this burst.
        scanner.clear()
        scanner.start()
        delay(window)
        scanner.stop()

        uploader.setRunning(p.uploadEnabled)
        if (p.uploadEnabled && p.target != null) {
            runCatching { uploader.uploadOnce(p.target) }
        }

        if (sleepAfterUpload) {
            val elapsed = System.currentTimeMillis() - cycleStart
            val sleep = p.periodMs - elapsed
            if (sleep > 0) delay(sleep)
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
