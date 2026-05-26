package de.syntaxfehler.ligpsport.ui.pairing

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.syntaxfehler.ligpsport.ble.DeviceScanner
import de.syntaxfehler.ligpsport.ble.DeviceStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * BLE device picker. Requests the runtime permissions iGPSPORT needs,
 * scans for advertising packets matching the known name prefixes (BSC,
 * iGS, iGPSPORT), and *appends* the user's choice to the list of paired
 * devices — up to [DeviceStore.MAX_DEVICES]. Already-paired entries
 * surface at the top with X buttons so the user can drop a device
 * without leaving the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(onPaired: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val paired = remember { mutableStateListOf<DeviceStore.Paired>().apply { addAll(store.list()) } }
    val scanned = remember { mutableStateMapOf<String, ScanEntry>() }
    var scanning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val requiredPermissions: Array<String> = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (!permissionsGranted) statusMessage = "Bluetooth permission denied."
    }
    LaunchedEffect(permissionsGranted) {
        if (!permissionsGranted) permissionLauncher.launch(requiredPermissions)
    }

    val scope = rememberCoroutineScope()
    val scanner = remember {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        DeviceScanner(bm?.adapter)
    }
    val startScan: () -> Unit = {
        if (!permissionsGranted) {
            statusMessage = "Grant Bluetooth permissions first."
        } else {
            scanning = true
            scanned.clear()
            scope.launch {
                try {
                    @Suppress("MissingPermission")
                    scanner.scan().collectLatest { dev ->
                        @Suppress("MissingPermission")
                        val name = try { dev.name } catch (_: SecurityException) { null }
                        scanned[dev.address] = ScanEntry(dev, name)
                    }
                } catch (e: Exception) {
                    statusMessage = "Scan failed: ${e.message}"
                } finally {
                    scanning = false
                }
            }
        }
    }
    LaunchedEffect(permissionsGranted) { if (permissionsGranted) startScan() }

    val capReached = paired.size >= DeviceStore.MAX_DEVICES

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair your iGPSPORT") },
                actions = {
                    IconButton(onClick = startScan, enabled = !scanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().testTag("paired_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Currently-paired devices --------------------------------
            item {
                SectionLabel(
                    "Paired (${paired.size} / ${DeviceStore.MAX_DEVICES})",
                )
            }
            if (paired.isEmpty()) {
                item {
                    Text(
                        "No devices paired yet — pick one from the scan results below.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(items = paired.toList(), key = { it.mac }) { p ->
                    PairedRow(
                        paired = p,
                        onRemove = {
                            store.remove(p.mac)
                            paired.removeAll { it.mac.equals(p.mac, ignoreCase = true) }
                        },
                    )
                }
            }

            // Scan section ------------------------------------------
            item {
                SectionLabel(
                    if (capReached) "Cap reached — remove one to add another"
                    else "Nearby devices",
                )
            }
            if (scanning) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Scanning for BSC / iGS / iGPSPORT devices…")
                    }
                }
            } else {
                item {
                    Text(
                        "Make sure your computer is awake and within range.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            statusMessage?.let {
                item {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(
                items = scanned.values.sortedBy { it.name ?: it.device.address }.toList(),
                key = { it.device.address },
            ) { entry ->
                val alreadyPaired = paired.any { it.mac.equals(entry.device.address, ignoreCase = true) }
                ScannedRow(
                    entry = entry,
                    alreadyPaired = alreadyPaired,
                    enabled = !alreadyPaired && !capReached,
                    onClick = {
                        if (alreadyPaired || capReached) return@ScannedRow
                        val added = store.add(name = entry.name, mac = entry.device.address)
                        if (!added) {
                            statusMessage =
                                "Device limit (${DeviceStore.MAX_DEVICES}) reached — remove one first."
                            return@ScannedRow
                        }
                        // Refresh in-memory snapshot from disk so the
                        // canonical (uppercased) MAC is what we render.
                        paired.clear()
                        paired.addAll(store.list())
                        // Pop back: the user picked a device, the
                        // explicit confirmation isn't needed.
                        onPaired()
                    },
                )
            }
        }
    }
}

private data class ScanEntry(val device: BluetoothDevice, val name: String?)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PairedRow(paired: DeviceStore.Paired, onRemove: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("paired_${paired.mac}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    paired.name ?: "(unnamed)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    paired.mac,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag("remove_${paired.mac}"),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Remove pairing")
            }
        }
    }
}

@Composable
private fun ScannedRow(
    entry: ScanEntry,
    alreadyPaired: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("device_${entry.device.address}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (alreadyPaired) Icons.Default.CheckCircle else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (alreadyPaired) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.fillMaxWidth()) {
                Text(
                    entry.name ?: "(unnamed)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled || alreadyPaired) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (alreadyPaired) "${entry.device.address} • paired"
                    else entry.device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
