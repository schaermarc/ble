package com.example.beaconscanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<ParcelUuid>,
    val serviceDataKeys: List<ParcelUuid>,
    val eddystone: EddystoneUidBeacon?,
    val rawBytes: ByteArray?,
    val lastSeenMillis: Long,
)

class BeaconScanner(context: Context) {

    private val bluetoothManager =
        context.applicationContext.getSystemService(BluetoothManager::class.java)

    private val bleScanner
        get() = bluetoothManager?.adapter?.bluetoothLeScanner

    private val _devices = MutableStateFlow<Map<String, ScannedDevice>>(emptyMap())
    val devices: StateFlow<Map<String, ScannedDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastError = MutableStateFlow<Int?>(null)
    val lastError: StateFlow<Int?> = _lastError.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            _lastError.value = errorCode
            _scanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(result: ScanResult) {
        val record = result.scanRecord
        val now = System.currentTimeMillis()

        // Three-layer lookup. getServiceData() is the API path; the map lookup
        // handles cases where the key's ParcelUuid instance doesn't compare equal
        // by reference; the raw-bytes parser handles OEM stacks that return a
        // null/empty serviceData map despite valid 0x16 AD blocks in the payload.
        val eddystoneData: ByteArray? = record?.let { rec ->
            rec.getServiceData(EDDYSTONE_UUID)
                ?: rec.serviceData?.entries?.firstOrNull { it.key.uuid == EDDYSTONE_UUID.uuid }?.value
                ?: AdvertisementParser.findEddystoneServiceData(rec.bytes)
        }
        val eddystone = eddystoneData?.let {
            EddystoneUidBeacon.parse(it, result.device.address, result.rssi, now)
        }

        val name = runCatching { result.device.name }.getOrNull() ?: record?.deviceName

        val device = ScannedDevice(
            address = result.device.address,
            name = name,
            rssi = result.rssi,
            serviceUuids = record?.serviceUuids.orEmpty(),
            serviceDataKeys = record?.serviceData?.keys?.toList().orEmpty(),
            eddystone = eddystone,
            rawBytes = record?.bytes,
            lastSeenMillis = now,
        )
        _devices.update { it + (device.address to device) }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val scanner = bleScanner ?: return false
        if (_scanning.value) return true
        _lastError.value = null
        // Minimal settings: MATCH_MODE / NUM_MATCHES defaults work best across OEMs.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(emptyList(), settings, callback)
        Log.i(TAG, "BLE scan started (no filter)")
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
        _devices.value = emptyMap()
    }

    companion object {
        private const val TAG = "BeaconScanner"
        val EDDYSTONE_UUID: ParcelUuid = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
    }
}
