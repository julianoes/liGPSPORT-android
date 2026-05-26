package de.syntaxfehler.ligpsport.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.syntaxfehler.ligpsport.route.Point
import de.syntaxfehler.ligpsport.search.PhotonClient
import de.syntaxfehler.ligpsport.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Which slot of the route is currently in inline-search mode.
 *
 * The bar can only have one slot in edit mode at a time — selecting a
 * row replaces the row's display with a [OutlinedTextField] and a
 * [LazyColumn] of [PhotonClient] suggestions. Picking a suggestion
 * resolves the edit by calling the appropriate `on…Change` callback.
 */
internal sealed interface EditTarget {
    /** First-time destination pick (the bar's only visible row before any route exists). */
    data object EmptyDestination : EditTarget
    data object Start : EditTarget
    data class Via(val id: Long) : EditTarget
    data object Destination : EditTarget
    /** Appends a new via to the end of the list. */
    data object AddStop : EditTarget
}

/**
 * Google-Maps-style stops sheet. Renders at the top of the map
 * screen, replaces the old single search bar, and lets the user
 * edit every slot of the planned route (start, vias, destination)
 * inline via [PhotonClient] autocomplete.
 *
 *  - **Empty state** (`destination == null`): one row reading
 *    "Where to?" — tapping it expands into search input.
 *  - **Populated state**: Start row → via rows (reorderable, removable)
 *    → "+ Add stop" → Destination row. Tap any row's label to swap
 *    that row into edit mode.
 *
 * The bar owns its own debounced search state (`queryFlow` +
 * `suggestions`) so the parent doesn't need to plumb anything extra.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun RouteStopsBar(
    destination: Destination?,
    intermediates: List<Waypoint>,
    startLabel: String,
    startIsOverride: Boolean,
    currentLocation: Point?,
    onDestinationPicked: (SearchResult) -> Unit,
    onStartChange: (SearchResult) -> Unit,
    onStartClear: () -> Unit,
    onViaChange: (id: Long, SearchResult) -> Unit,
    onViaRemove: (id: Long) -> Unit,
    onViaReorder: (from: Int, to: Int) -> Unit,
    onAddStop: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val queryFlow = remember { MutableStateFlow("") }

    // Debounced Photon autocomplete shared across every row's edit
    // mode. The bias prefers the user's live fix; falls back to the
    // currently-edited slot's coordinates so searching for "café" near
    // an existing waypoint surfaces results near that waypoint.
    LaunchedEffect(Unit) {
        queryFlow
            .debounce(300)
            .distinctUntilChanged()
            .collect { q ->
                if (q.isBlank()) {
                    suggestions = emptyList()
                    searching = false
                    return@collect
                }
                searching = true
                val bias = currentLocation
                val results = try {
                    withContext(Dispatchers.IO) {
                        PhotonClient().autocomplete(
                            query = q,
                            biasLat = bias?.latitude,
                            biasLon = bias?.longitude,
                            limit = 8,
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                suggestions = results
                searching = false
            }
    }

    // Whenever the editing target changes, seed the field. For an
    // empty-state pick or AddStop, we always start blank. For an
    // existing slot, prefill with the slot's current label so the
    // user sees what they're about to overwrite.
    LaunchedEffect(editing) {
        val seed = when (val e = editing) {
            null -> null
            EditTarget.EmptyDestination, EditTarget.AddStop -> ""
            EditTarget.Start -> if (startIsOverride) startLabel else ""
            is EditTarget.Via -> intermediates.firstOrNull { it.id == e.id }?.label ?: ""
            EditTarget.Destination -> destination?.label ?: ""
        }
        if (seed != null) {
            query = seed
            queryFlow.value = seed
        } else {
            query = ""
            queryFlow.value = ""
            suggestions = emptyList()
        }
    }

    val pickHandler: (SearchResult) -> Unit = pick@{ result ->
        when (val e = editing) {
            null -> return@pick
            EditTarget.EmptyDestination -> onDestinationPicked(result)
            EditTarget.Start -> onStartChange(result)
            is EditTarget.Via -> onViaChange(e.id, result)
            EditTarget.Destination -> onDestinationPicked(result)
            EditTarget.AddStop -> onAddStop(result)
        }
        editing = null
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("route_stops_bar"),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (destination == null) {
                EmptyStateRow(
                    editing = editing == EditTarget.EmptyDestination,
                    query = query,
                    onQueryChange = { query = it; queryFlow.value = it },
                    onActivate = { editing = EditTarget.EmptyDestination },
                    onDismiss = { editing = null },
                )
            } else {
                StartRow(
                    label = startLabel,
                    isOverride = startIsOverride,
                    editing = editing == EditTarget.Start,
                    query = query,
                    onQueryChange = { query = it; queryFlow.value = it },
                    onActivate = { editing = EditTarget.Start },
                    onDismiss = { editing = null },
                    onClear = onStartClear,
                )

                if (intermediates.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                    ReorderableVias(
                        vias = intermediates,
                        editingId = (editing as? EditTarget.Via)?.id,
                        query = query,
                        onQueryChange = { query = it; queryFlow.value = it },
                        onActivate = { id -> editing = EditTarget.Via(id) },
                        onDismiss = { editing = null },
                        onRemove = onViaRemove,
                        onReorder = onViaReorder,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                AddStopRow(
                    editing = editing == EditTarget.AddStop,
                    query = query,
                    onQueryChange = { query = it; queryFlow.value = it },
                    onActivate = { editing = EditTarget.AddStop },
                    onDismiss = { editing = null },
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                DestinationRow(
                    destination = destination,
                    editing = editing == EditTarget.Destination,
                    query = query,
                    onQueryChange = { query = it; queryFlow.value = it },
                    onActivate = { editing = EditTarget.Destination },
                    onDismiss = { editing = null },
                )
            }

            if (editing != null) {
                SuggestionsPanel(
                    searching = searching,
                    suggestions = suggestions,
                    onPick = pickHandler,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------

@Composable
private fun EmptyStateRow(
    editing: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (editing) {
        InlineSearchField(
            leadingIcon = Icons.Default.Search,
            placeholder = "Search a destination…",
            query = query,
            onQueryChange = onQueryChange,
            onDismiss = onDismiss,
            testTag = "search_bar_input",
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onActivate)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("search_bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Text(
                "Where to?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartRow(
    label: String,
    isOverride: Boolean,
    editing: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    if (editing) {
        InlineSearchField(
            leadingIcon = Icons.Default.MyLocation,
            placeholder = "Choose a starting point",
            query = query,
            onQueryChange = onQueryChange,
            onDismiss = onDismiss,
            testTag = "stop_row_start_input",
        )
    } else {
        StopRowDisplay(
            leadingIcon = { StartIcon() },
            label = label,
            testTag = "stop_row_start",
            onClick = onActivate,
            trailing = if (isOverride) {
                {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("stop_row_start_clear"),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Use my location")
                    }
                }
            } else null,
        )
    }
}

@Composable
private fun DestinationRow(
    destination: Destination,
    editing: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (editing) {
        InlineSearchField(
            leadingIcon = Icons.Default.LocationOn,
            placeholder = "Search a destination…",
            query = query,
            onQueryChange = onQueryChange,
            onDismiss = onDismiss,
            testTag = "stop_row_destination_input",
        )
    } else {
        StopRowDisplay(
            leadingIcon = {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            label = destination.label,
            testTag = "stop_row_destination",
            onClick = onActivate,
        )
    }
}

@Composable
private fun AddStopRow(
    editing: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (editing) {
        InlineSearchField(
            leadingIcon = Icons.Default.Add,
            placeholder = "Add a stop",
            query = query,
            onQueryChange = onQueryChange,
            onDismiss = onDismiss,
            testTag = "stop_row_add_input",
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onActivate)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("stop_row_add"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Add stop",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------
// Vias (reorderable)
// ---------------------------------------------------------------------

/**
 * Renders the via list with drag-to-reorder handles. The drag
 * mechanic is a simple cumulative vertical delta: once the user's
 * finger has moved more than half a row height in either direction,
 * the dragged via swaps with its neighbour and the accumulator
 * resets. Recomposition re-anchors the handle under the finger so
 * a continuous drag walks the via through the list one slot at a
 * time. Good enough for a list that's almost always under 5 items.
 */
@Composable
private fun ReorderableVias(
    vias: List<Waypoint>,
    editingId: Long?,
    query: String,
    onQueryChange: (String) -> Unit,
    onActivate: (id: Long) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (id: Long) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
) {
    val density = LocalDensity.current
    val rowHeightDp = 56.dp
    val swapThresholdPx = with(density) { (rowHeightDp / 2).toPx() }
    var accumulator by remember { mutableStateOf(0f) }
    var draggingId by remember { mutableStateOf<Long?>(null) }

    Column {
        vias.forEachIndexed { index, via ->
            val isEditing = via.id == editingId
            if (isEditing) {
                InlineSearchField(
                    leadingIcon = Icons.Default.LocationOn,
                    placeholder = "Search for a stop",
                    query = query,
                    onQueryChange = onQueryChange,
                    onDismiss = onDismiss,
                    testTag = "stop_row_via_input_${via.id}",
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = rowHeightDp)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("stop_row_via_${via.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Reorder stop",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("stop_row_via_handle_${via.id}")
                            .pointerInput(via.id, vias.size) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        draggingId = via.id
                                        accumulator = 0f
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        accumulator = 0f
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        accumulator = 0f
                                    },
                                    onVerticalDrag = onDrag@{ _, dy ->
                                        if (draggingId != via.id) return@onDrag
                                        accumulator += dy
                                        val currentIndex = vias.indexOfFirst { it.id == via.id }
                                        if (currentIndex < 0) return@onDrag
                                        if (accumulator >= swapThresholdPx && currentIndex < vias.lastIndex) {
                                            onReorder(currentIndex, currentIndex + 1)
                                            accumulator -= swapThresholdPx * 2
                                        } else if (accumulator <= -swapThresholdPx && currentIndex > 0) {
                                            onReorder(currentIndex, currentIndex - 1)
                                            accumulator += swapThresholdPx * 2
                                        }
                                    },
                                )
                            },
                    )
                    ViaIcon(index = index + 1)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onActivate(via.id) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            via.label ?: "Stop ${index + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "%.5f, %.5f".format(via.lat, via.lon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onRemove(via.id) },
                        modifier = Modifier.testTag("stop_row_via_remove_${via.id}"),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove stop")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------

@Composable
private fun StopRowDisplay(
    leadingIcon: @Composable () -> Unit,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingIcon()
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun InlineSearchField(
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    testTag: String,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Defer the focus request one frame so the field is laid out
        // before the IME tries to attach to it.
        focusRequester.requestFocus()
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Cancel, contentDescription = "Cancel")
            }
        },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { /* suggestions already visible */ }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(focusRequester)
            .testTag(testTag),
    )
}

@Composable
private fun SuggestionsPanel(
    searching: Boolean,
    suggestions: List<SearchResult>,
    onPick: (SearchResult) -> Unit,
) {
    if (searching && suggestions.isEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Searching…", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (suggestions.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .testTag("suggestions_panel"),
        ) {
            itemsIndexed(
                items = suggestions,
                // Index in the key guarantees uniqueness even when
                // Photon returns two features at the same (name, lat,
                // lon) — stacked POIs used to crash LazyColumn with
                // "Two keys are equal".
                key = { i, r -> "$i|${r.latitude}|${r.longitude}|${r.name}" },
            ) { _, result ->
                SuggestionRow(result = result, onClick = { onPick(result) })
            }
        }
    }
}

@Composable
private fun SuggestionRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("suggestion_${result.name}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(result.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (result.description.isNotBlank()) {
                Text(
                    result.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        result.distanceM?.let { dist ->
            Text(
                formatDistanceLocal(dist),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDistanceLocal(meters: Double): String = when {
    meters < 1_000 -> "${meters.toInt()} m"
    meters < 10_000 -> "%.1f km".format(meters / 1_000.0)
    else -> "${(meters / 1_000.0).toInt()} km"
}

@Composable
private fun StartIcon() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun ViaIcon(index: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$index",
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
