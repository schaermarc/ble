package com.example.beaconscanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BeaconScanner(context: Context) {

    private val bluetoothManager =
        context.applicationContext.getSystemService(BluetoothManager::class.java)

    private val bleScanner
        get() = bluetoothManager?.adapter?.bluetoothLeScanner

    private val _beacons = MutableStateFlow<List<EddystoneUidBeacon>>(emptyList())
    val beacons: StateFlow<List<EddystoneUidBeacon>> = _beacons.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handle)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            _scanning.value = false
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(EDDYSTONE_UUID) ?: return
        val beacon = EddystoneUidBeacon.parse(
            data = data,
            address = result.device.address,
            rssi = result.rssi,
            now = System.currentTimeMillis(),
        ) ?: return

        val current = _beacons.value.toMutableList()
        val idx = current.indexOfFirst { it.deviceAddress == beacon.deviceAddress }
        if (idx >= 0) current[idx] = beacon else current += beacon
        _beacons.value = current
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val scanner = bleScanner ?: return false
        if (_scanning.value) return true
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(EDDYSTONE_UUID).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(filters, settings, callback)
        _scanning.value = true
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!_scanning.value) return
        runCatching { bleScanner?.stopScan(callback) }
        _scanning.value = false
    }

    fun clear() {
        _beacons.value = emptyList()
    }

    companion object {
        private const val TAG = "BeaconScanner"
        val EDDYSTONE_UUID: ParcelUuid = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
    }
}
