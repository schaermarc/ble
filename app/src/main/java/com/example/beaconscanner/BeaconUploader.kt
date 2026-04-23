package com.example.beaconscanner

import android.location.Location
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    private val getLocation: () -> Location? = { null },
) {
    private val _status = MutableStateFlow(UploadStatus())
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    private var job: Job? = null

    fun start(target: UploadTarget, intervalMillis: Long = 30_000L) {
        stop()
        _status.value = _status.value.copy(running = true)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                uploadOnce(target)
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = _status.value.copy(running = false)
    }

    private fun uploadOnce(target: UploadTarget) {
        val beacons = getBeacons()
        val body = buildJson(beacons)
        val now = System.currentTimeMillis()
        val next = try {
            val code = when (target) {
                is UploadTarget.Http -> postHttp(target.url, body)
                is UploadTarget.AzureEventHub -> postEventHub(target, body)
            }
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
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

        val beaconArr = JSONArray()
        for (b in beacons) {
            // beaconId = last 2 bytes (= last 4 hex chars) of the 6-byte instance id.
            val beaconId = b.instanceId.takeLast(4)
            beaconArr.put(
                JSONObject()
                    .put("beaconId", beaconId)
                    .put("rssi", b.rssi)
            )
        }

        val dataScanCollection = JSONObject()
            .put("scanType", "BLE_BEACONS")
            .put("again", false)
            .put("dataFormat", "BEACON_ID")
            .put("fragmentIdentification", 0)
            .put("collectionIdentifier", 230)
            .put("hash", 128)
            .put("beaconIdData", beaconArr)

        val payload = JSONObject()
            .put("messageType", "DATA_SCAN_COLLECTION")
            .put("trackingMode", "PERMANENT_TRACKING")
            .put("batteryLevel", 92)
            .put("batteryStatus", "OPERATING")
            .put("ackToken", 1)
            .put("periodicPosition", false)
            .put("temperatureMeasure", 25.3)
            .put("sosFlag", 0)
            .put("appState", 1)
            .put("dynamicMotionState", "STATIC")
            .put("onDemand", false)
            .put("payload", "0b485c891000e680a903ba4a")
            .put("deviceConfiguration", JSONObject().put("mode", "PERMANENT_TRACKING"))
            .put("dataScanCollection", dataScanCollection)

        val points = JSONObject()
            .put("batteryLevel", JSONObject().put("unitId", "%").put("record", 92))
            .put("temperature", JSONObject().put("unitId", "Cel").put("record", 25.3))

        val uplink = JSONObject()
            .put("Time", time)
            .put("DevEUI", "20635F0181001445")
            .put("payload_hex", "0b485c891000e680a903ba4a")
            .put("payload", payload)
            .put("points", points)

        return JSONObject().put("DevEUI_uplink", uplink).toString()
    }

    private fun postHttp(url: String, body: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
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

    private fun postEventHub(cfg: UploadTarget.AzureEventHub, body: String): Int {
        val host = cfg.host.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val hub = cfg.hubName.trim().trim('/')
        val resourceUri = "https://$host/$hub"
        val expiry = System.currentTimeMillis() / 1000 + 3600
        val token = sasToken(resourceUri, cfg.keyName, cfg.key, expiry)

        val url = URL("$resourceUri/messages?api-version=2014-01")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json;charset=utf-8")
            setRequestProperty("Authorization", token)
            setRequestProperty("Host", host)
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

    /**
     * Azure Service Bus / Event Hubs Shared Access Signature:
     * SharedAccessSignature sr=<encoded resource uri>&sig=<encoded sig>&se=<expiry epoch secs>&skn=<key name>
     *
     * Signature = HMAC-SHA256(key, URLEncode(resourceUri) + "\n" + expiry)   (base64)
     */
    private fun sasToken(resourceUri: String, keyName: String, key: String, expiryEpochSec: Long): String {
        val encodedUri = URLEncoder.encode(resourceUri, "UTF-8")
        val stringToSign = "$encodedUri\n$expiryEpochSec"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        val sig = Base64.getEncoder().encodeToString(raw)
        return "SharedAccessSignature sr=$encodedUri" +
            "&sig=${URLEncoder.encode(sig, "UTF-8")}" +
            "&se=$expiryEpochSec" +
            "&skn=${URLEncoder.encode(keyName, "UTF-8")}"
    }
}
