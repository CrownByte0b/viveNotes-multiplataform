package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState

/**
 * Whether a pen is what moves the mouse over a window's content. On native Wayland the desktop app
 * hands a hovering pen to the UI as mouse motion, so that cursors and hover states follow it as they
 * do for a pen on X11 and Windows, and raises [active] from the pen's hover until it leaves or the
 * mouse itself is used: by its pointer type alone the canvas could not tell that pen from the mouse.
 * The pen's contacts keep it raised; they reach the UI as a stylus.
 */
@Stable
class StylusHover {
    var active by mutableStateOf(false)
}

/** This window's [StylusHover]; null where a hovering pen reaches the UI only as a stylus pointer. */
val LocalStylusHover = staticCompositionLocalOf<StylusHover?> { null }

/**
 * The circle under a stylus on the page, after Android's eraser indicator: the armed Draw tool's
 * width at the pen's tip, so the pen shows where and how wide it will draw or erase.
 */
internal object StylusIndicator {
    /** A thin pen's circle is still this wide, so that it is visible at all. */
    val MinimumDiameter = 6.dp

    /** The armed tool's width in page dp, or null for a tool that shows no circle. */
    fun diameterDp(state: WorkspaceState): Float? = when (state.inkTool) {
        InkTool.Pen -> state.inkSettings.pen(state.activePenIndex).sizeDp
        InkTool.Highlighter -> state.inkSettings.highlighter.sizeDp
        InkTool.Eraser -> state.inkSettings.eraser.sizeDp
        InkTool.Laser, null -> null
    }
}

/**
 * Where a stylus hovers, in the canvas's pixels: a stylus pointer's own hover, or mouse hover while
 * [hover] says a pen stands in for the mouse. Null otherwise, and for the mouse itself.
 */
internal fun CanvasPointer.stylusHoverAt(hover: StylusHover?): Offset? {
    val at = position ?: return null
    if (phase != CanvasPointer.Phase.Hover) return null
    val stylus = type == PointerType.Stylus || type == PointerType.Eraser
    return at.takeIf { stylus || type == PointerType.Mouse && hover?.active == true }
}

/** Whether a stylus touches the canvas. */
internal fun CanvasPointer.stylusTouching(): Boolean =
    position != null && phase == CanvasPointer.Phase.Contact && (type == PointerType.Stylus || type == PointerType.Eraser)

/** Where the circle goes: at a hovering stylus, and with the eraser also where it erases, as on Android. */
internal fun CanvasPointer.stylusIndicatorAt(hover: StylusHover?, eraser: Boolean): Offset? {
    stylusHoverAt(hover)?.let { return it }
    return position.takeIf { eraser && stylusTouching() }
}

/**
 * Whether the circle stands in for the cursor, with a tool that shows one: while the pen is what moves
 * the mouse ([hover]) — hovering, touching or just lifted — or a stylus hovers. Following the pen
 * rather than each of its events, the canvas asks for no cursor already as a lifted pen's hover comes
 * back to it, where a cursor taken from the page between its strokes would show for a moment.
 * Read in composition, it changes only as the pen arrives or leaves, not as it moves.
 */
@Composable
internal fun stylusReplacesCursor(pointer: CanvasPointer, hover: StylusHover?, toolShowsCircle: Boolean): Boolean {
    val replaces = remember(pointer, hover, toolShowsCircle) {
        derivedStateOf { toolShowsCircle && (hover?.active == true || pointer.stylusHoverAt(null) != null) }
    }
    return replaces.value
}

/**
 * Android's eraser indicator in its colours: a translucent white disc, a dark ring and a light one,
 * [diameterPx] wide (at least [StylusIndicator.MinimumDiameter]) and centred on [center]. Each ring is
 * 1 dp, dark at the circle's edge and light just inside it, so the outline shows on white paper and on
 * dark ink alike; Android's 2 px ring with a 1 px light line in its middle all but vanishes at 1x.
 */
internal fun DrawScope.drawStylusIndicator(center: Offset, diameterPx: Float) {
    val radius = maxOf(diameterPx, StylusIndicator.MinimumDiameter.toPx()) / 2f
    val line = 1.dp.toPx()
    drawCircle(Color(0x28FFFFFF), radius, center)
    drawCircle(Color(0xE6000000), (radius - line / 2f).coerceAtLeast(line / 2f), center, style = Stroke(width = line))
    drawCircle(Color(0xF2FFFFFF), (radius - line * 1.5f).coerceAtLeast(line / 2f), center, style = Stroke(width = line))
}
