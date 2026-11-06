package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.ui.canvas.LocalObjectGrab
import com.vivenotes.ui.canvas.detectObjectDrag
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.isUnwritten

/** The move bar's thickness in page dp: thin against a box, thick enough for a mouse to land on. */
internal const val TEXT_BOX_MOVE_BAR_DP = 16f

internal enum class TextBoxMoveBarMode { Hidden, OnHover, Always }

/**
 * When a box shows its move bar: while it is edited or lasso-selected, and otherwise while the mouse
 * is over it. A tool that acts on presses keeps the strip above a box for itself, an unwritten box is
 * still provisional, and an ink selection moves through its own frame.
 */
internal fun WorkspaceState.textBoxMoveBarMode(box: Outline.Text): TextBoxMoveBarMode = when {
    selectedPage?.editable != true || selectedInk != null || inkTool != null -> TextBoxMoveBarMode.Hidden
    box.id in selectedTextOutlineIds -> TextBoxMoveBarMode.Always
    box.isUnwritten() -> TextBoxMoveBarMode.Hidden
    focusedTextOutlineId == box.id -> TextBoxMoveBarMode.Always
    textToolArmed || shapeToolArmed || objectLassoArmed || drawPlacement != null -> TextBoxMoveBarMode.Hidden
    else -> TextBoxMoveBarMode.OnHover
}

internal fun TextBoxMoveBarMode.shows(hovered: Boolean): Boolean =
    this == TextBoxMoveBarMode.Always || (this == TextBoxMoveBarMode.OnHover && hovered)

/**
 * The bar's top in page dp: above the box, or on it at the page's top. It laps the box by a line, so
 * that its bottom border and the box's top border are one line rather than a doubled one.
 */
private val Outline.Text.moveBarTop: Float
    get() = (y.coerceAtLeast(0f) + 1f - TEXT_BOX_MOVE_BAR_DP).coerceAtLeast(0f)

internal fun Outline.Text.moveBarContains(point: Offset): Boolean {
    val left = x.coerceAtLeast(0f)
    return point.x in left..(left + width.coerceIn(120f, 2000f)) &&
        point.y in moveBarTop..(moveBarTop + TEXT_BOX_MOVE_BAR_DP)
}

/**
 * The text box a mouse at [point] (page dp) is over: the one whose bar is up there, as bars lie over
 * everything, or else the topmost with its body there. None behind a Prime Object, drawn over text.
 */
internal fun WorkspaceState.textBoxUnderMouse(point: Offset, hovered: String?, heights: Map<String, Float>): String? {
    val boxes = selectedPage?.document?.outlines.orEmpty().filterIsInstance<Outline.Text>()
    boxes.firstOrNull { it.moveBarContains(point) && textBoxMoveBarMode(it).shows(it.id == hovered) }?.let { return it.id }
    if (objectCovers(point, heights)) return null
    return boxes.lastOrNull { !it.isUnwritten() && it.bodyContains(point, heights) }?.id
}

/**
 * Keeps [hovered] on the text box under the mouse, asking [boxAt] from the document rather than the
 * laid-out elements: a box a drag has just moved has not been laid out yet when the button is let go.
 * A held button freezes the answer, so a bar being dragged cannot vanish from under the drag.
 */
internal fun Modifier.followTextBoxHover(key: Any?, hovered: MutableState<String?>, boxAt: (Offset) -> String?): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse || event.buttons.areAnyPressed) continue
                hovered.value = if (event.type == PointerEventType.Exit) null else boxAt(change.position)
            }
        }
    }

/** The box's top corners when the bar sits on them, so the two read as one window. */
@Composable
internal fun textBoxUnderBarShape(): CornerBasedShape =
    MaterialTheme.shapes.small.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)

/** A bar along a text box's top edge, outside the box so that its text stays where it is stored. */
@Composable
internal fun TextBoxMoveBar(
    box: Outline.Text,
    border: Color,
    onMove: (dx: Float, dy: Float, firstChange: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val grab = LocalObjectGrab.current
    val currentMove by rememberUpdatedState(onMove)
    val thickness = TEXT_BOX_MOVE_BAR_DP.dp
    val shape = MaterialTheme.shapes.small.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    Box(
        // Over every neighbour: the box above this one may be drawn later and reach its top edge.
        Modifier.offset(box.x.coerceAtLeast(0f).dp, box.moveBarTop.dp)
            .size(box.width.coerceIn(120f, 2000f).dp, thickness).zIndex(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, border, shape)
            .testTag(WorkspaceTestTags.textMoveBar(box.id))
            .semantics { contentDescription = Strings.move_text_box }
            .pointerInput(box.id) {
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
