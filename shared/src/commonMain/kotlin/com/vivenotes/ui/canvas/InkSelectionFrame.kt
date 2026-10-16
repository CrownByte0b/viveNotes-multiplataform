package com.vivenotes.ui.canvas

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.model.ink.InkSelection

internal const val INK_SELECTION_FRAME_TAG = "workspace-ink-selection-frame"

@Composable
internal fun InkSelectionFrame(
    selection: InkSelection, bounds: Rect, offset: Offset, enabled: Boolean, zoom: Float,
    onFocus: () -> Unit, onOffset: (Offset) -> Unit, onMove: (InkSelection, Offset) -> Unit,
) {
    val density = LocalDensity.current.density
    Box(Modifier.offset((bounds.left + offset.x - 6f).dp, (bounds.top + offset.y - 6f).dp)
        .size((bounds.width + 12f).dp, (bounds.height + 12f).dp)
        .border(1.5.dp, MaterialTheme.colorScheme.primary)
        .testTag(INK_SELECTION_FRAME_TAG)
        .pointerInput(selection, enabled, zoom, density) {
            var total = Offset.Zero
            detectDragGestures(onDragStart = { total = Offset.Zero; onFocus() },
                onDragCancel = { onOffset(Offset.Zero) },
                onDragEnd = { if (enabled && total != Offset.Zero) onMove(selection, total) else onOffset(Offset.Zero) },
                onDrag = { change, drag ->
                    change.consume()
                    if (enabled) {
                        total += drag / density
                        total = Offset(total.x.coerceAtLeast(-bounds.left), total.y.coerceAtLeast(-bounds.top))
                        onOffset(total)
                    }
                })
        })
}
