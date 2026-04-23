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

        val loc = getLocation()
        val lrrLat = if (loc != null) String.format(Locale.US, "%.6f", loc.latitude) else "0.000000"
        val lrrLon = if (loc != null) String.format(Locale.US, "%.6f", loc.longitude) else "0.000000"

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

        val lrrs = JSONObject().put(
            "Lrr",
            JSONArray().put(
                JSONObject()
                    .put("Lrrid", "100117ED")
                    .put("Chain", "0")
                    .put("LrrRSSI", "-59.000000")
                    .put("LrrSNR", "10.500000")
                    .put("LrrESP", "-59.370777")
            )
        )

        val customerData = JSONObject()
            .put("loc", JSONObject.NULL)
            .put("alr", JSONObject().put("pro", "ABEE/APY").put("ver", "1"))
            .put("tags", JSONArray().put("Hohmad"))
            .put("doms", JSONArray())
            .put("name", "AbeewayCompact_0272")
            .put("lrrID", JSONObject.NULL)
            .put("rfProbe", JSONObject.NULL)

        val baseStationData = JSONObject()
            .put("doms", JSONArray())
            .put("name", "000800-21621688")

        val driverCfg = JSONObject()
            .put(
                "mod",
                JSONObject().put("pId", "abeeway").put("mId", "compact-tracker").put("ver", "1")
            )
            .put(
                "app",
                JSONObject().put("pId", "abeeway").put("mId", "asset-tracker").put("ver", "2")
            )
            .put("id", "abeeway:asset-tracker:3")

        val uplink = JSONObject()
            .put("Time", time)
            .put("DevEUI", "20635F05B100045D")
            .put("FPort", "18")
            .put("FCntUp", "84398")
            .put("LostUplinksAS", "0")
            .put("ADRbit", "1")
            .put("MType", "2")
            .put("FCntDn", "1355")
            .put("payload_hex", "0b485c891000e680a903ba4a")
            .put("mic_hex", "56acbde2")
            .put("Lrcid", "00000401")
            .put("LrrRSSI", "-59.000000")
            .put("LrrSNR", "10.500000")
            .put("LrrESP", "-59.370777")
            .put("SpFact", "7")
            .put("SubBand", "G1")
            .put("Channel", "LC2")
            .put("Lrrid", "100117ED")
            .put("Late", "0")
            .put("LrrLAT", lrrLat)
            .put("LrrLON", lrrLon)
            .put("Lrrs", lrrs)
            .put("DevLrrCnt", "1")
            .put("CustomerID", "100055680")
            .put("CustomerData", customerData)
            .put("BaseStationData", baseStationData)
            .put("ModelCfg", "1:AbeewayCompact")
            .put("DriverCfg", driverCfg)
            .put("InstantPER", "0.000000")
            .put("MeanPER", "0.000000")
            .put("DevAddr", "08A2749B")
            .put("TxPower", "2.000000")
            .put("NbTrans", "1")
            .put("Frequency", "868.3")
            .put("DynamicClass", "A")
            .put("PayloadEncryption", 0)
            .put("payload", payload)
            .put("points", points)
            .put(
                "downlinkUrl",
                "https://portal.lpn.swisscom.ch/iot-flow/downlinkMessages/" +
                    "c5d165ef-a47f-42bd-b07e-2c2ed03444e0"
            )

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
        // The Event Hubs REST API only serves HTTPS on 443. Users often paste a
        // hostname copied from a Kafka/AMQP connection string that carries
        // :9093 or :5671 — strip any explicit port so we stay on 443.
        val host = cfg.host.removePrefix("https://").removePrefix("http://")
            .trimEnd('/').substringBefore(':')
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
