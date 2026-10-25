package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.ui.canvas.LocalObjectGrab
import com.vivenotes.ui.canvas.SelectionColorButton
import com.vivenotes.ui.canvas.detectObjectDrag
import com.vivenotes.ui.canvas.primeResizePointer
import com.vivenotes.ui.canvas.selectedCanvasBounds
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.ribbon.document.PictureObjectTools
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.ui.ribbon.draw.ShapeObjectTools
import com.vivenotes.workspace.TableEdit
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.primeHeight
import com.vivenotes.workspace.primeWidth

@Composable
internal fun CanvasGroupSelection(
    state: WorkspaceState,
    workspaceState: State<WorkspaceState>,
    groupBounds: Rect?,
    measuredTextHeights: Map<String, Float>,
    canvasFocusRequester: FocusRequester,
    onMoveSelection: (Float, Float, Boolean) -> Unit,
    onResizeObjects: (Float, Float, Float, Float, Boolean) -> Unit,
) {
    val currentState by workspaceState
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val currentMoveSelection by rememberUpdatedState(onMoveSelection)
    val currentResizeObjects by rememberUpdatedState(onResizeObjects)
    val grab = LocalObjectGrab.current
    if (groupBounds != null && state.selectedInk == null) {
        val padding = 6.dp
        val left = groupBounds.left.dp - padding
        val top = groupBounds.top.dp - padding
        val right = groupBounds.right.dp + padding
        val bottom = groupBounds.bottom.dp + padding
        val accent = MaterialTheme.colorScheme.primary
        Box(Modifier.offset(left, top).size(right - left, bottom - top)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRect(accent, topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke))
            }
            .testTag(WorkspaceTestTags.GroupSelectionFrame)
            .pointerInput(state.selectedPage?.id, state.selectedObjectIds, state.selectedTextOutlineIds,
                state.selectedObjectsLocked) {
                var firstChange = true
                detectObjectDrag(grab,
                    onDragStart = {
                        firstChange = true
                        focusManager.clearFocus()
                        canvasFocusRequester.requestFocus()
                    },
                    moves = { !currentState.selectedObjectsLocked },
                    onDrag = { change, drag ->
                        change.consume()
                        if (!currentState.selectedObjectsLocked) {
                            currentMoveSelection(
                                with(density) { drag.x.toDp().value },
                                with(density) { drag.y.toDp().value }, firstChange)
                            firstChange = false
                        }
                    },
                )
            })
        if (state.selectedTextOutlineIds.isEmpty() && !state.selectedObjectsLocked) {
            listOf(left to top, right to top, left to bottom, right to bottom)
                .forEachIndexed { index, (cx, cy) ->
                    Box(Modifier.offset(cx - 7.dp, cy - 7.dp).size(14.dp)
                        .zIndex(1f)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.5.dp, accent, CircleShape)
                        .testTag(WorkspaceTestTags.groupCorner(index))
                        .semantics { contentDescription = Strings.resize_selection }
                        .primeResizePointer(index)
                        .pointerInput(index, state.selectedObjectIds, state.selectedObjectsLocked) {
                            var total = Offset.Zero
                            var previousScaleX = 1f
                            var previousScaleY = 1f
                            var firstChange = true
                            var startBounds = groupBounds
                            detectDragGestures(
                                onDragStart = {
                                    total = Offset.Zero
                                    previousScaleX = 1f
                                    previousScaleY = 1f
                                    firstChange = true
                                    startBounds = selectedCanvasBounds(
                                        currentState.selectedPage?.document?.outlines.orEmpty(),
                                        currentState.selectedObjectIds, measuredTextHeights) ?: groupBounds
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    total += drag
                                    val dx = with(density) { total.x.toDp().value }
                                    val dy = with(density) { total.y.toDp().value }
                                    val sx = (1f + dx / startBounds.width.coerceAtLeast(1f) *
                                        (if (index % 2 == 0) -1 else 1)).coerceAtLeast(0.05f)
                                    val sy = (1f + dy / startBounds.height.coerceAtLeast(1f) *
                                        (if (index < 2) -1 else 1)).coerceAtLeast(0.05f)
                                    currentResizeObjects(
                                        if (index % 2 == 0) startBounds.right else startBounds.left,
                                        if (index < 2) startBounds.bottom else startBounds.top,
                                        sx / previousScaleX, sy / previousScaleY, firstChange)
                                    previousScaleX = sx
                                    previousScaleY = sy
                                    firstChange = false
                                },
                            )
                        })
                }
        }
    }
}

/**
 * The base object toolkit (Android `ObjectTooltip`): a dark pill centred above the selection, or
 * below it when there is no room above, with colour, a divider, copy and lock, then the kind's own
 * actions, then delete.
 */
@Composable
internal fun CanvasObjectToolkit(
    state: WorkspaceState,
    groupBounds: Rect?,
    measuredTextHeights: Map<String, Float>,
    shapeInk: Color,
    heldTable: TableAxis?,
    onColorObjects: (Int, Boolean) -> Unit,
    onTableEdit: (TableEdit) -> Unit,
    onReleaseTable: () -> Unit,
    onEquationChange: (com.vivenotes.workspace.PendingDrawEquation) -> Unit,
    onShapeLineType: (LineType) -> Unit,
    onShapeWidth: (Int) -> Unit,
    onShapeFill: (Int?) -> Unit,
    onImageBackground: (Int?) -> Unit,
    onCopyObjects: () -> Unit,
    onToggleObjectLock: () -> Unit,
    onDeleteObjects: () -> Unit,
) {
    if (state.selectedObjectIds.isNotEmpty() && state.selectedInk == null) {
        val selected = state.selectedPrimeObjects.firstOrNull()
        if (selected != null) {
            val bounds = groupBounds ?: Rect(selected.x, selected.y, selected.x + selected.primeWidth(),
                selected.y + (measuredTextHeights[selected.id] ?: selected.primeHeight()))
            var toolkitSize by remember { mutableStateOf(IntSize.Zero) }
            Surface(color = Color(0xFF202124), contentColor = Color(0xFFE8EAED),
                shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp,
                modifier = Modifier
                    .offset {
                        val gap = 12.dp.roundToPx()
                        val above = bounds.top.dp.roundToPx() - toolkitSize.height - gap
                        IntOffset((bounds.center.x.dp.roundToPx() - toolkitSize.width / 2).coerceAtLeast(0),
                            if (above >= 0) above else bounds.bottom.dp.roundToPx() + gap)
                    }
                    .onSizeChanged { toolkitSize = it }
                    .testTag(WorkspaceTestTags.ObjectToolkit)) {
                Row(Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val colorSource = state.selectedColorableObject
                    if (colorSource != null) {
                        val swatch = when (colorSource) {
                            is Outline.Shape -> Color(com.vivenotes.data.automaticColorOr(
                                colorSource.borderArgb, colorSource.borderFollowsTheme, shapeInk.toArgb()))
                            is Outline.Table -> Color(com.vivenotes.data.automaticColorOr(
                                colorSource.borderArgb, colorSource.borderFollowsTheme, shapeInk.toArgb()))
                            is Outline.Equation -> colorSource.colorArgb?.let(::Color) ?: shapeInk
                            else -> Color.Black
                        }
                        SelectionColorButton(swatch, shapeInk, onColorObjects)
                        Spacer(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp)
                            .background(Color(0xFF5F6368)).testTag(WorkspaceTestTags.ObjectColorDivider))
                    }
                    TooltipIconButton(Strings.copy_selection, onClick = onCopyObjects, tooltipPosition = TooltipAnchorPosition.Above,
                        modifier = Modifier.size(32.dp).testTag(WorkspaceTestTags.ObjectCopy)) {
                        Icon(DocumentSymbols.ContentCopy, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                    }
                    if (state.canLockSelectedObjects) {
                        TooltipIconButton(if (state.selectedObjectsLocked) Strings.unlock_selection else Strings.lock_selection,
                            onClick = onToggleObjectLock, tooltipPosition = TooltipAnchorPosition.Above,
                            modifier = Modifier.size(32.dp).testTag(WorkspaceTestTags.ObjectLock)) {
                            Icon(if (state.selectedObjectsLocked) ObjectSymbols.Lock
                                else ObjectSymbols.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    if (state.selectedObjectIds.size == 1 && state.selectedTextOutlineIds.isEmpty()) {
                        if (selected is Outline.Table) TableObjectTools(selected, heldTable, onTableEdit, onReleaseTable)
                        if (selected is Outline.Equation) EquationObjectTools(selected, onEquationChange)
                    }
                    if (selected is Outline.Shape && state.selectedObjectIds.size == 1 &&
                        state.selectedTextOutlineIds.isEmpty()) {
                        ShapeObjectTools(selected, onShapeLineType, onShapeWidth, onShapeFill)
                    }
                    state.selectedImages.takeIf { it.isNotEmpty() }?.let { images ->
                        PictureObjectTools(images.map { it.backgroundArgb }.distinct().singleOrNull(), onImageBackground)
                    }
                    TooltipIconButton(Strings.delete_selection, onClick = onDeleteObjects, tooltipPosition = TooltipAnchorPosition.Above,
                        modifier = Modifier.size(32.dp).testTag(WorkspaceTestTags.ObjectDelete)) {
                        Icon(ObjectSymbols.Delete, contentDescription = null,
                            tint = Color(0xFFFFA8A8),
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
