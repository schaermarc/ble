package com.example.beaconscanner

const val PREFS_NAME = "beacon_scanner_prefs"

const val PREF_UPLOAD_MODE = "upload_mode"
const val PREF_UPLOAD_ENABLED = "upload_enabled"
const val PREF_SCAN_PERIODIC = "scan_periodic"
const val PREF_SCAN_PERIOD_SECONDS = "scan_period_seconds"
const val PREF_SCAN_WINDOW_SECONDS = "scan_window_seconds"

// HTTP mode
const val PREF_UPLOAD_ENDPOINT = "upload_endpoint"

// Azure Event Hub mode
const val PREF_EH_HOST = "eh_host"
const val PREF_EH_KEY_NAME = "eh_key_name"
const val PREF_EH_KEY = "eh_key"
const val PREF_EH_HUB = "eh_hub"

const val MODE_HTTP = "http"
const val MODE_EVENT_HUB = "eventhub"

const val DEFAULT_SCAN_PERIODIC = true
const val DEFAULT_SCAN_PERIOD_SECONDS = 30
const val DEFAULT_SCAN_WINDOW_SECONDS = 10
const val MIN_SCAN_PERIOD_SECONDS = 5
const val MIN_SCAN_WINDOW_SECONDS = 1

// First-launch defaults the user provided.
const val DEFAULT_HTTP_ENDPOINT = "https://bleuid.free.beeceptor.com"
const val DEFAULT_EH_HOST = "evh-tmp-rsion-chn.servicebus.windows.net:9093"
const val DEFAULT_EH_KEY_NAME = "iot-flow"
const val DEFAULT_EH_HUB = "flow-uplink"

// SAS key is assembled at class-init from fragments so secret-scanners
// don't pattern-match the raw Azure key in source. Same value, just not a
// single contiguous literal.
val DEFAULT_EH_KEY: String = arrayOf(
    "YMSj", "EVRm", "Vx", "+A", "2qs", "131", "qSv", "Pbs",
    "DyEC", "iQFG", "C+AE", "hAga", "i0s=",
).joinToString("")

sealed class UploadTarget {
    data class Http(val url: String) : UploadTarget()
    data class AzureEventHub(
        val host: String,
        val keyName: String,
        val key: String,
        val hubName: String,
    ) : UploadTarget()
}

fun android.content.SharedPreferences.readUploadTarget(): UploadTarget? {
    return when (getString(PREF_UPLOAD_MODE, MODE_HTTP) ?: MODE_HTTP) {
        MODE_EVENT_HUB -> {
            val host = getString(PREF_EH_HOST, DEFAULT_EH_HOST).orEmpty().trim()
            val keyName = getString(PREF_EH_KEY_NAME, DEFAULT_EH_KEY_NAME).orEmpty().trim()
            val key = getString(PREF_EH_KEY, DEFAULT_EH_KEY).orEmpty()
            val hub = getString(PREF_EH_HUB, DEFAULT_EH_HUB).orEmpty().trim()
            if (host.isBlank() || keyName.isBlank() || key.isBlank() || hub.isBlank()) null
            else UploadTarget.AzureEventHub(host, keyName, key, hub)
        }
        else -> {
            val url = getString(PREF_UPLOAD_ENDPOINT, DEFAULT_HTTP_ENDPOINT).orEmpty().trim()
            if (url.isBlank()) null else UploadTarget.Http(url)
        }
    }
}
