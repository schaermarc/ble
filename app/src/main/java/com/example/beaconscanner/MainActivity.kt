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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BBraunGreen = Color(0xFF00857C)

private val BBraunColorScheme = lightColorScheme(
    primary = BBraunGreen,
    onPrimary = Color.White,
    secondary = BBraunGreen,
    onSecondary = Color.White,
    tertiary = BBraunGreen,
    onTertiary = Color.White,
)

class MainActivity : ComponentActivity() {

    private val scanner: BeaconScanner get() = (application as App).scanner
    private val uploader: BeaconUploader get() = (application as App).uploader
    private val locationTracker: LocationTracker get() = (application as App).locationTracker

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) ensureBluetoothAndStart()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startScanService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = BBraunColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BeaconScreen(
                        scanner = scanner,
                        uploader = uploader,
                        locationTracker = locationTracker,
                        prefs = prefs,
                        onStart = ::requestPermissionsAndStart,
                        onStop = ::stopScanService,
                        onClear = scanner::clear,
                        onOpenLocationSettings = {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                    )
                }
            }
        }

        requestPermissionsAndStart()
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
        startScanService()
    }

    private fun startScanService() {
        val intent = Intent(this, ScanService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopScanService() {
        // The service stops scanner + uploader in onDestroy.
        val intent = Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP)
        startService(intent)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
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

@Composable
private fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = R.drawable.ic_bbraun_logo),
            contentDescription = "B. Braun",
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            "Eddystone-UID Scanner",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
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
    locationTracker: LocationTracker,
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
        mutableStateOf(prefs.getString(PREF_UPLOAD_ENDPOINT, DEFAULT_HTTP_ENDPOINT).orEmpty())
    }
    var uploadEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_UPLOAD_ENABLED, false))
    }
    var intervalSecText by remember {
        mutableStateOf(prefs.getInt(PREF_UPLOAD_INTERVAL_SECONDS, DEFAULT_UPLOAD_INTERVAL_SECONDS).toString())
    }
    var uploadMode by remember {
        mutableStateOf(prefs.getString(PREF_UPLOAD_MODE, MODE_HTTP) ?: MODE_HTTP)
    }
    var ehHost by remember { mutableStateOf(prefs.getString(PREF_EH_HOST, DEFAULT_EH_HOST).orEmpty()) }
    var ehKeyName by remember { mutableStateOf(prefs.getString(PREF_EH_KEY_NAME, DEFAULT_EH_KEY_NAME).orEmpty()) }
    var ehKey by remember { mutableStateOf(prefs.getString(PREF_EH_KEY, DEFAULT_EH_KEY).orEmpty()) }
    var ehHub by remember { mutableStateOf(prefs.getString(PREF_EH_HUB, DEFAULT_EH_HUB).orEmpty()) }

    fun currentTarget(): UploadTarget? = when (uploadMode) {
        MODE_EVENT_HUB -> if (
            ehHost.isNotBlank() && ehKeyName.isNotBlank() &&
            ehKey.isNotBlank() && ehHub.isNotBlank()
        ) UploadTarget.AzureEventHub(
            host = ehHost.trim(), keyName = ehKeyName.trim(),
            key = ehKey, hubName = ehHub.trim(),
        ) else null
        else -> if (
            endpoint.startsWith("http://") || endpoint.startsWith("https://")
        ) UploadTarget.Http(endpoint.trim()) else null
    }

    fun restartIfEnabled() {
        if (!uploadEnabled) return
        val target = currentTarget() ?: run { uploader.stop(); return }
        val secs = intervalSecText.toIntOrNull()
            ?.coerceAtLeast(MIN_UPLOAD_INTERVAL_SECONDS)
            ?: DEFAULT_UPLOAD_INTERVAL_SECONDS
        uploader.start(target, secs * 1000L)
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
    val location by locationTracker.location.collectAsStateWithLifecycle()

    val all = devices.values.toList()
    val eddystone = all.mapNotNull { it.eddystone }

    val normalized = filter.replace("-", "").replace(" ", "").replace(":", "").lowercase()
    val visibleEddystone =
        if (normalized.isEmpty()) eddystone
        else eddystone.filter { it.instanceId.lowercase().contains(normalized) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AppHeader()
        Spacer(Modifier.height(8.dp))

        StatusCard(
            permsOk = permsOk,
            locationOk = locationOk,
            scanning = scanning,
            totalDevices = all.size,
            eddystoneCount = eddystone.size,
            lastError = lastError,
            location = location,
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
            mode = uploadMode,
            onModeChange = { m ->
                uploadMode = m
                prefs.edit().putString(PREF_UPLOAD_MODE, m).apply()
                restartIfEnabled()
            },
            endpoint = endpoint,
            onEndpointChange = {
                endpoint = it
                prefs.edit().putString(PREF_UPLOAD_ENDPOINT, it).apply()
                restartIfEnabled()
            },
            ehHost = ehHost,
            onEhHostChange = {
                ehHost = it
                prefs.edit().putString(PREF_EH_HOST, it).apply()
                restartIfEnabled()
            },
            ehKeyName = ehKeyName,
            onEhKeyNameChange = {
                ehKeyName = it
                prefs.edit().putString(PREF_EH_KEY_NAME, it).apply()
                restartIfEnabled()
            },
            ehKey = ehKey,
            onEhKeyChange = {
                ehKey = it
                prefs.edit().putString(PREF_EH_KEY, it).apply()
                restartIfEnabled()
            },
            ehHub = ehHub,
            onEhHubChange = {
                ehHub = it
                prefs.edit().putString(PREF_EH_HUB, it).apply()
                restartIfEnabled()
            },
            enabled = uploadEnabled,
            onEnabledChange = { on ->
                uploadEnabled = on
                prefs.edit().putBoolean(PREF_UPLOAD_ENABLED, on).apply()
                if (on) {
                    val target = currentTarget()
                    val secs = intervalSecText.toIntOrNull()
                        ?.coerceAtLeast(MIN_UPLOAD_INTERVAL_SECONDS)
                        ?: DEFAULT_UPLOAD_INTERVAL_SECONDS
                    if (target != null) uploader.start(target, secs * 1000L)
                    else uploader.stop()
                } else uploader.stop()
            },
            intervalSecText = intervalSecText,
            onIntervalChange = { raw ->
                val clean = raw.filter { it.isDigit() }.take(5)
                intervalSecText = clean
                val secs = clean.toIntOrNull()
                if (secs != null && secs >= MIN_UPLOAD_INTERVAL_SECONDS) {
                    prefs.edit().putInt(PREF_UPLOAD_INTERVAL_SECONDS, secs).apply()
                    restartIfEnabled()
                }
            },
            targetReady = currentTarget() != null,
            uploader = uploader,
        )
        Spacer(Modifier.height(8.dp))

        if (showAll) {
            Text("${all.size} BLE-Geräte", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            all.sortedByDescending { it.rssi }.forEach { DeviceCard(it) }
        } else {
            val count = visibleEddystone.size
            val caption = "$count Eddystone-UID" +
                (if (normalized.isNotEmpty()) " (gefiltert aus ${eddystone.size})" else "")
            Text(caption, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            visibleEddystone.sortedByDescending { it.rssi }.forEach { BeaconCard(it) }
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
    location: android.location.Location?,
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
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (location != null) {
                    val ageSec = ((System.currentTimeMillis() - location.time) / 1000).coerceAtLeast(0)
                    val acc = if (location.hasAccuracy()) " ±%.0fm".format(location.accuracy) else ""
                    "Position: %.6f, %.6f%s   •   %ds alt   •   %s".format(
                        location.latitude, location.longitude, acc, ageSec,
                        location.provider ?: "?",
                    )
                } else "Position: (noch kein Fix)",
                style = MaterialTheme.typography.bodySmall,
            )
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
    mode: String,
    onModeChange: (String) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    ehHost: String,
    onEhHostChange: (String) -> Unit,
    ehKeyName: String,
    onEhKeyNameChange: (String) -> Unit,
    ehKey: String,
    onEhKeyChange: (String) -> Unit,
    ehHub: String,
    onEhHubChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    intervalSecText: String,
    onIntervalChange: (String) -> Unit,
    targetReady: Boolean,
    uploader: BeaconUploader,
) {
    val status by uploader.status.collectAsStateWithLifecycle()
    val intervalSec = intervalSecText.toIntOrNull()
    val intervalValid = intervalSec != null && intervalSec >= MIN_UPLOAD_INTERVAL_SECONDS

    var expanded by rememberSaveable { mutableStateOf(false) }
    val modeLabel = if (mode == MODE_EVENT_HUB) "Azure Event Hub" else "HTTP"
    val summary = buildString {
        append("Upload ($modeLabel): ")
        append(if (status.running) "AN" else "AUS")
        if (intervalValid) append(" • ${intervalSec}s")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    summary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeChoice(
                        label = "HTTP",
                        selected = mode == MODE_HTTP,
                        onSelect = { onModeChange(MODE_HTTP) },
                        modifier = Modifier.weight(1f),
                    )
                    ModeChoice(
                        label = "Azure Event Hub",
                        selected = mode == MODE_EVENT_HUB,
                        onSelect = { onModeChange(MODE_EVENT_HUB) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (mode == MODE_EVENT_HUB) {
                    OutlinedTextField(
                        value = ehHost,
                        onValueChange = onEhHostChange,
                        label = { Text("Hostname") },
                        placeholder = { Text("mynamespace.servicebus.windows.net") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ehHub,
                        onValueChange = onEhHubChange,
                        label = { Text("Uplink Topic (Event Hub Name)") },
                        placeholder = { Text("beacons") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ehKeyName,
                        onValueChange = onEhKeyNameChange,
                        label = { Text("Shared Access Key Name") },
                        placeholder = { Text("RootManageSharedAccessKey") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ehKey,
                        onValueChange = onEhKeyChange,
                        label = { Text("Shared Access Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val endpointValid = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = onEndpointChange,
                        label = { Text("Endpoint-URL (POST JSON)") },
                        placeholder = { Text("https://example.com/beacons") },
                        singleLine = true,
                        isError = endpoint.isNotBlank() && !endpointValid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = intervalSecText,
                    onValueChange = onIntervalChange,
                    label = { Text("Intervall (Sekunden, min. $MIN_UPLOAD_INTERVAL_SECONDS)") },
                    singleLine = true,
                    isError = !intervalValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        enabled = targetReady && intervalValid,
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
}

@Composable
private fun ModeChoice(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onSelect() }.padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
