package com.vivenotes.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.Ruler
import com.vivenotes.ui.canvas.TextMenuRequest
import com.vivenotes.ui.ribbon.draw.TableAxis
import com.vivenotes.workspace.CanvasViewport
import com.vivenotes.workspace.PageSummary
import com.vivenotes.workspace.WorkspaceState

/** Canvas interaction values keep independent reset keys across page/tool/zoom changes. */
internal class CanvasInteractionState(
    val viewportPxState: MutableState<IntSize>,
    val pendingViewportState: MutableState<CanvasViewport?>,
    val pastePointState: MutableState<Offset?>,
    val rulerPlacementState: MutableState<Ruler?>,
    val lassoPath: LassoPathBuffer,
    val lassoState: MutableState<Pair<Offset, Offset>?>,
    val inkDragOffsetState: MutableState<Offset>,
    val inkMoveBusyState: MutableState<Boolean>,
    val inkToolkitBoundsInViewState: MutableState<Rect?>,
    val spaceDraftState: MutableState<SpaceCut?>,
    val shapeDraftState: MutableState<Pair<Offset, Offset>?>,
    val panActiveState: MutableState<Boolean>,
    val panAnchorState: MutableState<Offset>,
    val panPointerState: MutableState<Offset>,
    val titleValueState: MutableState<TextFieldValue>,
    val keyboardTextBoxState: MutableState<String?>,
    val textMenuState: MutableState<TextMenuRequest?>,
    val heldTableAxisState: MutableState<TableAxis?>,
    val reachedXState: MutableIntState,
    val reachedYState: MutableIntState
) {
    var viewportPx by viewportPxState
    var pendingViewport by pendingViewportState
    var pastePoint by pastePointState
    var rulerPlacement by rulerPlacementState
    var lasso by lassoState
    var inkDragOffset by inkDragOffsetState
    var inkMoveBusy by inkMoveBusyState
    var inkToolkitBoundsInView by inkToolkitBoundsInViewState
    var spaceDraft by spaceDraftState
    var shapeDraft by shapeDraftState
    var panActive by panActiveState
    var panAnchor by panAnchorState
    var panPointer by panPointerState
    var titleValue by titleValueState
    var keyboardTextBox by keyboardTextBoxState
    var textMenu by textMenuState
    var heldTableAxis by heldTableAxisState
    var reachedX by reachedXState
    var reachedY by reachedYState
}

@Composable
internal fun rememberCanvasInteractionState(page: PageSummary?, state: WorkspaceState, zoom: Float): CanvasInteractionState {
    val viewportPxState = remember { mutableStateOf(IntSize.Zero) }
    val pendingViewportState = remember(page?.id) { mutableStateOf<CanvasViewport?>(null) }
    val pastePointState = remember(page?.id) { mutableStateOf<Offset?>(null) }
    val rulerPlacementState = remember(page?.id, state.rulerVisible) { mutableStateOf<Ruler?>(null) }
    val lassoPathState = remember(page?.id, state.objectLassoArmed, zoom) { LassoPathBuffer() }
    val lassoState = remember(page?.id) { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val inkDragOffsetState = remember(page?.id, state.selectedInk, zoom) { mutableStateOf(Offset.Zero) }
    val inkMoveBusyState = remember(page?.id) { mutableStateOf(false) }
    val inkToolkitBoundsInViewState = remember(page?.id) { mutableStateOf<Rect?>(null) }
    val spaceDraftState = remember(page?.id, state.drawPlacement, zoom) { mutableStateOf<SpaceCut?>(null) }
    val shapeDraftState = remember(page?.id) { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val panActiveState = remember(page?.id) { mutableStateOf(false) }
    val panAnchorState = remember(page?.id) { mutableStateOf(Offset.Zero) }
    val panPointerState = remember(page?.id) { mutableStateOf(Offset.Zero) }
    val titleValueState = remember(page?.id) { mutableStateOf(TextFieldValue(page?.title.orEmpty())) }
    val keyboardTextBoxState = remember(page?.id) { mutableStateOf<String?>(null) }
    val textMenuState = remember(page?.id) { mutableStateOf<TextMenuRequest?>(null) }
    val heldTableAxisState = remember(page?.id) { mutableStateOf<TableAxis?>(null) }
    val reachedXState = remember(page?.id) { mutableIntStateOf(0) }
    val reachedYState = remember(page?.id) { mutableIntStateOf(0) }
    return CanvasInteractionState(
        viewportPxState,
        pendingViewportState,
        pastePointState,
        rulerPlacementState,
        lassoPathState,
        lassoState,
        inkDragOffsetState,
        inkMoveBusyState,
        inkToolkitBoundsInViewState,
        spaceDraftState,
        shapeDraftState,
        panActiveState,
        panAnchorState,
        panPointerState,
        titleValueState,
        keyboardTextBoxState,
        textMenuState,
        heldTableAxisState,
        reachedXState,
        reachedYState,
    )
}
