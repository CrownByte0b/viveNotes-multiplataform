package com.vivenotes.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.PageSpace
import com.vivenotes.model.PageStyle
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.ink.Ruler
import com.vivenotes.model.ink.RulerPoint
import com.vivenotes.ui.canvas.PageExtent
import com.vivenotes.ui.canvas.selectedCanvasBounds
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.workspace.DrawPlacementTool
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.titleFloor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CanvasGestureDrafts(
    val space: MutableState<SpaceCut?>,
    val shape: MutableState<Pair<Offset, Offset>?>,
    val path: LassoPathBuffer,
    val rectangle: MutableState<Pair<Offset, Offset>?>,
    val inkOffset: MutableState<Offset>,
    val paste: MutableState<Offset?>,
)

internal suspend fun PointerInputScope.detectCanvasSelectionGestures(
    workspaceState: State<WorkspaceState>,
    rulerState: State<Ruler?>,
    fingerState: State<Boolean>,
    extentState: State<PageExtent?>,
    inkMoveBusyState: State<Boolean>,
    drafts: CanvasGestureDrafts,
    density: Density,
    zoom: Float,
    focusManager: FocusManager,
    canvasFocusRequester: FocusRequester,
    inkMoveScope: CoroutineScope,
    measuredTextHeights: Map<String, Float>,
    toPage: (Offset) -> Offset,
    hitsContent: (Offset, WorkspaceState) -> Boolean,
    hitsSelectedTransform: (Offset, WorkspaceState) -> Boolean,
    hitsSelectionToolbar: (Offset, WorkspaceState, Float) -> Boolean,
    currentSelectInk: (InkSelection) -> Unit,
    finishInkMove: (InkSelection, Offset) -> Unit,
    currentSpaceInsertion: (SpaceInsertion) -> Unit,
    currentCreateShape: (Float, Float, Float, Float) -> Unit,
    currentLassoSelect: (List<InkSample>, Map<String, Float>) -> Unit,
    currentSelectObjects: (Float, Float, Float, Float) -> Unit,
    currentDrawObject: (Float, Float) -> Unit,
    currentCreate: (Float, Float) -> Unit,
    currentClearCanvasFocus: () -> Unit,
) {
    val currentState by workspaceState
    val currentRuler by rulerState
    val currentDrawWithFinger by fingerState
    val currentExtent by extentState
    val inkMoveBusy by inkMoveBusyState
    var spaceDraft by drafts.space
    var shapeDraft by drafts.shape
    val lassoPath = drafts.path
    var lasso by drafts.rectangle
    var inkDragOffset by drafts.inkOffset
    var pastePoint by drafts.paste
    var previousTapTime = 0L
    var previousTapPoint = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.isConsumed || currentState.inkTool != null) {
            waitForUpOrCancellation(pass = PointerEventPass.Initial)
            return@awaitEachGesture
        }
        val start = toPage(down.position)
        if (currentRuler?.let { it.grabs(RulerPoint(start.x, start.y)) &&
            (down.type == PointerType.Touch || it.grabsDial(RulerPoint(start.x, start.y))) } == true) {
            waitForUpOrCancellation(pass = PointerEventPass.Initial)
            return@awaitEachGesture
        }
        if (currentState.drawPlacement == DrawPlacementTool.InsertSpace &&
            (down.type != PointerType.Touch || currentDrawWithFinger)) {
            val floor = currentState.selectedPage?.document?.style?.titleFloor ?: 0f
            val origin = Offset(start.x.coerceAtLeast(0f), start.y.coerceAtLeast(floor))
            var axis: PageSpace.Axis? = null
            var cut: SpaceCut? = null
            down.consume()
            var cancelled = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) { cancelled = true; break }
                val at = toPage(change.position).let { Offset(it.x.coerceAtLeast(0f), it.y.coerceAtLeast(floor)) }
                val delta = at - origin
                if (axis == null && maxOf(kotlin.math.abs(delta.x), kotlin.math.abs(delta.y)) * density.density * zoom > viewConfiguration.touchSlop)
                    axis = if (kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)) PageSpace.Axis.Horizontal else PageSpace.Axis.Vertical
                axis?.let { chosen ->
                    cut = SpaceCut(chosen, if (chosen == PageSpace.Axis.Horizontal) origin.x else origin.y,
                        if (chosen == PageSpace.Axis.Horizontal) delta.x else delta.y)
                    spaceDraft = cut
                }
                change.consume()
            } while (currentEvent.changes.any { it.id == down.id && it.pressed })
            val request = cut
            val captured = currentState
            if (!cancelled && request != null) inkMoveScope.launch {
                val insertion = withContext(Dispatchers.Default) { prepareSpaceInsertion(captured, request) }
                insertion?.let(currentSpaceInsertion)
            }
            spaceDraft = null
            return@awaitEachGesture
        }
        val titleFloor = currentState.selectedPage?.document?.style?.titleFloor ?: PageStyle.TITLE_BAND_DP
        val onObjectToolbar = hitsSelectionToolbar(start, currentState, 60f)
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) {
            waitForUpOrCancellation(pass = PointerEventPass.Initial)
            return@awaitEachGesture
        }
        val inkHit = if (start.y >= titleFloor && !onObjectToolbar && !currentState.objectLassoArmed &&
            !currentState.textToolArmed && !currentState.shapeToolArmed && currentState.drawPlacement == null && !hitsSelectedTransform(start, currentState))
            currentState.selectedPage?.ink?.let { ink -> ink.geometry?.selectAt(ink, start.x, start.y) }
            else null
        val canMoveInk = currentState.selectedPage?.let { it.editable && it.inkReady } == true &&
            !currentState.notebookTransfer.running && !currentState.filePane.busy && !inkMoveBusy
        if (inkHit != null) {
            down.consume()
            focusManager.clearFocus()
            canvasFocusRequester.requestFocus()
            currentSelectInk(inkHit)
        }
        // A finger drags these tools only when fingers may draw; otherwise its drag moves
        // the page (`canvasTouchGestures`), and on an empty page it never draws a marquee.
        val finger = down.type == PointerType.Touch
        val fingerTool = !finger || currentDrawWithFinger
        val shaping = fingerTool && currentState.shapeToolArmed && start.y >= titleFloor &&
            currentState.selectedPage?.editable == true && !onObjectToolbar &&
            !hitsSelectedTransform(start, currentState)
        val marquee = !onObjectToolbar && currentState.drawPlacement == null && inkHit == null && (!finger || (currentDrawWithFinger && currentState.objectLassoArmed)) &&
            !hitsSelectedTransform(start, currentState) &&
            (currentState.objectLassoArmed ||
            (!currentState.textToolArmed && !currentState.shapeToolArmed && start.y >= titleFloor &&
                !hitsContent(start, currentState)))
        val freehand = marquee && currentState.objectLassoArmed
        if (freehand) lassoPath.start(start)
        var last = down
        // Taken by a pinch: a second finger made this gesture the page's, not a tap or a drag.
        var pinched = false
        try {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (finger && event.changes.count { it.pressed } > 1 && change.isConsumed) pinched = true
            last = change
            if (inkHit != null && canMoveInk && (change.position - down.position).getDistance() >= 12.dp.toPx()) {
                change.consume()
                val delta = toPage(change.position) - start
                inkDragOffset = Offset(delta.x.coerceAtLeast(-inkHit.bounds.left), delta.y.coerceAtLeast(-inkHit.bounds.top))
            }
            if (shaping && (change.position - down.position).getDistance() >= 12.dp.toPx()) {
                change.consume()
                shapeDraft = start to toPage(change.position)
            }
            if (marquee &&
                ((change.position - down.position).getDistance() >= 12.dp.toPx() || (freehand && lassoPath.size > 1))) {
                change.consume()
                if (freehand) {
                    change.historical.forEach { lassoPath.append(toPage(it.position)) }
                    lassoPath.append(toPage(change.position))
                } else lasso = start to toPage(change.position)
            }
        } while (last.pressed)
        } finally {
            if (last.pressed) lassoPath.clear()
        }
        val distance = if (freehand) lassoPath.distanceFromStart() * density.density * zoom
            else (last.position - down.position).getDistance()
        val completedLoop = if (freehand && !pinched && distance >= 12.dp.toPx()) lassoPath.snapshot() else emptyList()
        lassoPath.clear()
        if (pinched) {
            shapeDraft = null
            lasso = null
            inkDragOffset = Offset.Zero
        } else if (inkHit != null) {
            if (distance >= 12.dp.toPx() && canMoveInk)
                finishInkMove(inkHit, inkDragOffset)
            else inkDragOffset = Offset.Zero
        } else if (shaping) {
            val end = if (distance >= 12.dp.toPx()) toPage(last.position) else
                Offset(start.x + Outline.Shape.DEFAULT_WIDTH,
                    start.y + Outline.Shape.DEFAULT_HEIGHT)
            val style = currentState.selectedPage?.document?.style
            if (!down.isConsumed && style != null &&
                currentExtent?.canPlaceAt(style, start.x, start.y) == true)
                currentCreateShape(start.x, start.y, end.x, end.y)
            shapeDraft = null
        } else if (marquee && distance >= 12.dp.toPx()) {
            val a = start
            val b = toPage(last.position)
            if (freehand) currentLassoSelect(completedLoop.map { InkSample(it.x, it.y) }, measuredTextHeights.toMap()) else currentSelectObjects(
                minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
            lasso = null
        } else if (distance < 12.dp.toPx()) {
            // A press a control took — a toolkit button reaching past its object — is
            // that control's click, not a tap on the page: the page tap would clear the
            // selection the button acts on. Asked of the press rather than the release
            // because the press has finished dispatching by now, while this release may
            // reach the page before or after the button, depending on scheduling.
            val handled = down.isConsumed
            val point = toPage(last.position)
            val x = point.x
            val y = point.y
            val insideGroup = selectedCanvasBounds(
                currentState.selectedPage?.document?.outlines.orEmpty(),
                currentState.selectedObjectIds + currentState.selectedTextOutlineIds,
                measuredTextHeights, currentState.selectedInk)?.let { bounds ->
                x in (bounds.left - 6f)..(bounds.right + 6f) &&
                    y in (bounds.top - 6f)..(bounds.bottom + 6f)
            } == true
            if (!handled && insideGroup) {
                focusManager.clearFocus()
                canvasFocusRequester.requestFocus()
            } else if (!handled && !hitsContent(point, currentState) && y >= titleFloor) {
                focusManager.clearFocus()
                canvasFocusRequester.requestFocus()
                // Beside a sheet that bounds the page there is nowhere to put anything.
                val style = currentState.selectedPage?.document?.style
                val placeable = style != null && currentExtent?.canPlaceAt(style, x, y) == true
                val isDouble = placeable && !currentState.canvasClipboard.isEmpty &&
                    last.uptimeMillis - previousTapTime in 1..350 &&
                    (point - previousTapPoint).getDistance() < 32f
                if (isDouble) {
                    pastePoint = point
                } else {
                    pastePoint = null
                    if (currentState.drawPlacement == DrawPlacementTool.Table || currentState.drawPlacement == DrawPlacementTool.Equation) {
                        if (placeable) currentDrawObject(x, y)
                    } else if (currentState.textToolArmed && placeable) currentCreate(x, y)
                    else currentClearCanvasFocus()
                }
                previousTapTime = last.uptimeMillis
                previousTapPoint = point
            }
        }
    }
}
