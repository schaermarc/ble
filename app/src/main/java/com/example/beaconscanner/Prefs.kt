package com.example.beaconscanner

const val PREFS_NAME = "beacon_scanner_prefs"

const val PREF_UPLOAD_MODE = "upload_mode"
const val PREF_UPLOAD_ENABLED = "upload_enabled"
const val PREF_UPLOAD_INTERVAL_SECONDS = "upload_interval_seconds"

// HTTP mode
const val PREF_UPLOAD_ENDPOINT = "upload_endpoint"

// Azure Event Hub mode
const val PREF_EH_HOST = "eh_host"
const val PREF_EH_KEY_NAME = "eh_key_name"
const val PREF_EH_KEY = "eh_key"
const val PREF_EH_HUB = "eh_hub"

const val MODE_HTTP = "http"
const val MODE_EVENT_HUB = "eventhub"

const val DEFAULT_UPLOAD_INTERVAL_SECONDS = 30
const val MIN_UPLOAD_INTERVAL_SECONDS = 5

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
            val host = getString(PREF_EH_HOST, "").orEmpty().trim()
            val keyName = getString(PREF_EH_KEY_NAME, "").orEmpty().trim()
            val key = getString(PREF_EH_KEY, "").orEmpty()
            val hub = getString(PREF_EH_HUB, "").orEmpty().trim()
            if (host.isBlank() || keyName.isBlank() || key.isBlank() || hub.isBlank()) null
            else UploadTarget.AzureEventHub(host, keyName, key, hub)
        }
        else -> {
            val url = getString(PREF_UPLOAD_ENDPOINT, "").orEmpty().trim()
            if (url.isBlank()) null else UploadTarget.Http(url)
        }
    }
}
