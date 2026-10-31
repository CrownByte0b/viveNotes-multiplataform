package com.vivenotes.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vivenotes.data.PictureLibrary
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeArm
import com.vivenotes.model.ink.ShapeAxis
import com.vivenotes.model.ink.arms
import com.vivenotes.model.ink.ends
import com.vivenotes.ui.canvas.LocalObjectGrab
import com.vivenotes.ui.canvas.PictureAsset
import com.vivenotes.ui.canvas.PictureContent
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.canvas.detectObjectDrag
import com.vivenotes.ui.canvas.drawDocumentShape
import com.vivenotes.ui.canvas.primeResizePointer
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.workspace.TABLE_GUTTER
import com.vivenotes.workspace.TableEdit
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.isPrimeObject
import com.vivenotes.workspace.primeHeight
import kotlinx.coroutines.flow.first

@Composable
internal fun CanvasPrimeObjects(
    viewport: () -> Rect,
    searchTarget: String? = null,
    state: WorkspaceState,
    workspaceState: State<WorkspaceState>,
    displayedOutlines: List<Outline>,
    groupBounds: Rect?,
    measuredTextHeights: MutableMap<String, Float>,
    richColors: RichTextColors,
    shapeInk: Color,
    pictures: PictureLibrary?,
    pictureAssets: Map<String, PictureAsset>,
    editorFocusRequester: FocusRequester,
    canvasFocusRequester: FocusRequester,
    onMoveObject: (String, Float, Float, Boolean) -> Unit,
    onSelectObject: (String) -> Unit,
    onResizeObjects: (Float, Float, Float, Float, Boolean) -> Unit,
    onFocusTextBox: (String) -> Unit,
    onBodyChange: (String, TextFieldValue) -> Unit,
    onTextShortcut: (KeyEvent) -> Boolean,
    onMoveShapeEnd: (String, Boolean, Float, Float, Boolean) -> Unit,
    onMoveShapeArm: (String, ShapeArm, Float, Boolean) -> Unit,
    heldTable: TableAxis?,
    onHoldTable: (TableAxis?) -> Unit,
    onTableEdit: (TableEdit) -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val currentMoveObject by rememberUpdatedState(onMoveObject)
    val grab = LocalObjectGrab.current
    val currentSelectObject by rememberUpdatedState(onSelectObject)
    val currentResizeObjects by rememberUpdatedState(onResizeObjects)
    val measurementKeys = remember(measuredTextHeights) { mutableStateMapOf<String, TableMeasurementKey>() }
    val visible by remember(viewport) { derivedStateOf { canvasDemandWindow(viewport()) } }
    val primeOutlines = remember(displayedOutlines) { displayedOutlines.filter { it.isPrimeObject() } }
    SideEffect {
        val ids = primeOutlines.mapTo(HashSet()) { it.id }
        measurementKeys.keys.filterNot { it in ids }.forEach(measurementKeys::remove)
    }
    primeOutlines.forEach { outline ->
        key(outline.id) {
        val measureKey = (outline as? Outline.Table)?.let {
            TableMeasurementKey(it.columns, it.rows, it.headerRow, it.headerColumn, it.inkOnly,
                density.density, density.fontScale)
        }
        val pinned = outline.id in state.selectedObjectIds || outline.id == searchTarget ||
            (outline is Outline.Table && outline.locate(state.focusedTextOutlineId.orEmpty()) != null)
        val knownHeight = if (outline is Outline.Table)
            measuredTextHeights[outline.id]?.takeIf { measurementKeys[outline.id] == measureKey }
            else outline.primeHeight()
        val cullPad = if (outline is Outline.Shape) outline.borderWidth / 2f + 2f else 0f
        if (shouldComposeCanvasItem(outline.x - cullPad, outline.y - cullPad,
                maxOf(24f, outline.width) + 2f * cullPad, knownHeight?.let { maxOf(24f, it) + 2f * cullPad }, visible, pinned)) {
        val selected = outline.id in state.selectedObjectIds
        val drawnShape = outline is Outline.Shape && outline.segments.isNotEmpty()
        // Leave half the stroke and rounded surface clip outside the actual geometry.
        val visualPad = if (drawnShape) (outline as Outline.Shape).borderWidth / 2f + 2f else 0f
        // A table's grid sits inside the gutter its (x, y) reserves for the row and column handles.
        val inset = if (outline is Outline.Table) TABLE_GUTTER else -visualPad
        val x = (outline.x + inset).dp
        val y = (outline.y + inset).dp
        val width = (outline.width + 2f * visualPad).coerceAtLeast(24f).dp
        val tableRows = if (outline is Outline.Table) measuredTableRows(outline, richColors) else emptyList()
        val objectHeight = if (outline is Outline.Table) tableRows.sum() else outline.primeHeight()
        if (outline is Outline.Table) SideEffect {
            measuredTextHeights[outline.id] = TABLE_GUTTER + objectHeight
            measurementKeys[outline.id] = measureKey!!
        }
        val height = (objectHeight + 2f * visualPad).coerceAtLeast(24f).dp
        // A table alone in the selection, or one being typed in, shows its handles.
        val tableActive = outline is Outline.Table && groupBounds == null && state.selectedInk == null &&
            state.selectedTextOutlineIds.isEmpty() && (state.selectedObjectIds == setOf(outline.id) ||
                outline.locate(state.focusedTextOutlineId.orEmpty()) != null)
        val label = when (outline) {
            is Outline.Shape -> outline.kind.label
            is Outline.Table -> Strings.table
            is Outline.Equation -> Strings.equation_value(outline.latex)
            is Outline.Image -> Strings.picture
            else -> Strings.object_label
        }
        // A picture sits on the page itself, so its transparent parts show the paper;
        // only a picture that cannot be drawn gets a plate (see PictureContent).
        val drawnPicture = outline is Outline.Image && pictures != null
        val drawnObject = drawnPicture || drawnShape || outline is Outline.Table || outline is Outline.Equation
        Surface(
            color = if (drawnObject) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small,
            tonalElevation = if (drawnPicture) 0.dp else if (selected && groupBounds == null) 3.dp else 1.dp,
            modifier = Modifier.offset(x, y).size(width, height)
                .testTag(WorkspaceTestTags.primeObject(outline.id))
                // A picture shows its own edges; only its selection is outlined. A table's is dashed, in its chrome.
                .then(if (drawnObject && (!selected || groupBounds != null || outline is Outline.Table)) Modifier else Modifier.border(
                    if (selected && groupBounds == null) 2.dp else 1.dp,
                    if (selected && groupBounds == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    MaterialTheme.shapes.small))
                .pointerInput(outline.id) {
                    var firstChange = true
                    detectObjectDrag(grab,
                        onDragStart = {
                            firstChange = true
                            if (outline.id !in workspaceState.value.selectedObjectIds)
                                currentSelectObject(outline.id)
                        },
                        moves = { !workspaceState.value.selectedObjectsLocked },
                        onDrag = { change, drag ->
                            change.consume()
                            currentMoveObject(outline.id,
                                with(density) { drag.x.toDp().value },
                                with(density) { drag.y.toDp().value }, firstChange)
                            firstChange = false
                        },
                    )
                }
                // An object is part of the page, not a button: a click selects it without a ripple over it.
                .clickable(interactionSource = null, indication = null) {
                    focusManager.clearFocus()
                    canvasFocusRequester.requestFocus()
                    if (outline.id !in workspaceState.value.selectedObjectIds) onSelectObject(outline.id)
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (outline is Outline.Image && pictures != null) {
                    PictureContent(pictureAssets[outline.attachmentId], outline.backgroundArgb)
                } else if (outline is Outline.Table) {
                    TableContent(outline, tableRows, shapeInk, state, richColors, editorFocusRequester,
                        onFocusTextBox, onBodyChange, onTextShortcut, heldTable?.takeIf { it.tableId == outline.id },
                        MaterialTheme.colorScheme.primary.copy(alpha = .22f))
                } else if (outline is Outline.Equation) {
                    EquationContent(outline.latex, outline.colorArgb?.let(::Color) ?: shapeInk, Modifier.fillMaxSize(), stretch = true)
                } else if (outline is Outline.Shape && drawnShape) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawDocumentShape(outline, outline.x - visualPad,
                            outline.y - visualPad, shapeInk)
                    }
                } else {
                    Text(label, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (selected && outline.lockGroup == null && groupBounds == null && state.selectedTextOutlineIds.isEmpty() &&
            outline is Outline.Shape && outline.kind.hasEnds) {
            outline.ends().forEach { end ->
                Box(Modifier.offset((end.x - 7f).dp, (end.y - 7f).dp).size(14.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .testTag("workspace-shape-end-${outline.id}-${end.atEnd}")
                    .pointerInput(outline.id, end.atEnd, state.selectedObjectsLocked) {
                        if (!state.selectedObjectsLocked) {
                            var total = Offset.Zero
                            var first = true
                            detectDragGestures(onDragStart = { total = Offset.Zero; first = true },
                                onDrag = { change, drag ->
                                    change.consume()
                                    total += drag
                                    onMoveShapeEnd(outline.id, end.atEnd,
                                        end.x + with(density) { total.x.toDp().value },
                                        end.y + with(density) { total.y.toDp().value }, first)
                                    first = false
                                })
                        }
                    })
            }
        }
        if (outline is Outline.Table && tableActive) {
            TableChrome(outline, tableRows, heldTable?.takeIf { it.tableId == outline.id },
                grabbable = outline.lockGroup == null,
                onHold = onHoldTable,
                onColumnWidth = { column, columnWidth, first ->
                    onTableEdit(TableEdit.ColumnWidth(outline.id, column, columnWidth, recordHistory = first))
                },
                onRowMinHeight = { row, minHeight, first ->
                    onTableEdit(TableEdit.RowMinHeight(outline.id, row, minHeight, recordHistory = first))
                },
                onMove = { dx, dy, first -> currentMoveObject(outline.id, dx, dy, first) },
                onScale = { sx, sy, first ->
                    if (outline.id !in workspaceState.value.selectedObjectIds) currentSelectObject(outline.id)
                    currentResizeObjects(outline.x, outline.y, sx, sy, first)
                })
        }
        if (selected && outline.lockGroup == null && groupBounds == null && state.selectedTextOutlineIds.isEmpty() &&
            outline !is Outline.Table && (outline !is Outline.Shape || !outline.kind.hasEnds)) {
            val corners = listOf(
                x to y, x + width to y, x to y + height, x + width to y + height,
            )
            corners.forEachIndexed { index, (cx, cy) ->
                val anchorX = if (index % 2 == 0) outline.x + outline.width else outline.x
                val anchorY = if (index < 2) outline.y + outline.primeHeight() else outline.y
                Box(Modifier.offset(cx - 7.dp, cy - 7.dp).size(14.dp)
                    .zIndex(1f)
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    .testTag(WorkspaceTestTags.objectCorner(outline.id, index))
                    .semantics { contentDescription = Strings.resize_value(label) }
                    .primeResizePointer(index)
                    .pointerInput(outline.id, index, state.selectedObjectsLocked) {
                        if (!state.selectedObjectsLocked) {
                            var total = Offset.Zero
                            var previousScaleX = 1f
                            var previousScaleY = 1f
                            var firstChange = true
                            detectDragGestures(
                                onDragStart = {
                                    total = Offset.Zero
                                    previousScaleX = 1f
                                    previousScaleY = 1f
                                    firstChange = true
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    total += drag
                                    val sx = (1f + with(density) { total.x.toDp().value } /
                                        outline.width.coerceAtLeast(1f) * (if (index % 2 == 0) -1 else 1))
                                        .coerceAtLeast(0.05f)
                                    val sy = (1f + with(density) { total.y.toDp().value } /
                                        outline.primeHeight().coerceAtLeast(1f) * (if (index < 2) -1 else 1))
                                        .coerceAtLeast(0.05f)
                                    currentResizeObjects(anchorX, anchorY,
                                        sx / previousScaleX, sy / previousScaleY, firstChange)
                                    previousScaleX = sx
                                    previousScaleY = sy
                                    firstChange = false
                                },
                            )
                        }
                    })
            }
        }
        if (selected && groupBounds == null && state.selectedTextOutlineIds.isEmpty() &&
            outline is Outline.Shape && !state.selectedObjectsLocked) {
            outline.arms().forEach { arm ->
                Box(Modifier.offset((arm.x - 5f).dp, (arm.y - 5f).dp).size(10.dp)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    .testTag("workspace-shape-arm-${outline.id}-${arm.segmentId}-${arm.atEnd}")
                    .pointerInput(outline.id, arm.segmentId, arm.atEnd) {
                        var total = Offset.Zero
                        var first = true
                        detectDragGestures(onDragStart = { total = Offset.Zero; first = true },
                            onDrag = { change, drag ->
                                change.consume()
                                total += drag
                                val delta = if (arm.axis == ShapeAxis.Horizontal) total.x else total.y
                                onMoveShapeArm(outline.id, arm,
                                    arm.along + with(density) { delta.toDp().value }, first)
                                first = false
                            })
                    })
            }
        }
        } else {
            val inset = if (outline is Outline.Table) TABLE_GUTTER else -cullPad
            val height = if (outline is Outline.Table) knownHeight!! - TABLE_GUTTER else knownHeight!!
            CanvasItemPlaceholder(WorkspaceTestTags.primeObject(outline.id), outline.x + inset, outline.y + inset,
                maxOf(24f, outline.width + 2f * cullPad), maxOf(24f, height + 2f * cullPad))
        }
        }
    }
}
