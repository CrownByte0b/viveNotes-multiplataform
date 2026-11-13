package com.vivenotes.ui.shell

import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.vivenotes.data.rememberTextClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.withTimeoutOrNull
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.ink.InkSelectionChange
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.Ruler
import com.vivenotes.model.ink.RulerPoint
import com.vivenotes.model.ink.ShapeArm
import com.vivenotes.model.ink.StraightLine
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentKind
import com.vivenotes.richtext.LinkTarget
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.ApplyCanvasCursorWhilePressed
import com.vivenotes.ui.canvas.CanvasCursor
import com.vivenotes.ui.canvas.CanvasPointer
import com.vivenotes.ui.canvas.CanvasTouchActions
import com.vivenotes.ui.canvas.FingerRole
import com.vivenotes.ui.canvas.InkLayer
import com.vivenotes.ui.canvas.InkSelectionFrame
import com.vivenotes.ui.canvas.InkSelectionToolkit
import com.vivenotes.ui.canvas.InsertCursorBadge
import com.vivenotes.ui.canvas.LocalClipboardHtml
import com.vivenotes.ui.canvas.LocalObjectGrab
import com.vivenotes.ui.canvas.ObjectContextMenu
import com.vivenotes.ui.canvas.ObjectGrab
import com.vivenotes.ui.canvas.PageExtent
import com.vivenotes.ui.canvas.PreviewLayout
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.canvas.RulerLayer
import com.vivenotes.ui.canvas.LaserLayer
import com.vivenotes.ui.canvas.TextCaretKeys
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.canvas.CanvasClipboardActions
import com.vivenotes.ui.canvas.CanvasPasteProgress
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.keyboard.LocalKeyBindings
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.ui.canvas.canvasPointerIcon
import com.vivenotes.ui.canvas.canvasTouchGestures
import com.vivenotes.ui.canvas.contentEdge
import com.vivenotes.ui.canvas.detectRulerGestures
import com.vivenotes.ui.canvas.documentExtent
import com.vivenotes.ui.canvas.followPointer
import com.vivenotes.ui.canvas.pendingInsert
import com.vivenotes.ui.canvas.selectedCanvasBounds
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.drop.FileDropHandler
import com.vivenotes.ui.drop.FileDropHighlight
import com.vivenotes.ui.drop.rememberPageFileDrop
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.workspace.CanvasViewport
import com.vivenotes.workspace.InkPenSettings
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.ObjectCommand
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.RulerKind
import com.vivenotes.workspace.TableEdit
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.isPrimeObject
import com.vivenotes.workspace.isUnwritten
import com.vivenotes.workspace.movedBy
import com.vivenotes.workspace.primeHeight
import com.vivenotes.workspace.primeWidth
import com.vivenotes.workspace.titleFloor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vivenotes.diagnostics.DebugLog

@Composable
internal fun PageCanvas(
    state: WorkspaceState,
    searchHit: ContentHit?,
    searchRevealTick: Int,
    searchImageHighlight: com.vivenotes.model.ocr.PictureHighlight? = null,
    pictureHighlights: () -> Map<String, com.vivenotes.model.ocr.PictureHighlight> = { emptyMap() },
    pictures: PictureLibrary?,
    thumbnails: VideoThumbnailSource?,
    canvasOrigin: CanvasOrigin,
    keyboardPaste: CanvasKeyboardPaste,
    canvasClipboard: CanvasClipboardActions,
    canvasControl: CanvasViewControl,
    /** What an import placed from outside the canvas reads from it, and where it asks it to scroll. */
    placement: CanvasPlacement = remember { CanvasPlacement() },
    zoom: Float,
    /** Settings → Hardware: a finger uses the armed drawing tool rather than moving the page. */
    drawWithFinger: Boolean,
    onZoomChange: (Float) -> Unit,
    canvasDark: Boolean,
    showMargins: Boolean,
    /** A key in the text box being edited: true when a shortcut used it. */
    onTextShortcut: (KeyEvent) -> Boolean,
    editorFocusRequester: FocusRequester,
    canvasFocusRequester: FocusRequester,
    onTitleChange: (String) -> Unit,
    onFocusTextBox: (String) -> Unit,
    onClearCanvasFocus: () -> Unit,
    onInkEdit: (com.vivenotes.data.InkEdit, com.vivenotes.model.ink.InkPage) -> Unit,
    onSelectInk: (InkSelection) -> Unit,
    onInkSelectionChange: (InkSelectionChange) -> Unit,
    onInkGrouping: (InkSelectionChange) -> Unit,
    inkMathAvailable: Boolean,
    onInkMath: (InkSelection) -> Unit,
    onInkRecolor: (InkSelection, Int, Boolean, InkSelectionChange?) -> Unit,
    onPreparedPaste: (WorkspaceState, WorkspaceState) -> Unit,
    onCreateShape: (Float, Float, Float, Float) -> Unit,
    onStraightenStroke: (StraightLine, InkPenSettings) -> Unit = { _, _ -> },
    onCreateTextBox: (Float, Float) -> Unit,
    onPlaceEmptyTextBox: (String, Float, Float) -> Unit,
    onMoveTextBox: (String, Float, Float, Boolean) -> Unit,
    onMoveSelection: (Float, Float, Boolean) -> Unit,
    onResizeTextBox: (String, Float?, Float?, Boolean) -> Unit,
    onDeleteTextBox: (String) -> Unit,
    onPasteCanvas: (Float, Float) -> Unit,
    onSelectObject: (String) -> Unit,
    onMoveObject: (String, Float, Float, Boolean) -> Unit,
    onResizeObjects: (Float, Float, Float, Float, Boolean) -> Unit,
    onCopyObjects: () -> Unit,
    onDeleteObjects: () -> Unit,
    /** The prime object right-click menu's commands: on what is selected, or on this one object. */
    onObjectCommand: (ObjectCommand, String?) -> Unit = { _, _ -> },
    onToggleObjectLock: () -> Unit,
    onColorObjects: (Int, Boolean) -> Unit,
    onShapeLineType: (LineType) -> Unit,
    onShapeWidth: (Int) -> Unit,
    onShapeFill: (Int?) -> Unit,
    onMoveShapeEnd: (String, Boolean, Float, Float, Boolean) -> Unit,
    onMoveShapeArm: (String, ShapeArm, Float, Boolean) -> Unit,
    onSelectObjectsInRect: (Float, Float, Float, Float) -> Unit,
    onSelectObjectsInLasso: (List<InkSample>, Map<String, Float>) -> Unit = { _, _ -> },
    onDrawObject: (Float, Float) -> Unit = { _, _ -> },
    onSpaceInsertion: (SpaceInsertion) -> Unit = {},
    onTableEdit: (TableEdit) -> Unit = {},
    graphActions: GraphToolkitActions? = null,
    graphRewrites: Map<String, GraphRewritePreview> = emptyMap(),
    onMutateGraph: (Outline.Graph, com.vivenotes.graph.GraphDrag) -> Unit = { _, _ -> },
    onTraceGraph: (String, Double) -> Unit = { _, _ -> },
    onGraphArea: (String, com.vivenotes.model.GraphInterval) -> Unit = { _, _ -> },
    onGraphParameter: (String, Int, Double) -> Unit = { _, _, _ -> },
    onEditGraphSliders: (String) -> Unit = {},
    graphableFormulas: GraphableFormulas = GraphableFormulas.None,
    onGraphEquation: (Outline.Equation) -> Unit = {},
    onOpenEquationInMath: ((Outline.Equation) -> Unit)? = null,
    onEquationChange: (com.vivenotes.workspace.PendingDrawEquation) -> Unit = {},
    onImageBackground: (Int?) -> Unit = {},
    onToggleTodo: (outlineId: String, blockId: String) -> Unit,
    /** Applies a text command and gives the keyboard back to the text box. */
    onTextCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onTextLinkRequest: (String, TextSelection, LinkTarget) -> Unit,
    /** A right-click in a text box: edit that box, with this range selected. */
    onOpenTextMenu: (String, TextSelection) -> Unit,
    /** Edit a text box with this range selected, and give it the keyboard. */
    onEditTextAt: (String, TextSelection) -> Unit,
    onBodyChange: (String, TextFieldValue) -> Unit,
    /** What the page takes when files are dropped on it from outside the app; none, no drops. */
    fileDrops: List<FileDropHandler> = emptyList(),
    debugLog: DebugLog = DebugLog(),
    modifier: Modifier = Modifier,
) {
    val page = state.selectedPage
    val interaction = rememberCanvasInteractionState(page, state, zoom)
    val currentEditTextAt by rememberUpdatedState(onEditTextAt)
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    // Zoom is this device's View setting, kept across pages; read through this in pointer handlers,
    // which outlive the composition that created them.
    val zoomState = rememberUpdatedState(zoom)
    val visiblePageWindow: () -> Rect = remember(horizontalScroll, verticalScroll, zoomState, interaction.viewportPxState, density) {
        {
            val scale = zoomState.value * density.density
            val left = horizontalScroll.value / scale
            val top = verticalScroll.value / scale
            Rect(left, top, left + interaction.viewportPx.width / scale, top + interaction.viewportPx.height / scale)
        }
    }
    val pictureAssets = rememberViewportPictureAssets(page?.id, page?.document?.outlines.orEmpty(), pictures,
        visiblePageWindow, density.density * zoom)
    val currentZoomChange by rememberUpdatedState(onZoomChange)
    val zoomTracking = remember { ZoomTracking(zoom) }
    val ruler = if (state.rulerVisible && interaction.viewportPx.width > 0 && interaction.viewportPx.height > 0) {
        val center = interaction.rulerPlacement ?: Ruler(
            (horizontalScroll.value + interaction.viewportPx.width / 2f) / zoom / density.density,
            (verticalScroll.value + interaction.viewportPx.height / 2f) / zoom / density.density,
            0f, state.inkSettings.ruler.kind, state.inkSettings.ruler.diameterDp.toFloat())
        center.copy(kind = state.inkSettings.ruler.kind, sizeDp = if (state.inkSettings.ruler.kind == RulerKind.Straight)
            kotlin.math.hypot(interaction.viewportPx.width.toFloat(), interaction.viewportPx.height.toFloat()) / zoom / density.density * 2f
            else state.inkSettings.ruler.diameterDp.toFloat())
    } else null
    SideEffect { if (interaction.rulerPlacement == null && ruler != null) interaction.rulerPlacement = ruler }
    val currentRulerState = rememberUpdatedState(ruler)
    val currentRuler by currentRulerState
    val inkMoveScope = rememberCoroutineScope()
    val currentSelectInk by rememberUpdatedState(onSelectInk)
    val currentInkSelectionChange by rememberUpdatedState(onInkSelectionChange)
    val currentInkGrouping by rememberUpdatedState(onInkGrouping)
    val currentInkRecolor by rememberUpdatedState(onInkRecolor)
    val currentPreparedPaste by rememberUpdatedState(onPreparedPaste)
    fun finishInkMove(selection: InkSelection, offset: Offset) {
        if (interaction.inkMoveBusy) return
        interaction.inkMoveBusy = true
        inkMoveScope.launch {
            try {
                val change = withContext(Dispatchers.Default) { selection.move(offset.x, offset.y) }
                if (change != null) currentInkSelectionChange(change)
            } finally { interaction.inkDragOffset = Offset.Zero; interaction.inkMoveBusy = false }
        }
    }
    val currentSpaceInsertion by rememberUpdatedState(onSpaceInsertion)
    val currentDrawObject by rememberUpdatedState(onDrawObject)
    val objectGrab = remember(page?.id) { ObjectGrab() }
    val heldCursor = when {
        interaction.panActive -> CanvasCursor.AllScroll
        objectGrab.held -> CanvasCursor.Grabbing
        else -> null
    }
    ApplyCanvasCursorWhilePressed(heldCursor)
    val canvasPointer = remember { CanvasPointer() }
    val currentCreate by rememberUpdatedState(onCreateTextBox)
    val currentPlaceEmptyTextBox by rememberUpdatedState(onPlaceEmptyTextBox)
    val currentCreateShape by rememberUpdatedState(onCreateShape)
    val currentClearCanvasFocus by rememberUpdatedState(onClearCanvasFocus)
    val currentPaste by rememberUpdatedState(onPasteCanvas)
    val currentSelectObjects by rememberUpdatedState(onSelectObjectsInRect)
    val currentLassoSelect by rememberUpdatedState(onSelectObjectsInLasso)
    val currentMoveSelection by rememberUpdatedState(onMoveSelection)
    val currentDrawWithFingerState = rememberUpdatedState(drawWithFinger)
    val currentDrawWithFinger by currentDrawWithFingerState
    val flingDecay = rememberSplineBasedDecay<Float>()
    val touchScope = rememberCoroutineScope()
    val touchViewport = remember(page?.id, flingDecay) {
        CanvasTouchViewport(horizontalScroll, verticalScroll, zoom = { zoomTracking.applied },
            onZoom = { next ->
                // The pinch scrolls for its own zoom: recorded as applied, the ribbon's anchoring
                // below stands down when it arrives.
                zoomTracking.applied = next
                interaction.pendingViewport = null
                currentZoomChange(next)
            }, scope = touchScope, decay = flingDecay)
    }
    val currentStateValue = rememberUpdatedState(state)
    val currentState by currentStateValue
    val measuredTextHeights = remember(page?.id) { mutableStateMapOf<String, Float>() }
    val textMeasurementCache = remember(state.selectedNotebookId) { TextMeasurementCache() }
    val notebookPages = remember(state.notebooks, state.selectedNotebookId) {
        state.notebooks.firstOrNull { it.id == state.selectedNotebookId }?.sections
            ?.flatMap { it.pages }?.mapTo(HashSet()) { it.id }.orEmpty()
    }
    SideEffect { textMeasurementCache.retainPages(notebookPages) }
    val textLayouts = remember(page?.id) { mutableStateMapOf<String, TextLayoutResult>() }
    // How each box's preview is laid out, while it is drawn in place of the box's editor.
    val previewLayouts = remember(page?.id) { mutableMapOf<String, PreviewLayout>() }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(page?.title) {
        val title = page?.title.orEmpty()
        if (interaction.titleValue.text != title) interaction.titleValue = TextFieldValue(title,
            TextRange(interaction.titleValue.selection.start.coerceAtMost(title.length), interaction.titleValue.selection.end.coerceAtMost(title.length)))
    }
    val imageSearchFocus = remember(page?.id) { mutableStateOf<com.vivenotes.ui.search.ImageSearchFocus?>(null) }
    LaunchedEffect(searchHit, searchImageHighlight, searchRevealTick, page?.id, page?.content) {
        imageSearchFocus.value = null
        if (searchHit?.unit?.pageId == page?.id && searchImageHighlight != null && page?.editable == true) {
            imageSearchFocus.value = com.vivenotes.ui.search.ImageSearchFocus(searchHit!!.unit.boxId, searchImageHighlight)
            kotlinx.coroutines.delay(1800)
            imageSearchFocus.value = null
        }
    }
    LaunchedEffect(searchHit, searchRevealTick, page?.id, page?.inkReady) {
        interaction.searchInkBounds = null
        val hit = searchHit
        if (hit?.unit?.pageId == page?.id && hit?.unit?.kind == ContentKind.Ink && page?.inkReady == true &&
            page.ink?.strokes?.any { it.id in hit.unit.inkStrokeIds } == true) {
            interaction.searchInkBounds = hit.unit.inkBounds
            kotlinx.coroutines.delay(1500)
            interaction.searchInkBounds = null
        }
    }
    LaunchedEffect(searchHit, searchImageHighlight, searchRevealTick, page?.id, page?.content) {
        val hit = searchHit ?: return@LaunchedEffect
        if (hit.unit.pageId != page?.id || page.editable != true) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val outline = page.document.outlines.firstOrNull { it.id == (hit.unit.tableId ?: hit.unit.boxId) }
        val textLayout = textLayouts[hit.unit.boxId]
        val lineY = if (textLayout != null && hit.unit.kind == ContentKind.Text &&
            hit.editorStart < textLayout.layoutInput.text.length)
            textLayout.getBoundingBox(hit.editorStart).top / density.density else 0f
        val picture = (outline as? Outline.Image)?.takeIf { hit.unit.kind == ContentKind.Image }
        if (picture != null && searchImageHighlight != null) {
            val scale = density.density * zoom
            val target = com.vivenotes.ui.search.pictureSearchScrollTarget(picture, searchImageHighlight,
                visiblePageWindow(), Offset(horizontalScroll.maxValue / scale, verticalScroll.maxValue / scale))
            horizontalScroll.scrollTo((target.x * scale).roundToInt())
            verticalScroll.scrollTo((target.y * scale).roundToInt())
        } else {
            horizontalScroll.scrollTo((((hit.unit.inkBounds?.left ?: outline?.x ?: 0f) - 24f).coerceAtLeast(0f) * density.density * zoom).roundToInt())
            verticalScroll.scrollTo((((hit.unit.inkBounds?.top ?: outline?.y ?: 0f) + lineY - 40f).coerceAtLeast(0f) * density.density * zoom).roundToInt())
        }
        if (hit.unit.kind == ContentKind.Title && !page.document.style.hideTitle) {
            interaction.titleValue = TextFieldValue(page.title, TextRange(hit.editorStart.coerceIn(0, page.title.length),
                hit.editorEnd.coerceIn(0, page.title.length)))
            titleFocus.requestFocus()
        }
    }
    // Something landed from outside the canvas — a document's pages, which go under what is already
    // here and so usually below the window, where nothing on screen would say that they arrived. Left
    // alone when it is already where the eye is, so importing onto a fresh page does not move the title.
    val scrollTarget = placement.scrollTarget
    LaunchedEffect(scrollTarget, page?.id) {
        val target = scrollTarget ?: return@LaunchedEffect
        if (target.pageId != page?.id) return@LaunchedEffect
        val top = target.y * zoomState.value * density.density
        val windowHeight = interaction.viewportPx.height
        if (top < verticalScroll.value || top > verticalScroll.value + windowHeight * SCROLL_TARGET_COMFORTABLE) {
            val destination = (top - with(density) { SCROLL_TARGET_MARGIN.toPx() }).roundToInt().coerceAtLeast(0)
            // The objects arrive a frame or two before the canvas has grown to hold them, and a scroll
            // asked for sooner stops at the page's old end.
            withTimeoutOrNull(SCROLL_RANGE_WAIT_MS) { snapshotFlow { verticalScroll.maxValue }.first { it >= destination } }
            verticalScroll.animateScrollTo(destination)
        }
        placement.onScrollTargetHandled()
    }
    val textClipboard = TextClipboardActions(rememberTextClipboard(), onTextCommand, LocalClipboardHtml.current)
    val caretKeys = remember(page?.id) { TextCaretKeys() }
    /** Up, Down or End in the text box being edited, when [TextCaretKeys] moves the caret for it. */
    fun moveCaret(id: String, event: KeyEvent): Boolean {
        val snapshot = currentState
        if (snapshot.focusedTextOutlineId != id || snapshot.editorComposition != null) return false
        val layout = textLayouts[id] ?: return false
        val buffer = snapshot.richTextFor(id) ?: return false
        val moved = caretKeys.move(event, layout, buffer.text, buffer.selection) ?: return false
        currentEditTextAt(id, moved)
        return true
    }
    // The text box whose field holds the keyboard. Esc and a tap on the page end the editing but
    // not the field's focus, and the field would go on taking keys — Ctrl+Z as its own text undo,
    // and typing — so once its box is no longer the one being edited, the canvas takes the focus.
    LaunchedEffect(interaction.keyboardTextBox, state.focusedTextOutlineId) {
        val holder = interaction.keyboardTextBox ?: return@LaunchedEffect
        if (holder == currentState.focusedTextOutlineId) return@LaunchedEffect
        withFrameNanos { }
        if (interaction.keyboardTextBox == holder && holder != currentState.focusedTextOutlineId) {
            canvasFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(zoom) {
        val previous = zoomTracking.applied
        zoomTracking.applied = zoom
        // The wheel asks for its own cursor-anchored scroll, and a pinch has scrolled already. A
        // change from the ribbon keeps the middle of the window still, except on an axis scrolled
        // to its start, whose page edge stays in view — zooming at the top of a page must not
        // scroll its title away. Page Width also brings the page's left edge to the window's.
        val requested = interaction.pendingViewport?.takeIf { it.zoom == zoom } ?: if (previous == zoom) null else {
            val alignLeft = canvasControl.alignLeftOnNextZoom
            val scrollX = horizontalScroll.value.toFloat()
            val scrollY = verticalScroll.value.toFloat()
            val anchored = CanvasViewport(previous, scrollX, scrollY).zoomTo(zoom,
                if (alignLeft || scrollX == 0f) 0f else interaction.viewportPx.width / 2f,
                if (scrollY == 0f) 0f else interaction.viewportPx.height / 2f)
            if (alignLeft) anchored.copy(scrollX = 0f) else anchored
        }
        canvasControl.alignLeftOnNextZoom = false
        requested ?: return@LaunchedEffect
        withFrameNanos { }
        horizontalScroll.scrollTo(requested.scrollX.roundToInt())
        verticalScroll.scrollTo(requested.scrollY.roundToInt())
        if (interaction.pendingViewport == requested) interaction.pendingViewport = null
    }

    // How far the canvas has been extended to meet the user, in whole screenfuls: a high-water
    // mark, so scrolling back never shrinks the canvas from under the scroll position, and quantised
    // so it changes about once a screenful rather than once a frame.
    LaunchedEffect(page?.id, interaction.viewportPx, zoom) {
        snapshotFlow {
            val across = interaction.viewportPx.width / zoom
            val down = interaction.viewportPx.height / zoom
            val x = if (across > 0f) ((horizontalScroll.value / zoom + across) / across).toInt() else 0
            val y = if (down > 0f) ((verticalScroll.value / zoom + down) / down).toInt() else 0
            x to y
        }.collect { (x, y) ->
            if (x > interaction.reachedX) interaction.reachedX = x
            if (y > interaction.reachedY) interaction.reachedY = y
        }
    }

    LaunchedEffect(page?.id, interaction.panActive) {
        if (!interaction.panActive) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (interaction.panActive) {
            if (CanvasViewport.autoScrollResting(interaction.panPointer.x - interaction.panAnchor.x, interaction.panPointer.y - interaction.panAnchor.y)) {
                snapshotFlow { interaction.panPointer }.first {
                    !CanvasViewport.autoScrollResting(it.x - interaction.panAnchor.x, it.y - interaction.panAnchor.y)
                }
                previousFrame = withFrameNanos { it }
            }
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previousFrame = frame
            val offset = interaction.panPointer - interaction.panAnchor
            val before = CanvasViewport(zoomState.value,
                horizontalScroll.value.toFloat(), verticalScroll.value.toFloat())
            val next = before.autoScrollBy(offset.x, offset.y, elapsed,
                horizontalScroll.maxValue.toFloat(), verticalScroll.maxValue.toFloat())
            horizontalScroll.dispatchRawDelta(next.scrollX - before.scrollX)
            verticalScroll.dispatchRawDelta(next.scrollY - before.scrollY)
        }
    }

    fun toPage(point: Offset): Offset = with(density) {
        Offset(
            ((point.x + horizontalScroll.value) / zoomState.value).toDp().value,
            ((point.y + verticalScroll.value) / zoomState.value).toDp().value,
        )
    }
    SideEffect { canvasOrigin.read = { toPage(Offset.Zero) } }
    val fileDrop = rememberPageFileDrop(debugLog)
    SideEffect {
        fileDrop.pageId = page?.takeIf { it.editable }?.id
        fileDrop.handlers = fileDrops
        fileDrop.toPage = ::toPage
    }

    // A held row or column lasts while its table alone stays selected and still has it.
    val heldTable = interaction.heldTableAxis.heldIn(state)
    if (interaction.heldTableAxis != null && heldTable == null) SideEffect { interaction.heldTableAxis = null }

    // The page's size and edge, from everything on it: whether a chosen sheet still holds the
    // content decides whether it bounds the page or is only drawn as a guide.
    val inkEdge = remember(page?.ink) { page?.ink?.contentEdge() ?: Offset.Zero }
    /** How far down what is on [document] reaches, as drawn: text at the heights it wrapped to. */
    fun contentBottomOf(document: PageDoc, inkBottom: Float): Float = maxOf(inkBottom, document.outlines.maxOfOrNull { outline ->
        outline.y + if (outline is Outline.Text) measuredTextHeights[outline.id]
            ?: maxOf(150f, outline.minHeight) else measuredTextHeights[outline.id] ?: outline.primeHeight()
    } ?: 0f)
    val extent = page?.document?.let { document ->
        remember(document, inkEdge, measuredTextHeights) { derivedStateOf {
        PageExtent.of(
            document.style,
            contentRight = maxOf(inkEdge.x, document.outlines.maxOfOrNull { it.x + it.primeWidth() } ?: 0f),
            contentBottom = contentBottomOf(document, inkEdge.y),
        )
        } }.value
    }
    val currentExtentState = rememberUpdatedState(extent)
    val currentExtent by currentExtentState
    val currentInkEdge by rememberUpdatedState(inkEdge)
    SideEffect {
        canvasControl.viewportWidthDp = interaction.viewportPx.width / density.density
        canvasControl.pageWidthDp = extent?.page?.width?.value ?: 0f
        // Read when asked, not composed: nothing on screen depends on them.
        placement.visibleOrigin = { toPage(Offset.Zero) }
        placement.visibleWidth = { interaction.viewportPx.width / zoomState.value / density.density }
        placement.contentBottom = {
            currentState.selectedPage?.takeIf { it.id == page?.id }?.document?.let { contentBottomOf(it, currentInkEdge.y) }
        }
    }

    /** The canvas clipboard at page [point]; ink is laid out off the main thread, one paste at a time. */
    fun pasteCanvas(point: Offset) {
        if (!canvasClipboard.useInternal()) {
            canvasClipboard.pasteExternal(point.x, point.y)
            return
        }
        val before = currentState
        val clipboard = before.canvasClipboard
        debugLog.event("objects") { "paste ${clipboard.outlines.size} objects" + if (clipboard.ink != null) " with ink" else "" }
        if (before.canvasClipboard.ink == null) currentPaste(point.x, point.y)
        else if (!interaction.inkMoveBusy) {
            interaction.inkMoveBusy = true
            inkMoveScope.launch {
                try {
                    val prepared = withContext(Dispatchers.Default) { before.pasteCanvasAt(point.x, point.y) }
                    currentPreparedPaste(before, prepared)
                } finally { interaction.inkMoveBusy = false }
            }
        }
    }
    SideEffect {
        keyboardPaste.paste = paste@{
            val snapshot = currentState
            val style = snapshot.selectedPage?.takeIf { it.editable }?.document?.style ?: return@paste false
            if (!canvasClipboard.canPaste()) return@paste false
            val extent = currentExtent ?: return@paste false
            interaction.pastePoint = null
            pasteCanvas(keyboardPastePoint(canvasPointer.position?.let(::toPage), toPage(Offset.Zero), style) { x, y ->
                extent.canPlaceAt(style, x, y)
            })
            true
        }
    }

    /**
     * The prime object menu at page [point]: on object [objectId], which a right-click selects as a
     * click would unless it is already selected, or with null on the selection, whose frame it was in.
     * [tableCell] is the row and column of the cell the point is in, when the object is a table.
     */
    fun openObjectMenu(objectId: String?, point: Offset, tableCell: Pair<Int, Int>? = null) {
        focusManager.clearFocus()
        canvasFocusRequester.requestFocus()
        if (objectId != null && objectId !in currentState.selectedObjectIds) onSelectObject(objectId)
        interaction.pastePoint = null
        // Left as it is when the menu closes, so the cell's rows do not go before the menu has.
        interaction.objectMenuCell = tableCell
        interaction.objectMenu = point
    }

    fun showsMoveBar(box: Outline.Text, snapshot: WorkspaceState): Boolean =
        snapshot.textBoxMoveBarMode(box).shows(hovered = interaction.hoveredTextBox == box.id)

    fun hitsContent(point: Offset, snapshot: WorkspaceState): Boolean =
        snapshot.selectedPage?.ink?.let { ink -> ink.geometry?.selectAt(ink, point.x, point.y) != null } == true ||
        snapshot.selectedPage?.document?.outlines?.any { outline ->
            val height = if (outline is Outline.Text) measuredTextHeights[outline.id]
                ?: maxOf(150f, outline.minHeight) else measuredTextHeights[outline.id] ?: outline.primeHeight()
            // Room for the resize handle, which an unwritten box does not have.
            val margin = if (outline is Outline.Text && snapshot.focusedTextOutlineId == outline.id &&
                !outline.isUnwritten()) 16f else 0f
            val chromeTop = if (outline is Outline.Text && showsMoveBar(outline, snapshot)) TEXT_BOX_MOVE_BAR_DP
                else if (outline.id in snapshot.selectedObjectIds) 52f
                else if (outline is Outline.Table && snapshot.showsTableHandles(outline)) TEXT_BOX_MOVE_BAR_DP else 0f
            point.x >= outline.x - margin && point.x <= outline.x + outline.primeWidth() + margin &&
                point.y >= outline.y - chromeTop &&
                point.y <= outline.y + height + margin
        } == true

    fun hitsSelectedTransform(point: Offset, snapshot: WorkspaceState): Boolean {
        val outlines = snapshot.selectedPage?.document?.outlines ?: return false
        val group = selectedCanvasBounds(outlines,
            snapshot.selectedObjectIds + snapshot.selectedTextOutlineIds, measuredTextHeights, snapshot.selectedInk)
        val groupBody = group != null && point.x in (group.left - 6f)..(group.right + 6f) &&
            point.y in (group.top - 6f)..(group.bottom + 6f)
        val groupCorner = group != null && snapshot.selectedInk == null && snapshot.selectedTextOutlineIds.isEmpty() &&
            !snapshot.selectedObjectsLocked && listOf(
                Offset(group.left - 6f, group.top - 6f), Offset(group.right + 6f, group.top - 6f),
                Offset(group.left - 6f, group.bottom + 6f), Offset(group.right + 6f, group.bottom + 6f),
            ).any { (point - it).getDistance() <= 14f }
        val moveBar = outlines.any { outline ->
            outline is Outline.Text && outline.moveBarContains(point) && showsMoveBar(outline, snapshot) ||
                outline is Outline.Table && snapshot.tableMoveBarContains(outline, point)
        }
        val primeBounds = outlines.any { outline ->
            val margin = if (outline is Outline.Shape && outline.segments.isNotEmpty())
                maxOf(10f, outline.borderWidth / 2f + 9f) else 10f
            val height = measuredTextHeights[outline.id] ?: outline.primeHeight()
            outline.id in snapshot.selectedObjectIds && outline.isPrimeObject() &&
                point.x in (outline.x - margin)..(outline.x + outline.primeWidth() + margin) &&
                point.y in (outline.y - margin)..(outline.y + height + margin)
        }
        return groupBody || groupCorner || moveBar || primeBounds
    }

    /** The selected objects' toolbar, which sits just above their frame. */
    fun hitsSelectionToolbar(point: Offset, snapshot: WorkspaceState, reach: Float): Boolean {
        if (snapshot.selectedInk != null) return interaction.inkToolkitBoundsInView?.contains(Offset(
            point.x * zoomState.value * density.density - horizontalScroll.value,
            point.y * zoomState.value * density.density - verticalScroll.value)) == true
        val bounds = selectedCanvasBounds(snapshot.selectedPage?.document?.outlines.orEmpty(),
            snapshot.selectedObjectIds + snapshot.selectedTextOutlineIds, measuredTextHeights, snapshot.selectedInk) ?: return false
        return point.x in bounds.left..(bounds.left + 400f) && point.y in (bounds.top - reach)..bounds.top
    }

    // Android's split: a drawing tool has a finger only when fingers may draw; otherwise the finger
    // moves the page, except on the controls of what is selected.
    fun fingerRole(position: Offset): FingerRole {
        val snapshot = currentState
        val point = toPage(position)
        val toolArmed = snapshot.inkTool != null || snapshot.shapeToolArmed || snapshot.objectLassoArmed || snapshot.drawPlacement != null
        return when {
            currentRuler?.grabs(RulerPoint(point.x, point.y)) == true -> FingerRole.Content
            toolArmed && currentDrawWithFinger -> FingerRole.Tool
            hitsSelectedTransform(point, snapshot) || hitsSelectionToolbar(point, snapshot, reach = 80f) ->
                FingerRole.Content
            toolArmed -> FingerRole.Pan
            hitsContent(point, snapshot) -> FingerRole.Content
            else -> FingerRole.PanAfterSlop
        }
    }
    val touchActions = rememberUpdatedState(object : CanvasTouchActions {
        override fun roleAt(position: Offset) = fingerRole(position)
        override fun pan(dx: Float, dy: Float) = touchViewport.pan(dx, dy)
        override fun fling(vx: Float, vy: Float) = touchViewport.fling(vx, vy)
        override fun stopFling() = touchViewport.stopFling()
        override fun pinch(focus: Offset, pan: Offset, zoomChange: Float) =
            touchViewport.pinch(focus, pan, zoomChange)
        override fun doubleTap(position: Offset) {
            val snapshot = currentState
            val point = toPage(position)
            val style = snapshot.selectedPage?.document?.style ?: return
            if (canvasClipboard.canPaste() && !hitsContent(point, snapshot) &&
                !hitsSelectedTransform(point, snapshot) && !hitsSelectionToolbar(point, snapshot, reach = 80f) &&
                currentExtent?.canPlaceAt(style, point.x, point.y) == true) {
                interaction.pastePoint = point
            }
        }
    })

    Box(
        modifier
            .testTag(WorkspaceTestTags.PageCanvas)
            .then(fileDrop.modifier)
            .onSizeChanged { interaction.viewportPx = it }
            .background(MaterialTheme.colorScheme.background)
            .drawWithContent {
                drawContent()
                if (interaction.panActive) {
                    val c = interaction.panAnchor
                    val radius = 14.dp.toPx()
                    val ink = Color(0xFF30343B)
                    drawCircle(Color(0xFFF7F7F7), radius, c)
                    drawCircle(ink, radius, c, style = Stroke(width = 1.5.dp.toPx()))
                    drawCircle(ink, 2.dp.toPx(), c)
                    val arm = 6.dp.toPx()
                    val tip = 10.dp.toPx()
                    val wing = 2.5.dp.toPx()
                    for ((dx, dy) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) {
                        val start = Offset(c.x + dx * arm, c.y + dy * arm)
                        val end = Offset(c.x + dx * tip, c.y + dy * tip)
                        drawLine(ink, start, end, 1.5.dp.toPx())
                        drawLine(ink, end, Offset(end.x - dx * wing - dy * wing,
                            end.y - dy * wing + dx * wing), 1.5.dp.toPx())
                        drawLine(ink, end, Offset(end.x - dx * wing + dy * wing,
                            end.y - dy * wing - dx * wing), 1.5.dp.toPx())
                    }
                }
            }
            .pointerHoverIcon(heldCursor?.let { canvasPointerIcon(it) } ?: PointerIcon.Default,
                overrideDescendants = heldCursor != null)
            // Where the mouse is, for the badge beside it that shows what a click will insert.
            .followPointer(canvasPointer)
            .followTextBoxHover(page?.id, interaction.hoveredTextBoxState) { position ->
                currentState.textBoxUnderMouse(toPage(position), interaction.hoveredTextBox, measuredTextHeights)
            }
            // Ahead of the page's own handlers: on the initial pass, the first modifier is asked first.
            // Keyed by the placement it writes, which is new for each page and each time the ruler is
            // shown: a handler started earlier would keep moving the placement of a ruler now gone.
            .pointerInput(interaction.rulerPlacementState) {
                val placement = interaction.rulerPlacementState
                detectRulerGestures({ currentRuler }, ::toPage) { placement.value = it }
            }
            .canvasTouchGestures(touchActions)
            .pointerInput(page?.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = if (change.scrollDelta.y != 0f) change.scrollDelta.y
                                else change.scrollDelta.x
                            val base = interaction.pendingViewport ?: CanvasViewport(zoomState.value,
                                horizontalScroll.value.toFloat(), verticalScroll.value.toFloat())
                            val next = base.wheel(delta, change.position.x, change.position.y)
                            if (next.zoom != base.zoom) {
                                interaction.pendingViewport = next
                                currentZoomChange(next.zoom)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(page?.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
                            val point = toPage(change.position)
                            val snapshot = currentState
                            val style = snapshot.selectedPage?.document?.style
                            if (style != null && !hitsContent(point, snapshot) &&
                                !hitsSelectedTransform(point, snapshot) &&
                                currentExtent?.canPlaceAt(style, point.x, point.y) == true) {
                                currentClearCanvasFocus()
                                focusManager.clearFocus()
                                canvasFocusRequester.requestFocus()
                                interaction.objectMenu = null
                                interaction.pastePoint = point.takeIf { canvasClipboard.canPaste() }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                        }
                        // However a drag ended, an object is no longer held once every button is up.
                        if (event.type == PointerEventType.Release && !event.buttons.areAnyPressed) objectGrab.releaseAll()
                        if (interaction.panActive) {
                            if (event.type == PointerEventType.Move) interaction.panPointer = change.position
                            if (event.type == PointerEventType.Release && !event.buttons.isTertiaryPressed) {
                                interaction.panActive = false
                            }
                            event.changes.forEach { it.consume() }
                        } else if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                            interaction.panAnchor = change.position
                            interaction.panPointer = change.position
                            interaction.panActive = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(page?.id, zoom, state.inkTool, state.objectLassoArmed, state.textToolArmed, state.shapeToolArmed, state.drawPlacement) {
                detectCanvasSelectionGestures(
                    workspaceState = currentStateValue,
                    rulerState = currentRulerState,
                    fingerState = currentDrawWithFingerState,
                    extentState = currentExtentState,
                    inkMoveBusyState = interaction.inkMoveBusyState,
                    objectGrab = objectGrab,
                    drafts = CanvasGestureDrafts(interaction.spaceDraftState, interaction.shapeDraftState, interaction.lassoPath, interaction.lassoState, interaction.inkDragOffsetState, interaction.pastePointState),
                    density = density,
                    zoom = zoom,
                    focusManager = focusManager,
                    canvasFocusRequester = canvasFocusRequester,
                    inkMoveScope = inkMoveScope,
                    measuredTextHeights = measuredTextHeights,
                    toPage = ::toPage,
                    hitsContent = ::hitsContent,
                    emptyTextBoxAt = { point, snapshot -> snapshot.emptyTextBoxAt(point, measuredTextHeights) },
                    hitsSelectedTransform = ::hitsSelectedTransform,
                    hitsSelectionToolbar = ::hitsSelectionToolbar,
                    currentSelectInk = { currentSelectInk(it) },
                    finishInkMove = ::finishInkMove,
                    currentSpaceInsertion = { currentSpaceInsertion(it) },
                    currentCreateShape = { sx, sy, ex, ey -> currentCreateShape(sx, sy, ex, ey) },
                    currentLassoSelect = { path, heights -> currentLassoSelect(path, heights) },
                    currentSelectObjects = { l, t, r, b -> currentSelectObjects(l, t, r, b) },
                    currentDrawObject = { x, y -> currentDrawObject(x, y) },
                    currentCreate = { x, y -> currentCreate(x, y) },
                    currentPlaceEmptyTextBox = { id, x, y -> currentPlaceEmptyTextBox(id, x, y) },
                    currentClearCanvasFocus = { currentClearCanvasFocus() },
                    canPaste = { canvasClipboard.canPaste() },
                )
            },
    ) {
        if (page == null) {
            Text(Strings.choose_a_page_to_begin, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        // A body still being read: for that instant there is nothing to draw and nothing to edit.
        if (page.content == PageContent.Unloaded) return@Box
        // Drags of what is on the page report here, for the grabbing cursor.
        CompositionLocalProvider(LocalObjectGrab provides objectGrab) {
        Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll)
            .verticalScroll(verticalScroll)) {
            val pageExtent = extent ?: return@Box
            // The window onto the page, in page dp: what the viewport shows at this zoom.
            val window = with(density) { DpSize((interaction.viewportPx.width / zoom).toDp(), (interaction.viewportPx.height / zoom).toDp()) }
            val canvasSize = pageExtent.canvasSize(window, interaction.reachedX, interaction.reachedY, density, zoom)
            // Read while drawing, so scrolling redraws the ruling and recomposes nothing.
            val visibleWindow: () -> Rect = {
                val left = horizontalScroll.value / zoom
                val top = verticalScroll.value / zoom
                Rect(left, top, left + interaction.viewportPx.width / zoom, top + interaction.viewportPx.height / zoom)
            }
            val palette = canvasPalette(page.document.style, canvasDark)
            val darkPage = palette.background.luminance() < 0.45f
            val shapeInk = palette.ink
            val richColors = RichTextColors(
                // Android's `EditorStyle.accentColor`, for bullets, to-do boxes and quote stripes.
                accent = Color(0xFF4CAF50),
                link = if (darkPage) Color(0xFF8AB4F8) else Color(0xFF1A5FB4),
                codeBackground = palette.ink.copy(alpha = 0.1f),
            )
            ZoomedCanvas(zoom) {
                Box(Modifier.documentExtent(canvasSize).testTag(WorkspaceTestTags.CanvasExtent)) {
                    val groupBounds = selectedCanvasBounds(page.document.outlines,
                        state.selectedObjectIds + state.selectedTextOutlineIds, measuredTextHeights, state.selectedInk)
                    CanvasPaper(page.document.style, palette, pageExtent, canvasSize, zoom, visibleWindow,
                        showMargins, interaction.lasso, interaction.lassoPath)
                    CanvasDrafts(
                        state = state,
                        spaceDraft = interaction.spaceDraft,
                        shapeDraft = interaction.shapeDraft,
                        canvasSize = canvasSize,
                        visibleWindow = visibleWindow,
                        shapeInk = shapeInk
                    )
                    CanvasPageTitle(
                        page = page,
                        palette = palette,
                        titleState = interaction.titleValueState,
                        titleFocus = titleFocus,
                        onTitleChange = onTitleChange,
                        onClearCanvasFocus = onClearCanvasFocus
                    )
                    val displayedOutlines = if (interaction.inkDragOffset == Offset.Zero) page.document.outlines else page.document.outlines.map { outline ->
                        if (outline.id !in state.selectedObjectIds && outline.id !in state.selectedTextOutlineIds) outline
                        else if (outline is Outline.Text) outline.copy(x = outline.x + interaction.inkDragOffset.x, y = outline.y + interaction.inkDragOffset.y)
                        else outline.movedBy(interaction.inkDragOffset.x, interaction.inkDragOffset.y)
                    }
                    CanvasTextBoxes(
                        state = state,
                        viewport = visiblePageWindow,
                        searchTarget = searchHit?.unit?.boxId,
                        workspaceState = currentStateValue,
                        displayedOutlines = displayedOutlines,
                        measuredTextHeights = measuredTextHeights,
                        measurementCache = textMeasurementCache,
                        debugLog = debugLog,
                        textLayouts = textLayouts,
                        previewLayouts = previewLayouts,
                        menuState = interaction.textMenuState,
                        keyboardFocusState = interaction.keyboardTextBoxState,
                        hoveredTextBox = interaction.hoveredTextBoxState,
                        richColors = richColors,
                        palette = palette,
                        thumbnails = thumbnails,
                        textClipboard = textClipboard,
                        editorFocusRequester = editorFocusRequester,
                        onMoveTextBox = onMoveTextBox,
                        onResizeTextBox = onResizeTextBox,
                        onMoveSelection = onMoveSelection,
                        onCopyObjects = onCopyObjects,
                        onDeleteObjects = onDeleteObjects,
                        onOpenTextMenu = onOpenTextMenu,
                        onEditTextAt = onEditTextAt,
                        onToggleTodo = onToggleTodo,
                        onTextShortcut = onTextShortcut,
                        onMoveCaret = ::moveCaret,
                        onBodyChange = onBodyChange,
                        onFocusTextBox = onFocusTextBox,
                        onTextCommand = onTextCommand,
                        onTextLinkRequest = onTextLinkRequest,
                        onDeleteTextBox = onDeleteTextBox
                    )
                    CanvasPrimeObjects(
                        viewport = visiblePageWindow,
                        searchTarget = searchHit?.unit?.tableId ?: searchHit?.unit?.boxId,
                        heldTable = heldTable,
                        onHoldTable = { axis ->
                            focusManager.clearFocus()
                            canvasFocusRequester.requestFocus()
                            interaction.heldTableAxis = axis
                            if (axis != null && currentState.selectedObjectIds != setOf(axis.tableId)) onSelectObject(axis.tableId)
                        },
                        onTableEdit = onTableEdit,
                        state = state,
                        workspaceState = currentStateValue,
                        displayedOutlines = displayedOutlines,
                        groupBounds = groupBounds,
                        measuredTextHeights = measuredTextHeights,
                        richColors = richColors,
                        shapeInk = shapeInk,
                        pictures = pictures,
                        pictureAssets = pictureAssets,
                        pictureHighlights = pictureHighlights,
                        searchImageFocus = imageSearchFocus.value,
                        paperColor = palette.background,
                        graphRewrites = graphRewrites,
                        onMutateGraph = onMutateGraph,
                        onTraceGraph = onTraceGraph,
                        onGraphArea = onGraphArea,
                        onGraphParameter = onGraphParameter,
                        onEditGraphSliders = onEditGraphSliders,
                        editorFocusRequester = editorFocusRequester,
                        canvasFocusRequester = canvasFocusRequester,
                        onMoveObject = onMoveObject,
                        onSelectObject = onSelectObject,
                        onResizeObjects = onResizeObjects,
                        onFocusTextBox = onFocusTextBox,
                        onBodyChange = onBodyChange,
                        onTextShortcut = onTextShortcut,
                        onMoveShapeEnd = onMoveShapeEnd,
                        onMoveShapeArm = onMoveShapeArm,
                        onObjectMenu = ::openObjectMenu,
                        onObjectCommand = onObjectCommand,
                    )
                    val inkEnabled = page.editable && page.inkReady && !state.notebookTransfer.running && !state.filePane.busy
                    InkLayer(page.ink, canvasSize, shapeInk, visibleWindow, page.id,
                        state.inkTool.takeIf { inkEnabled && it != InkTool.Laser }, page.document.style.titleFloor, onInkEdit, zoom,
                        state.eraserMode, state.inkSettings, state.selectedInk, interaction.inkDragOffset, state.activePenIndex, ruler, onStraightenStroke)
                    interaction.searchInkBounds?.let { bounds ->
                        Box(Modifier.offset(bounds.left.dp, bounds.top.dp)
                            .size((bounds.right - bounds.left).coerceAtLeast(1f).dp,
                                (bounds.bottom - bounds.top).coerceAtLeast(1f).dp)
                            .background(com.vivenotes.ui.search.SearchHighlightColor.copy(alpha = .24f))
                            .border(1.5.dp, com.vivenotes.ui.search.SearchHighlightColor.copy(alpha = .72f))
                            .testTag(com.vivenotes.ui.search.SearchTags.InkHighlight))
                    }
                    ruler?.let { RulerLayer(it, canvasSize, palette.ink, zoom) { value -> interaction.rulerPlacement = value } }
                    // Light rather than an object: it lands on the ruler as readily as on the page.
                    LaserLayer(canvasSize, zoom, active = inkEnabled && state.inkTool == InkTool.Laser)
                    state.selectedInk?.let { selectedInk ->
                        if (groupBounds != null) InkSelectionFrame(selectedInk, groupBounds, interaction.inkDragOffset,
                            enabled = page.editable && page.inkReady && !state.notebookTransfer.running && !state.filePane.busy && !interaction.inkMoveBusy,
                            zoom = zoom, onFocus = { focusManager.clearFocus(); canvasFocusRequester.requestFocus() },
                            onOffset = { interaction.inkDragOffset = it }, onMove = ::finishInkMove)
                    }
                    CanvasGroupSelection(
                        state = state,
                        workspaceState = currentStateValue,
                        groupBounds = groupBounds,
                        measuredTextHeights = measuredTextHeights,
                        canvasFocusRequester = canvasFocusRequester,
                        onMoveSelection = onMoveSelection,
                        onResizeObjects = onResizeObjects,
                        onSelectionMenu = { point -> openObjectMenu(null, point) },
                    )
                    CanvasObjectToolkit(
                        state = state,
                        groupBounds = groupBounds,
                        measuredTextHeights = measuredTextHeights,
                        shapeInk = shapeInk,
                        heldTable = heldTable,
                        onColorObjects = onColorObjects,
                        onTableEdit = onTableEdit,
                        onReleaseTable = { interaction.heldTableAxis = null },
                        onEquationChange = onEquationChange,
                        graphActions = graphActions,
                        graphableFormulas = graphableFormulas,
                        onGraphEquation = onGraphEquation,
                        onOpenEquationInMath = onOpenEquationInMath,
                        onShapeLineType = onShapeLineType,
                        onShapeWidth = onShapeWidth,
                        onShapeFill = onShapeFill,
                        onImageBackground = onImageBackground,
                        onCopyObjects = onCopyObjects,
                        onToggleObjectLock = onToggleObjectLock,
                        onDeleteObjects = onDeleteObjects
                    )
                }
            }
        }
        }
        state.selectedInk?.let { selection ->
            val bounds = selectedCanvasBounds(page.document.outlines, state.selectedObjectIds + state.selectedTextOutlineIds,
                measuredTextHeights, selection) ?: return@let
            CanvasInkToolkitPosition(bounds, interaction.inkDragOffset, zoom * density.density,
                horizontalScroll, verticalScroll) { viewBounds ->
            val ink = canvasPalette(page.document.style, canvasDark).ink
            InkSelectionToolkit(selection, viewBounds, interaction.viewportPx, ink,
                enabled = page.editable && page.inkReady && !state.notebookTransfer.running && !state.filePane.busy && !interaction.inkMoveBusy,
                showColor = state.selectedTextOutlineIds.isEmpty(), onBoundsChange = { interaction.inkToolkitBoundsInView = it },
                showGroup = state.inkOnlySelection && (selection.targetIds.size > 1 || selection.isGrouped),
                showMath = state.inkAndLinesSelection && inkMathAvailable,
                onMath = { onInkMath(selection) },
                onGroup = {
                    if (!interaction.inkMoveBusy) {
                        interaction.inkMoveBusy = true
                        inkMoveScope.launch {
                            try {
                                val change = withContext(Dispatchers.Default) {
                                    if (selection.isGrouped) selection.ungroup() else selection.group()
                                }
                                if (change != null) currentInkGrouping(change)
                            } finally { interaction.inkMoveBusy = false }
                        }
                    }
                },
                onCopy = onCopyObjects, onDelete = onDeleteObjects, onColor = { argb, followsTheme ->
                    if (!interaction.inkMoveBusy) {
                        interaction.inkMoveBusy = true
                        inkMoveScope.launch {
                            try {
                                val change = withContext(Dispatchers.Default) { selection.recolor(argb, followsTheme) }
                                currentInkRecolor(selection, argb, followsTheme, change)
                            } finally { interaction.inkMoveBusy = false }
                        }
                    }
                })
            }
        }
        // Where pastes from another app will land on this page, while they are read and downloaded.
        canvasClipboard.pendingPastes.filter { it.pageId == page.id }.forEach { pending ->
            key(pending.at) { CanvasPasteProgress(pending.at, zoom * density.density, horizontalScroll, verticalScroll) }
        }
        ContextMenu(
            anchor = interaction.pastePoint?.let { point ->
                Offset(point.x * zoom * density.density - horizontalScroll.value,
                    point.y * zoom * density.density - verticalScroll.value)
            },
            onDismiss = { interaction.pastePoint = null },
        ) {
            ContextMenuItem(label = Strings.paste, icon = DocumentSymbols.ContentPaste,
                shortcut = LocalKeyBindings.current.primary(ShortcutAction.Paste)?.label,
                enabled = canvasClipboard.canPaste(),
                onClick = {
                    interaction.pastePoint?.let(::pasteCanvas)
                    interaction.pastePoint = null
                }, modifier = Modifier.testTag(WorkspaceTestTags.CanvasPaste))
        }
        // Nothing left to act on — an undo, or the objects gone in a sync — closes it for good, so a
        // later selection does not bring it back.
        if (interaction.objectMenu != null && !state.hasObjectSelection) SideEffect { interaction.objectMenu = null }
        // One table right-clicked on a cell — a locked one, or one that holds only ink, whose cells
        // have no menu of their own — also offers that cell's row and column commands.
        val menuTable = (state.selectedPrimeObjects.singleOrNull() as? Outline.Table)?.takeIf {
            state.selectedObjectIds.size == 1 && state.selectedTextOutlineIds.isEmpty() && state.selectedInk == null
        }
        val menuCell = interaction.objectMenuCell
        ObjectContextMenu(
            own = if (menuTable == null || menuCell == null) null else { {
                TableGridMenuItems(menuTable, menuCell.first, menuCell.second, page.editable) { edit ->
                    interaction.objectMenu = null
                    onTableEdit(edit)
                }
            } },
            anchor = interaction.objectMenu?.takeIf { state.hasObjectSelection }?.let { point ->
                Offset(point.x * zoom * density.density - horizontalScroll.value,
                    point.y * zoom * density.density - verticalScroll.value)
            },
            editable = page.editable,
            onCommand = { command ->
                interaction.objectMenu = null
                onObjectCommand(command, null)
            },
            onDismiss = { interaction.objectMenu = null },
        )
        if (state.selectedInk == null) SideEffect { interaction.inkToolkitBoundsInView = null }
        if (page.content == PageContent.Unreadable) {
            StatusBanner(
                UnreadablePageMessage,
                Modifier.align(Alignment.TopCenter).testTag(WorkspaceTestTags.UnreadablePage),
            )
        }
        // The zoom readout is also the quickest way back: a click returns to 100%. The Box holds the
        // corner: TooltipBox does not put its modifier on its outermost layout, so align is lost there.
        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
            HoverTooltip(ResetZoomLabel, position = TooltipAnchorPosition.Above) {
                Surface(
                    onClick = { currentZoomChange(1f) },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .testTag(WorkspaceTestTags.ZoomIndicator)
                        .semantics {
                            contentDescription = ResetZoomLabel
                            stateDescription = Strings.canvas_zoom_value_percent((zoom * 100).roundToInt())
                        },
                ) {
                    Text(Strings.value((zoom * 100).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
        // Last, so it is drawn over everything on the page; a held cursor stands alone.
        InsertCursorBadge(if (heldCursor != null) null else state.pendingInsert(), canvasPointer, Modifier.matchParentSize())
        FileDropHighlight(fileDrop.hovering, Modifier.matchParentSize())
    }
}


private class ZoomTracking(var applied: Float)

internal class CanvasOrigin {
    var read: () -> Offset = { Offset.Zero }
}

@Composable
private fun CanvasInkToolkitPosition(
    bounds: Rect, offset: Offset, scale: Float, horizontal: ScrollState, vertical: ScrollState,
    content: @Composable (Rect) -> Unit,
) {
    content(Rect(
        (bounds.left + offset.x) * scale - horizontal.value,
        (bounds.top + offset.y) * scale - vertical.value,
        (bounds.right + offset.x) * scale - horizontal.value,
        (bounds.bottom + offset.y) * scale - vertical.value,
    ))
}
