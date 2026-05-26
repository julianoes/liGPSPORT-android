package de.syntaxfehler.ligpsport.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Two-action popup anchored to the left of the tapped pixel on the
 * map: "Set as destination" and "Add stop". Mirrors the Google-Maps
 * gesture where tapping the map after a destination is already set
 * asks the user what to do with that point.
 *
 * The popup width is fixed (220 dp) so the left-anchor placement is
 * deterministic; if the tap is too close to the left edge, fall back
 * to anchoring on the right of the tap so the popup never sails off
 * screen.
 */
@Composable
internal fun TapActionPopup(
    pixelX: Int,
    pixelY: Int,
    onSetDestination: () -> Unit,
    onAddStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val popupWidthDp = 220.dp
    val popupWidthPx = with(density) { popupWidthDp.roundToPx() }
    val gapPx = with(density) { 12.dp.roundToPx() }
    val leftAnchored = pixelX - popupWidthPx - gapPx
    val offsetX = if (leftAnchored >= 0) leftAnchored else pixelX + gapPx
    val offsetY = (pixelY - with(density) { 24.dp.roundToPx() }).coerceAtLeast(0)

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(offsetX, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            modifier = Modifier
                .width(popupWidthDp)
                .testTag("tap_action_popup"),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TextButton(
                    onClick = onSetDestination,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .testTag("tap_action_set_destination"),
                ) {
                    Icon(
                        Icons.Default.PinDrop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  Set as destination",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                TextButton(
                    onClick = onAddStop,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .testTag("tap_action_add_stop"),
                ) {
                    Icon(
                        Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  Add stop",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
