package com.example.beaconscanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private lateinit var scanner: BeaconScanner

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            ensureBluetoothAndStart()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { scanner.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BeaconScanner(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BeaconScreen(
                        scanner = scanner,
                        onStart = ::requestPermissionsAndStart,
                        onStop = scanner::stop,
                        onClear = scanner::clear,
                    )
                }
            }
        }

        requestPermissionsAndStart()
    }

    override fun onDestroy() {
        scanner.stop()
        super.onDestroy()
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ensureBluetoothAndStart()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureBluetoothAndStart() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) return
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        scanner.start()
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

@Composable
private fun BeaconScreen(
    scanner: BeaconScanner,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val beacons by scanner.beacons.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()

    val normalized = filter.replace("-", "").replace(" ", "").replace(":", "").lowercase()
    val visible = if (normalized.isEmpty()) beacons
    else beacons.filter { it.instanceId.lowercase().contains(normalized) }
    val sorted = visible.sortedByDescending { it.rssi }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Eddystone-UID Scanner",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))

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
            if (scanning) {
                Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Scan") }
            }
            Button(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))

        Text(
            text = "${sorted.size} Beacon(s)" +
                (if (normalized.isNotEmpty()) " (gefiltert aus ${beacons.size})" else "") +
                if (scanning) " • scanning…" else "",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn {
            items(sorted, key = { it.deviceAddress }) { BeaconCard(it) }
        }
    }
}

@Composable
private fun BeaconCard(beacon: EddystoneUidBeacon) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Instance-ID",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = beacon.instanceId,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Namespace",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = beacon.namespace,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${beacon.deviceAddress}  •  RSSI ${beacon.rssi} dBm  •  TxPower ${beacon.txPower} dBm",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
