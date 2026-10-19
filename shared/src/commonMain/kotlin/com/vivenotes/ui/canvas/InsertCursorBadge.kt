package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.trace
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.DrawSymbols
import com.vivenotes.ui.icons.insertTextGlyph
import com.vivenotes.ui.ribbon.RibbonStyle
import com.vivenotes.ui.ribbon.draw.drawToolkitTracing
import com.vivenotes.workspace.DrawPlacementTool
import com.vivenotes.workspace.WorkspaceState
import kotlin.math.roundToInt

/** What a click on the page puts there while the tool that inserts it is armed. */
internal sealed interface PendingInsert {
    data object TextBox : PendingInsert
    data class Shape(val kind: ShapeKind) : PendingInsert
    data object Table : PendingInsert
    data object Equation : PendingInsert
}

/** The object the armed tool puts on the page, or null while no armed tool places one there. */
internal fun WorkspaceState.pendingInsert(): PendingInsert? = when {
    selectedPage?.editable != true -> null
    textToolArmed -> PendingInsert.TextBox
    shapeToolArmed -> PendingInsert.Shape(shapeSettings.kind)
    drawPlacement == DrawPlacementTool.Table -> PendingInsert.Table
    drawPlacement == DrawPlacementTool.Equation && pendingEquation != null -> PendingInsert.Equation
    else -> null
}

internal object InsertBadgeTags {
    fun badge(insert: PendingInsert): String = "canvas-insert-badge-" + when (insert) {
        PendingInsert.TextBox -> "text-box"
        is PendingInsert.Shape -> "shape-${insert.kind.name}"
        PendingInsert.Table -> "table"
        PendingInsert.Equation -> "equation"
    }
}

/** Where a mouse or pen hovers over the canvas, in the canvas's own pixels; null while it is elsewhere. */
@Stable
internal class CanvasPointer {
    var position by mutableStateOf<Offset?>(null)
}

/**
 * Follows a mouse or pen over what this modifies into [pointer], taking nothing from the handlers
 * beneath it. A finger has no hover to follow.
 */
internal fun Modifier.followPointer(pointer: CanvasPointer): Modifier = pointerInput(pointer) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            pointer.position = if (event.type == PointerEventType.Exit || change.type == PointerType.Touch) null
                else change.position
        }
    }
}

private val BadgeShape = RoundedCornerShape(5.dp)
private val BadgeSize = 20.dp
private val GlyphSize = 14.dp
/** Right of and below the pointer's tip, clear of the arrow drawn down and right from it. */
private val PointerGap = 16.dp
/** Left of or above the tip, where no arrow is, when the canvas's edge leaves no room the usual way. */
private val FlippedGap = 4.dp

/**
 * A small badge beside the mouse pointer showing what a click will [insert] — the Text button's
 * glyph, the shape chosen, a table or an equation — in the colours of the armed ribbon button.
 * Nothing while the pointer is off the canvas. It takes no input, so clicks reach the page beneath.
 * [modifier] must give it the canvas's size: the badge is placed within it.
 */
@Composable
internal fun InsertCursorBadge(insert: PendingInsert?, pointer: CanvasPointer, modifier: Modifier = Modifier) {
    insert ?: return
    val hovering by remember(pointer) { derivedStateOf { pointer.position != null } }
    if (!hovering) return
    Layout(content = { Badge(insert) }, modifier = modifier) { measurables, constraints ->
        val badge = measurables.single().measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Read as the badge is placed, so following the pointer places it again and composes nothing.
            val at = pointer.position ?: return@layout
            val gap = PointerGap.toPx()
            val flipped = FlippedGap.toPx()
            val x = if (at.x + gap + badge.width <= constraints.maxWidth) at.x + gap else at.x - flipped - badge.width
            val y = if (at.y + gap + badge.height <= constraints.maxHeight) at.y + gap else at.y - flipped - badge.height
            badge.place(x.roundToInt().coerceAtLeast(0), y.roundToInt().coerceAtLeast(0))
        }
    }
}

@Composable
private fun Badge(insert: PendingInsert) {
    Box(
        Modifier.size(BadgeSize).shadow(3.dp, BadgeShape).background(RibbonStyle.background, BadgeShape)
            .border(1.dp, RibbonStyle.divider, BadgeShape).testTag(InsertBadgeTags.badge(insert)),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = Modifier.size(GlyphSize)
        when (insert) {
            PendingInsert.TextBox -> Icon(remember { insertTextGlyph(RibbonStyle.activeText, RibbonStyle.accent) },
                contentDescription = null, modifier = glyph, tint = Color.Unspecified)
            is PendingInsert.Shape -> Canvas(glyph) {
                val stroke = 1.4.dp.toPx()
                drawToolkitTracing(trace(insert.kind, stroke, stroke, size.width - stroke, size.height - stroke),
                    RibbonStyle.activeText, stroke, LineType.Solid)
            }
            PendingInsert.Table -> Icon(DrawSymbols.Table, contentDescription = null, modifier = glyph,
                tint = RibbonStyle.activeText)
            PendingInsert.Equation -> Icon(DocumentSymbols.Function, contentDescription = null, modifier = glyph,
                tint = RibbonStyle.activeText)
        }
    }
}
