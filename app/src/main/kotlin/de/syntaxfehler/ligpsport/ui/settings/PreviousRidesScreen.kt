package de.syntaxfehler.ligpsport.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import de.syntaxfehler.ligpsport.data.PreviousRidesStore
import de.syntaxfehler.ligpsport.data.RouteSessionStore
import de.syntaxfehler.ligpsport.route.formatKm
import java.text.DateFormat
import java.util.Date

/**
 * History of every ride the user has uploaded. Tapping a card
 * replays its full editor state into [RouteSessionStore]; the
 * MapScreen's resume observer picks the new session up and restores
 * the destination, vias, start point and the saved polyline.
 *
 * Naming follows the brief: "$start → $dest" with " +N stops" when
 * the route had intermediate waypoints. The user-specified KM
 * readout sits on the second line alongside a short timestamp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviousRidesScreen(
    onBack: () -> Unit,
    onRestoredToMap: () -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { PreviousRidesStore(ctx) }
    var rides by remember { mutableStateOf(store.list()) }
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Previous rides") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        if (rides.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No rides yet — upload a route to add it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .testTag("previous_rides_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = rides, key = { it.id }) { ride ->
                RideCard(
                    ride = ride,
                    timestamp = dateFormat.format(Date(ride.savedAt)),
                    onOpen = {
                        // Hand the full snapshot to RouteSessionStore;
                        // MapScreen's resume observer compares its
                        // last-applied session and replays this one
                        // when the user navigates back.
                        RouteSessionStore.set(
                            RouteSessionStore.Session(
                                destinationName = ride.destinationLabel,
                                destinationLat = ride.destinationLat,
                                destinationLon = ride.destinationLon,
                                plannedGpx = ride.gpxBytes,
                                intermediates = ride.intermediates.map {
                                    RouteSessionStore.Stop(it.lat, it.lon, it.label)
                                },
                                startLat = ride.startLat,
                                startLon = ride.startLon,
                                startLabel = ride.startLabel,
                            ),
                        )
                        onRestoredToMap()
                    },
                    onDelete = {
                        store.remove(ride.id)
                        rides = store.list()
                    },
                )
            }
        }
    }
}

@Composable
private fun RideCard(
    ride: PreviousRidesStore.Ride,
    timestamp: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onOpen)
            .testTag("ride_${ride.id}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    ride.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatKm(ride.distanceM)}  ·  $timestamp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_${ride.id}"),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Delete ride")
            }
        }
    }
}
