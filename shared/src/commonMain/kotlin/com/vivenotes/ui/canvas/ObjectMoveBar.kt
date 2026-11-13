package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vivenotes.ui.icons.NavigationSymbols

/** A move bar's thickness in page dp: thin against its object, thick enough for a mouse to land on. */
internal const val OBJECT_MOVE_BAR_DP = 16f

/**
 * The bar along the top of a text box or a table that drags it: one look for both. [x], [top] and
 * [width] are page dp; [onMove] receives each step of a drag, the first one flagged for undo.
 */
@Composable
internal fun ObjectMoveBar(
    x: Float,
    top: Float,
    width: Float,
    border: Color,
    tag: String,
    label: String,
    key: Any?,
    onMove: (dx: Float, dy: Float, firstChange: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val grab = LocalObjectGrab.current
    val currentMove by rememberUpdatedState(onMove)
    val thickness = OBJECT_MOVE_BAR_DP.dp
    val shape = MaterialTheme.shapes.small.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    Box(
        // Over every neighbour: the object above this one may be drawn later and reach its top edge.
        Modifier.offset(x.dp, top.dp)
            .size(width.dp, thickness).zIndex(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, border, shape)
            .testTag(tag)
            .semantics { contentDescription = label }
            .pointerInput(key) {
                var firstChange = true
                detectObjectDrag(grab,
                    onDragStart = { firstChange = true },
                    onDrag = { change, drag ->
                        change.consume()
                        currentMove(with(density) { drag.x.toDp().value }, with(density) { drag.y.toDp().value }, firstChange)
                        firstChange = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(NavigationSymbols.DragIndicator, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(thickness).rotate(90f))
    }
}
