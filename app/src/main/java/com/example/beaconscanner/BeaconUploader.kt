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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UploadStatus(
    val running: Boolean = false,
    val lastAttemptMillis: Long? = null,
    val lastSuccess: Boolean? = null,
    val lastMessage: String? = null,
    val lastBeaconCount: Int? = null,
)

class BeaconUploader(
    private val scope: CoroutineScope,
    private val getBeacons: () -> List<EddystoneUidBeacon>,
) {
    private val _status = MutableStateFlow(UploadStatus())
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    private var job: Job? = null

    fun start(endpoint: String, intervalMillis: Long = 30_000L) {
        stop()
        _status.value = _status.value.copy(running = true)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                uploadOnce(endpoint)
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = _status.value.copy(running = false)
    }

    private fun uploadOnce(endpoint: String) {
        val beacons = getBeacons()
        val body = buildJson(beacons)
        val now = System.currentTimeMillis()
        val next = try {
            val code = post(endpoint, body)
            UploadStatus(
                running = true,
                lastAttemptMillis = now,
                lastSuccess = code in 200..299,
                lastMessage = "HTTP $code",
                lastBeaconCount = beacons.size,
            )
        } catch (e: Exception) {
            UploadStatus(
                running = true,
                lastAttemptMillis = now,
                lastSuccess = false,
                lastMessage = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
                lastBeaconCount = beacons.size,
            )
        }
        _status.value = next
    }

    private fun buildJson(beacons: List<EddystoneUidBeacon>): String {
        val arr = JSONArray()
        for (b in beacons) {
            arr.put(
                JSONObject()
                    .put("namespace", b.namespace)
                    .put("instanceId", b.instanceId)
                    .put("txPower", b.txPower)
                    .put("rssi", b.rssi)
                    .put("deviceAddress", b.deviceAddress)
                    .put("lastSeenMillis", b.lastSeenMillis)
            )
        }
        return JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("beaconCount", beacons.size)
            .put("beacons", arr)
            .toString()
    }

    private fun post(endpoint: String, body: String): Int {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
