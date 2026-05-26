package de.syntaxfehler.ligpsport.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import de.syntaxfehler.ligpsport.ble.DeviceStore
import de.syntaxfehler.ligpsport.ble.UploadPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bottom-left navigation-status stack on [MapScreen]. Renders one
 * [NavStatusPill] per paired device (up to [DeviceStore.MAX_DEVICES]),
 * each polling its own device's `ROUTE_PLAN LIST_GET` every 15 s. The
 * pill text doubles as a "which devices are connected" indicator —
 * Connecting / Idle / Navigating implies the most recent poll
 * succeeded.
 *
 * - **Pair device first** — no devices in [DeviceStore].
 * - **<device> · Connecting…** — paired but the first poll hasn't
 *   completed or the most recent poll failed.
 * - **<device> · Navigating: <route name>** — BSC200 reports an
 *   `enum_USED_STATUS` entry in `ROUTE_PLAN LIST_GET`
 *   (PROTOCOL.md §7.3).
 * - **<device> · Idle** — paired, polled, but no route is tagged USED.
 *
 * Async by design: each pill never blocks the map and shows the
 * previous value while a new poll is in flight. Transient BLE
 * failures fall back to "connecting" rather than flipping to
 * "unpaired" — pairing state doesn't actually change.
 */
@Composable
internal fun NavStatusOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    var devices by remember { mutableStateOf(store.list()) }

    // Re-read the paired set on resume so adding / removing a device
    // in Settings shows up the next time the map foregrounds. Polling
    // on every recomposition would be wasteful for a list that only
    // changes when the user explicitly edits it.
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                devices = store.list()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = modifier.testTag("nav_status_stack"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (devices.isEmpty()) {
            NavStatusPill(
                state = NavStatusUiState.Unpaired,
                deviceLabel = null,
                modifier = Modifier.testTag("nav_status"),
            )
        } else {
            for (device in devices) {
                DeviceNavStatusPill(device = device)
            }
        }
    }
}

@Composable
private fun DeviceNavStatusPill(device: DeviceStore.Paired) {
    val ctx = LocalContext.current
    var state: NavStatusUiState by remember(device.mac) {
        mutableStateOf(NavStatusUiState.Connecting)
    }

    LaunchedEffect(device.mac) {
        while (true) {
            val res = withContext(Dispatchers.IO) {
                UploadPipeline.navStatus(ctx, targetMac = device.mac)
            }
            state = when (res) {
                is UploadPipeline.Result.Success -> {
                    val ns = res.navStatus
                    when {
                        ns == null -> NavStatusUiState.Connecting
                        ns.isNavigating -> NavStatusUiState.Navigating(
                            ns.activeRouteName.ifEmpty { ns.activeRouteId?.toString() ?: "?" },
                        )
                        else -> NavStatusUiState.Idle
                    }
                }
                // Keep showing the previous (or "Connecting") on
                // transient BLE failures rather than flipping back to
                // a more pessimistic state — the device is still
                // paired, the radio just glitched.
                is UploadPipeline.Result.Failure -> when (state) {
                    is NavStatusUiState.Navigating, NavStatusUiState.Idle -> state
                    else -> NavStatusUiState.Connecting
                }
            }
            delay(15_000)
        }
    }

    NavStatusPill(
        state = state,
        deviceLabel = device.name ?: device.mac,
        modifier = Modifier.testTag("nav_status_${device.mac}"),
    )
}

internal sealed interface NavStatusUiState {
    data object Unpaired : NavStatusUiState
    data object Connecting : NavStatusUiState
    data object Idle : NavStatusUiState
    data class Navigating(val routeName: String) : NavStatusUiState
}

@Composable
internal fun NavStatusPill(
    state: NavStatusUiState,
    deviceLabel: String?,
    modifier: Modifier = Modifier,
) {
    val statusText = when (state) {
        NavStatusUiState.Unpaired -> "Pair device first"
        NavStatusUiState.Connecting -> "Connecting…"
        NavStatusUiState.Idle -> "No active route"
        is NavStatusUiState.Navigating -> "Navigating: ${state.routeName}"
    }
    val label = if (deviceLabel != null) "$deviceLabel · $statusText" else statusText
    val leadingSpinner = state is NavStatusUiState.Connecting
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = when (state) {
        NavStatusUiState.Unpaired -> Icons.Filled.BluetoothDisabled
        NavStatusUiState.Connecting -> null
        NavStatusUiState.Idle -> Icons.Outlined.Navigation
        is NavStatusUiState.Navigating -> Icons.Filled.Navigation
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(end = 6.dp),
                )
            }
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
