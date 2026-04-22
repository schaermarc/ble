package com.example.beaconscanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "beacon_scanner_prefs"
private const val PREF_UPLOAD_ENDPOINT = "upload_endpoint"
private const val PREF_UPLOAD_ENABLED = "upload_enabled"

class MainActivity : ComponentActivity() {

    private lateinit var scanner: BeaconScanner
    private lateinit var uploader: BeaconUploader

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) ensureBluetoothAndStart()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { scanner.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BeaconScanner(this)
        uploader = BeaconUploader(
            scope = lifecycleScope,
            getBeacons = { scanner.devices.value.values.mapNotNull { it.eddystone } },
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BeaconScreen(
                        scanner = scanner,
                        uploader = uploader,
                        prefs = prefs,
                        onStart = ::requestPermissionsAndStart,
                        onStop = scanner::stop,
                        onClear = scanner::clear,
                        onOpenLocationSettings = {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                    )
                }
            }
        }

        requestPermissionsAndStart()

        // Resume auto-upload if it was on before the app was killed.
        val savedEndpoint = prefs.getString(PREF_UPLOAD_ENDPOINT, "").orEmpty()
        val savedEnabled = prefs.getBoolean(PREF_UPLOAD_ENABLED, false)
        if (savedEnabled && savedEndpoint.isNotBlank()) {
            uploader.start(savedEndpoint)
        }
    }

    override fun onDestroy() {
        uploader.stop()
        scanner.stop()
        super.onDestroy()
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ensureBluetoothAndStart()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun ensureBluetoothAndStart() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        scanner.start()
    }

    private fun requiredPermissions(): List<String> = buildList {
        // Request FINE_LOCATION on every Android version. The manifest no longer
        // sets neverForLocation on BLUETOOTH_SCAN, so on 12+ we also need location
        // permission for the OS to hand us all scan results reliably.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}

private fun Context.permissionsGranted(): Boolean {
    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

private fun Context.locationServicesEnabled(): Boolean {
    // Required whenever FINE_LOCATION backs the scan (i.e. no neverForLocation
    // flag on BLUETOOTH_SCAN) — which is true on every Android version here.
    val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
    else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

@Composable
private fun BeaconScreen(
    scanner: BeaconScanner,
    uploader: BeaconUploader,
    prefs: android.content.SharedPreferences,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var filter by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var permsOk by remember { mutableStateOf(context.permissionsGranted()) }
    var locationOk by remember { mutableStateOf(context.locationServicesEnabled()) }

    var endpoint by remember {
        mutableStateOf(prefs.getString(PREF_UPLOAD_ENDPOINT, "").orEmpty())
    }
    var uploadEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_UPLOAD_ENABLED, false))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permsOk = context.permissionsGranted()
                locationOk = context.locationServicesEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val devices by scanner.devices.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    val lastError by scanner.lastError.collectAsStateWithLifecycle()

    val all = devices.values.toList()
    val eddystone = all.mapNotNull { it.eddystone }

    val normalized = filter.replace("-", "").replace(" ", "").replace(":", "").lowercase()
    val visibleEddystone =
        if (normalized.isEmpty()) eddystone
        else eddystone.filter { it.instanceId.lowercase().contains(normalized) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Eddystone-UID Scanner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        StatusCard(
            permsOk = permsOk,
            locationOk = locationOk,
            scanning = scanning,
            totalDevices = all.size,
            eddystoneCount = eddystone.size,
            lastError = lastError,
            onOpenLocationSettings = onOpenLocationSettings,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Instance-ID Filter (hex, Teilstring)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (scanning) Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
            else Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Scan") }
            Button(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear") }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showAll, onCheckedChange = { showAll = it })
            Text("  Alle BLE-Geräte anzeigen (Debug)", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))

        UploadCard(
            endpoint = endpoint,
            onEndpointChange = {
                endpoint = it
                prefs.edit().putString(PREF_UPLOAD_ENDPOINT, it).apply()
                if (uploadEnabled) {
                    if (it.isBlank()) uploader.stop()
                    else uploader.start(it)
                }
            },
            enabled = uploadEnabled,
            onEnabledChange = { on ->
                uploadEnabled = on
                prefs.edit().putBoolean(PREF_UPLOAD_ENABLED, on).apply()
                if (on && endpoint.isNotBlank()) uploader.start(endpoint)
                else uploader.stop()
            },
            uploader = uploader,
        )
        Spacer(Modifier.height(8.dp))

        if (showAll) {
            Text("${all.size} BLE-Geräte", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(all.sortedByDescending { it.rssi }, key = { it.address }) { DeviceCard(it) }
            }
        } else {
            val count = visibleEddystone.size
            val caption = "$count Eddystone-UID" +
                (if (normalized.isNotEmpty()) " (gefiltert aus ${eddystone.size})" else "")
            Text(caption, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(visibleEddystone.sortedByDescending { it.rssi }, key = { it.deviceAddress }) {
                    BeaconCard(it)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    permsOk: Boolean,
    locationOk: Boolean,
    scanning: Boolean,
    totalDevices: Int,
    eddystoneCount: Int,
    lastError: Int?,
    onOpenLocationSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Status: " + when {
                    !permsOk -> "Permission fehlt"
                    !locationOk -> "Standortdienst AUS"
                    !scanning -> "gestoppt"
                    else -> "scanning…"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text("BLE-Geräte: $totalDevices   •   Eddystone-UID: $eddystoneCount",
                style = MaterialTheme.typography.bodyMedium)
            if (lastError != null) {
                Spacer(Modifier.height(4.dp))
                Text("Scan-Fehler Code $lastError", style = MaterialTheme.typography.bodySmall)
            }
            if (!locationOk) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Standortdienst muss im System AN sein, sonst liefert Android " +
                        "die BLE-Scan-Ergebnisse nicht vollständig aus.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onOpenLocationSettings) { Text("Standort-Einstellungen öffnen") }
            }
        }
    }
}

@Composable
private fun BeaconCard(beacon: EddystoneUidBeacon) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Instance-ID", style = MaterialTheme.typography.labelMedium)
            Text(beacon.instanceId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Namespace", style = MaterialTheme.typography.labelMedium)
            Text(beacon.namespace, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "${beacon.deviceAddress}  •  RSSI ${beacon.rssi} dBm  •  TxPower ${beacon.txPower} dBm",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeviceCard(device: ScannedDevice) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(device.name ?: "(kein Name)", style = MaterialTheme.typography.titleSmall)
            Text("${device.address}  •  RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            if (device.serviceUuids.isNotEmpty()) {
                Text(
                    "Service-UUIDs: " + device.serviceUuids.joinToString { shortUuid(it.toString()) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (device.serviceDataKeys.isNotEmpty()) {
                Text(
                    "Service-Data: " + device.serviceDataKeys.joinToString { shortUuid(it.toString()) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val ed = device.eddystone
            if (ed != null) {
                Spacer(Modifier.height(4.dp))
                Text("Eddystone UID:", style = MaterialTheme.typography.labelMedium)
                Text("NS ${ed.namespace}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("ID ${ed.instanceId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            val raw = device.rawBytes
            if (raw != null && raw.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Raw adv:", style = MaterialTheme.typography.labelMedium)
                Text(
                    AdvertisementParser.toHex(raw, max = 62),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun shortUuid(u: String): String {
    // Compact common base UUIDs (0000xxxx-0000-1000-8000-00805f9b34fb) to 16-bit form.
    return if (u.length == 36 && u.endsWith("-0000-1000-8000-00805f9b34fb") && u.startsWith("0000"))
        "0x" + u.substring(4, 8).uppercase()
    else u
}

@Composable
private fun UploadCard(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    uploader: BeaconUploader,
) {
    val status by uploader.status.collectAsStateWithLifecycle()
    val endpointValid = endpoint.startsWith("http://") || endpoint.startsWith("https://")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "HTTP-Upload (alle 30 s)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                label = { Text("Endpoint-URL (POST JSON)") },
                placeholder = { Text("https://example.com/beacons") },
                singleLine = true,
                isError = endpoint.isNotBlank() && !endpointValid,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    enabled = endpointValid,
                    onCheckedChange = onEnabledChange,
                )
                Text(
                    "  " + if (status.running) "Upload AN (läuft)" else "Upload AUS",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val ts = status.lastAttemptMillis
            if (ts != null) {
                Spacer(Modifier.height(4.dp))
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
                val tag = if (status.lastSuccess == true) "OK" else "FEHLER"
                Text(
                    "Letzter Versuch $time: $tag" +
                        (status.lastMessage?.let { " — $it" } ?: "") +
                        (status.lastBeaconCount?.let { " (${it} Beacons)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
