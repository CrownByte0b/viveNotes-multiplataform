package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.model.Mark
import com.vivenotes.model.findVideoLinks
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.LinkTarget
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.BlockSeparators
import com.vivenotes.ui.canvas.CtrlState
import com.vivenotes.ui.canvas.LocalObjectGrab
import com.vivenotes.ui.canvas.PreviewLayout
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.canvas.TextBoxPreviews
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.canvas.selectAdditionalWords
import com.vivenotes.ui.canvas.wordAt
import com.vivenotes.workspace.textSelectionsFor
import com.vivenotes.ui.canvas.TextContextMenu
import com.vivenotes.ui.canvas.TextMenuRequest
import com.vivenotes.ui.canvas.asAnnotatedString
import com.vivenotes.ui.canvas.caretAt
import com.vivenotes.ui.canvas.detectObjectDrag
import com.vivenotes.ui.canvas.drawBlockDecorations
import com.vivenotes.ui.canvas.linkAtPoint
import com.vivenotes.ui.canvas.linkSpanAtPoint
import com.vivenotes.ui.canvas.linkPointer
import com.vivenotes.ui.canvas.scrollOnlyForTyping
import com.vivenotes.ui.canvas.storedEquationPreviews
import com.vivenotes.ui.canvas.textMenuAt
import com.vivenotes.ui.canvas.todoAt
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest

private class TextPreparationCounters {
    var editorStarts = 0
    var textLayouts = 0
    var placements = 0
    var reused = 0
}

@Composable
internal fun CanvasTextBoxes(
    state: WorkspaceState,
    viewport: () -> Rect,
    searchTarget: String? = null,
    workspaceState: State<WorkspaceState>,
    displayedOutlines: List<Outline>,
    measuredTextHeights: MutableMap<String, Float>,
    measurementCache: TextMeasurementCache,
    debugLog: DebugLog,
    textLayouts: MutableMap<String, TextLayoutResult>,
    previewLayouts: MutableMap<String, PreviewLayout>,
    menuState: MutableState<TextMenuRequest?>,
    keyboardFocusState: MutableState<String?>,
    richColors: RichTextColors,
    palette: CanvasPalette,
    thumbnails: VideoThumbnailSource?,
    textClipboard: TextClipboardActions,
    editorFocusRequester: FocusRequester,
    onMoveTextBox: (String, Float, Float, Boolean) -> Unit,
    onResizeTextBox: (String, Float?, Float?, Boolean) -> Unit,
    onMoveSelection: (Float, Float, Boolean) -> Unit,
    onCopyObjects: () -> Unit,
    onDeleteObjects: () -> Unit,
    onOpenTextMenu: (String, TextSelection) -> Unit,
    onEditTextAt: (String, TextSelection) -> Unit,
    onToggleTodo: (String, String) -> Unit,
    onTextShortcut: (KeyEvent) -> Boolean,
    onMoveCaret: (String, KeyEvent) -> Boolean,
    onBodyChange: (String, TextFieldValue) -> Unit,
    onFocusTextBox: (String) -> Unit,
    onTextCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onTextLinkRequest: (String, TextSelection, LinkTarget) -> Unit,
    onDeleteTextBox: (String) -> Unit,
) {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = palette.ink)
    val pageId = state.selectedPageId
    val measurements = remember(pageId, measuredTextHeights) { mutableStateMapOf<String, MeasuredTextHeight>() }
    val placedKeys = remember(pageId, measuredTextHeights) { mutableMapOf<String, TextMeasurementKey>() }
    var admitted by remember(pageId, measuredTextHeights) { mutableStateOf(emptySet<String>()) }
    val counters = remember(pageId, measuredTextHeights) { TextPreparationCounters() }
    val observer = LocalTextPreparationObserver.current
    val visible by remember(viewport) { derivedStateOf { canvasDemandWindow(viewport()) } }
    val textOutlines = remember(displayedOutlines) { displayedOutlines.filterIsInstance<Outline.Text>() }
    val requests = remember(textOutlines, density, style, resolver, direction, state.focusedTextOutlineId, thumbnails) {
        textOutlines.associate { outline ->
            val focused = state.focusedTextOutlineId == outline.id
            outline.id to TextMeasurementKey(outline.blocks, outline.width, outline.minHeight,
                density.density, density.fontScale, editing = focused, thumbnails = if (focused) null else thumbnails,
                style = style, layoutDirection = direction, fontResolver = resolver)
        }
    }
    val currentRequests by rememberUpdatedState(requests)
    val known = requests.mapNotNull { (id, request) ->
        (measurements[id]?.takeIf { it.matches(request) } ?: measurementCache.get(pageId, id, request))
            ?.let { id to it }
    }.toMap()
    val pinned = state.selectedTextOutlineIds + listOfNotNull(state.focusedTextOutlineId, searchTarget,
        menuState.value?.outlineId, keyboardFocusState.value)
    val currentOutlines by rememberUpdatedState(textOutlines)
    val currentWindow by rememberUpdatedState(visible)
    val currentPinned by rememberUpdatedState(pinned)
    LaunchedEffect(pageId, measurements) {
        snapshotFlow {
            val waiting = currentOutlines.filter { outline ->
                val request = currentRequests.getValue(outline.id)
                outline.id !in currentPinned && !textMayIntersectWindow(
                    outline.x.coerceAtLeast(0f), outline.y.coerceAtLeast(0f),
                    outline.width.coerceIn(120f, 2000f), currentWindow) &&
                    measurements[outline.id]?.matches(request) != true &&
                    measurementCache.get(pageId, outline.id, request) == null
            }.map { it.id }
            planTextPreparation(waiting, admitted)
        }.collectLatest { plan ->
            if (plan.next.isNotEmpty()) {
                withFrameNanos { admitted = plan.retained + plan.next }
            } else if (admitted != plan.retained) admitted = plan.retained
        }
    }
    LaunchedEffect(pageId, measurements) {
        debugLog.event("text-canvas") { "preparation started boxes=${textOutlines.size}" }
        snapshotFlow { currentRequests.all { (id, request) -> measurements[id]?.matches(request) == true } }.first { it }
        debugLog.event("text-canvas") { "preparation complete boxes=${currentOutlines.size} reused=${counters.reused} layouts=${counters.textLayouts}" }
    }
    SideEffect {
        val ids = textOutlines.mapTo(HashSet()) { it.id }
        measurements.keys.filterNot { it in ids }.forEach(measurements::remove)
        placedKeys.keys.filter { it !in ids || placedKeys[it] != requests[it] }.toList().forEach { id ->
            placedKeys.remove(id)
            measuredTextHeights.remove(id)
        }
        measurementCache.retainBoxes(pageId, ids)
        textLayouts.keys.filterNot { it in ids }.forEach(textLayouts::remove)
        previewLayouts.keys.filterNot { it in ids }.forEach(previewLayouts::remove)
        known.forEach { (id, measurement) ->
            if (measurements[id] !== measurement) {
                counters.reused++
                val adopted = measurement.copy(key = requests.getValue(id))
                measurements[id] = adopted
                measuredTextHeights[id] = adopted.height
                placedKeys[id] = adopted.key
                measurementCache.put(pageId, id, adopted)
            }
        }
    }
    var composed = 0
    var background = 0
    textOutlines.forEachIndexed { index, outline ->
        key(outline.id) {
            val focused = state.focusedTextOutlineId == outline.id
            val measureKey = requests.getValue(outline.id)
            val selected = outline.id in state.selectedTextOutlineIds
            val knownHeight = known[outline.id]?.height
            val needed = outline.id in pinned || if (knownHeight != null) {
                shouldComposeCanvasItem(outline.x.coerceAtLeast(0f), outline.y.coerceAtLeast(0f),
                    outline.width.coerceIn(120f, 2000f), knownHeight, visible, false)
            } else textMayIntersectWindow(outline.x.coerceAtLeast(0f), outline.y.coerceAtLeast(0f),
                outline.width.coerceIn(120f, 2000f), visible)
            if (needed || (knownHeight == null && outline.id in admitted)) {
                composed++
                if (!needed) background++
                CanvasTextBox(
                    outline = outline, index = index, focused = focused, lassoSelected = selected,
                    showSelectionToolkit = selected && state.selectedInk == null && state.selectedObjectIds.isEmpty() &&
                        outline.id == state.selectedTextOutlineIds.firstOrNull(),
                    inkSelected = state.selectedInk != null,
                    selection = if (focused) state.editorSelection else TextSelection(0),
                    typingMarks = if (focused) state.typingMarks else emptySet(),
                    composition = if (focused) state.editorComposition else null,
                    measurementKey = measureKey,
                    onMeasurement = { height, ready, reusable, fontsCurrent ->
                        if (workspaceState.value.selectedPageId == pageId && currentRequests[outline.id] == measureKey) {
                            counters.placements++
                            measuredTextHeights[outline.id] = height
                            placedKeys[outline.id] = measureKey
                            if (ready) {
                                val measurement = measurements[outline.id]?.takeIf {
                                    it.height == height && it.matches(measureKey)
                                } ?: MeasuredTextHeight(measureKey, height, fontsCurrent)
                                measurements[outline.id] = measurement
                                if (reusable) {
                                    if (measurementCache.get(pageId, outline.id, measureKey) !== measurement)
                                        measurementCache.put(pageId, outline.id, measurement)
                                } else measurementCache.remove(pageId, outline.id)
                            } else {
                                measurements.remove(outline.id)
                                measurementCache.remove(pageId, outline.id)
                            }
                        }
                    },
                    counters = counters,
                    workspaceState = workspaceState,
                    measuredTextHeights = measuredTextHeights,
                    textLayouts = textLayouts,
                    previewLayouts = previewLayouts,
                    menuState = menuState,
                    keyboardFocusState = keyboardFocusState,
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
                    onMoveCaret = onMoveCaret,
                    onBodyChange = onBodyChange,
                    onFocusTextBox = onFocusTextBox,
                    onTextCommand = onTextCommand,
                    onTextLinkRequest = onTextLinkRequest,
                    onDeleteTextBox = onDeleteTextBox,
                )
            } else {
                CanvasItemPlaceholder(WorkspaceTestTags.textBox(outline.id), outline.x.coerceAtLeast(0f),
                    outline.y.coerceAtLeast(0f), outline.width.coerceIn(120f, 2000f),
                    knownHeight ?: maxOf(outline.minHeight, 80f))
            }
        }
    }
    SideEffect {
        observer?.invoke(TextPreparationSnapshot(pageId, textOutlines.size, known.size, composed, background,
            counters.editorStarts, counters.textLayouts, counters.placements, counters.reused))
    }
}

@Composable
private fun CanvasTextBox(
    workspaceState: State<WorkspaceState>,
    outline: Outline.Text,
    index: Int,
    focused: Boolean,
    lassoSelected: Boolean,
    showSelectionToolkit: Boolean,
    inkSelected: Boolean,
    selection: TextSelection,
    typingMarks: Set<Mark>,
    composition: TextSelection?,
    measurementKey: TextMeasurementKey,
    onMeasurement: (Float, Boolean, Boolean, () -> Boolean) -> Unit,
    counters: TextPreparationCounters,
    measuredTextHeights: MutableMap<String, Float>,
    textLayouts: MutableMap<String, TextLayoutResult>,
    previewLayouts: MutableMap<String, PreviewLayout>,
    menuState: MutableState<TextMenuRequest?>,
    keyboardFocusState: MutableState<String?>,
    richColors: RichTextColors,
    palette: CanvasPalette,
    thumbnails: VideoThumbnailSource?,
    textClipboard: TextClipboardActions,
    editorFocusRequester: FocusRequester,
    onMoveTextBox: (String, Float, Float, Boolean) -> Unit,
    onResizeTextBox: (String, Float?, Float?, Boolean) -> Unit,
    onMoveSelection: (Float, Float, Boolean) -> Unit,
    onCopyObjects: () -> Unit,
    onDeleteObjects: () -> Unit,
    onOpenTextMenu: (String, TextSelection) -> Unit,
    onEditTextAt: (String, TextSelection) -> Unit,
    onToggleTodo: (String, String) -> Unit,
    onTextShortcut: (KeyEvent) -> Boolean,
    onMoveCaret: (String, KeyEvent) -> Boolean,
    onBodyChange: (String, TextFieldValue) -> Unit,
    onFocusTextBox: (String) -> Unit,
    onTextCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onTextLinkRequest: (String, TextSelection, LinkTarget) -> Unit,
    onDeleteTextBox: (String) -> Unit,
) {
    var textMenu by menuState
    var keyboardTextBox by keyboardFocusState
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val currentMoveSelection by rememberUpdatedState(onMoveSelection)
    val currentEditTextAt by rememberUpdatedState(onEditTextAt)
    val currentToggleTodo by rememberUpdatedState(onToggleTodo)
    val currentTextShortcut by rememberUpdatedState(onTextShortcut)
    val richText = remember(outline.blocks, selection, typingMarks) { RichTextBuffer(outline.blocks, selection, typingMarks) }
    val currentRichText by rememberUpdatedState(richText)
    val hasLinks = remember(richText) { richText?.linkSpans()?.isNotEmpty() == true }
    val linkCtrl = remember { CtrlState() }
    val asynchronousPreview = remember(richText.text, thumbnails, focused) {
        !focused && thumbnails != null && findVideoLinks(richText.text).isNotEmpty()
    }
    var previewPrepared by remember(outline.blocks, thumbnails, focused) { mutableStateOf(!asynchronousPreview) }
    var measurementReusable by remember(outline.blocks, thumbnails, focused) { mutableStateOf(!asynchronousPreview) }
    DisposableEffect(Unit) {
        counters.editorStarts++
        onDispose { }
    }
    val currentMeasurement by rememberUpdatedState(onMeasurement)
    val measurementReady = focused || previewPrepared
    // A readiness change must cause a final placement even when the last thumbnail did not
    // change the outer size. Only that pass may release an offscreen preview from composition.
    val publishPlacement: (LayoutCoordinates) -> Unit = remember(measurementReady, measurementReusable, measurementKey, measuredTextHeights) {
        { coordinates ->
            val height = coordinates.size.height / measurementKey.density
            val editor = textLayouts[outline.id]?.multiParagraph?.intrinsics
            val preview = previewLayouts[outline.id]?.layout?.multiParagraph?.intrinsics
            currentMeasurement(height, measurementReady && editor != null, measurementReusable) {
                editor?.hasStaleResolvedFonts == false && preview?.hasStaleResolvedFonts != true
            }
        }
    }
    val width = outline.width.coerceIn(120f, 2000f).dp
    val x = outline.x.coerceAtLeast(0f).dp
    val y = outline.y.coerceAtLeast(0f).dp
    val renderedHeight = (measuredTextHeights[outline.id]
        ?: maxOf(outline.minHeight, 150f)).dp
    val showChrome = focused && outline.blocks.any { it.runs.any { run -> run.plainText.isNotEmpty() } }
    val currentMove by rememberUpdatedState(onMoveTextBox)
    val currentResize by rememberUpdatedState(onResizeTextBox)
    val grab = LocalObjectGrab.current
    if (showSelectionToolkit) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp,
            modifier = Modifier.offset(x, (y - 76.dp).coerceAtLeast(0.dp)),
        ) {
            Row(Modifier.padding(3.dp)) {
                TooltipIconButton(Strings.copy_selected_text_boxes, onClick = onCopyObjects, tooltipPosition = TooltipAnchorPosition.Above,
                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectCopy)) {
                    Icon(DocumentSymbols.ContentCopy, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                }
                TooltipIconButton(Strings.delete_selected_text_boxes, onClick = onDeleteObjects, tooltipPosition = TooltipAnchorPosition.Above,
                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectDelete)) {
                    Icon(ObjectSymbols.Delete, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if ((showChrome || lassoSelected) && !inkSelected) {
        Box(
            modifier = Modifier.offset(x, (y - 32.dp).coerceAtLeast(0.dp))
                .size(48.dp, 32.dp)
                .testTag(WorkspaceTestTags.textGrip(outline.id))
                .semantics { contentDescription = Strings.move_text_box }
                .pointerInput(outline.id, lassoSelected) {
                    var firstChange = true
                    detectObjectDrag(grab,
                        onDragStart = { firstChange = true },
                        onDrag = { change, drag ->
                            change.consume()
                            val dx = with(density) { drag.x.toDp().value }
                            val dy = with(density) { drag.y.toDp().value }
                            if (lassoSelected) currentMoveSelection(dx, dy, firstChange)
                            else currentMove(outline.id, dx, dy, firstChange)
                            firstChange = false
                        },
                    )
                },
        ) {
            Box(Modifier.align(Alignment.Center).size(40.dp, 16.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center) {
                Text("⋮⋮", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Box(
        modifier = Modifier.offset(x, y).width(width)
            .heightIn(min = maxOf(outline.minHeight, 80f).dp)
            .onPlaced(publishPlacement)
            .selectAdditionalWords(outline.id, rangeAt = { position ->
                val point = position - with(density) { Offset(TextBoxPadding.toPx(), TextBoxPadding.toPx()) }
                val layout = textLayouts[outline.id]
                val offset = previewLayouts[outline.id]?.caretAt(point) ?: layout?.getOffsetForPosition(point) ?: 0
                wordAt(layout, currentRichText.text, offset)
            }, onCtrlClick = { position ->
                val point = position - with(density) { Offset(TextBoxPadding.toPx(), TextBoxPadding.toPx()) }
                val link = previewLayouts[outline.id]?.linkAt(point, currentRichText.linkSpans())
                    ?: textLayouts[outline.id]?.let { currentRichText.linkSpanAtPoint(it, point) }
                link?.let { runCatching { uriHandler.openUri(it.href) } }
            })
            // Clicking or focusing a box leaves the page where it is; typing still
            // keeps the caret in view.
            .scrollOnlyForTyping { workspaceState.value.richTextFor(outline.id)?.text }
            // Ours in place of the text field's own menu, which it never sees.
            .onSecondaryPress(outline.id) { position ->
                val request = textMenuAt(outline.id, position, with(density) { TextBoxPadding.toPx() },
                    workspaceState.value, previewLayouts[outline.id], textLayouts[outline.id])
                onOpenTextMenu(outline.id, request.selection)
                textMenu = request
            }
            .onMarginClick(outline.id, leaves = { point ->
                val armed = workspaceState.value.let {
                    it.inkTool != null || it.shapeToolArmed || it.drawPlacement != null || it.objectLassoArmed
                }
                armed || listOfNotNull(textLayouts[outline.id]?.size, previewLayouts[outline.id]?.layout?.size).any {
                    point.x in 0f..it.width.toFloat() && point.y in 0f..it.height.toFloat()
                }
            }) { point ->
                val text = workspaceState.value.richTextFor(outline.id)?.text.orEmpty()
                val caret = textLayouts[outline.id]?.let { layout ->
                    caretAt(layout, text, Offset(point.x.coerceIn(0f, layout.size.width.toFloat()),
                        point.y.coerceIn(0f, (layout.size.height - 1f).coerceAtLeast(0f))))
                } ?: text.length
                currentEditTextAt(outline.id, TextSelection(caret))
            }
            .testTag(WorkspaceTestTags.textBox(outline.id)),
    ) {
        if (focused || lassoSelected) Box(Modifier.matchParentSize()
            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .testTag(WorkspaceTestTags.textBoxOutline(outline.id)))
        val styledText = richText?.asAnnotatedString(richColors) ?: buildAnnotatedString {}
        val menuSelection = textMenu?.takeIf { it.outlineId == outline.id }?.selection
            ?.takeUnless { it.collapsed }
        val selectionTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        val showingPreview = if (!focused && styledText.isNotEmpty()) {
            TextBoxPreviews(
                source = styledText,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = palette.ink),
                ink = palette.ink,
                availableWidthDp = outline.width - 2 * TextBoxPadding.value,
                thumbnails = thumbnails,
                onEdit = { offset -> onEditTextAt(outline.id, TextSelection(offset)) },
                onOpenVideo = { url -> runCatching { uriHandler.openUri(url) } },
                modifier = Modifier.fillMaxWidth().padding(TextBoxPadding).zIndex(1f),
                storedEquations = richText?.storedEquationPreviews().orEmpty(),
                links = richText?.linkSpans().orEmpty(),
                linkColor = richColors.link,
                onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                blocks = outline.blocks,
                colors = richColors,
                onToggleTodo = { blockId -> currentToggleTodo(outline.id, blockId) },
                onPreviewLayout = { previewLayouts[outline.id] = it },
                onPreparationChanged = { previewPrepared = it },
                onMeasurementReusable = { measurementReusable = it },
            )
        } else false
        SideEffect {
            if (!showingPreview) previewLayouts.remove(outline.id)
            if (focused || styledText.isEmpty()) previewPrepared = true
        }
        BasicTextField(
            value = TextFieldValue(
                annotatedString = styledText,
                selection = TextRange(richText?.selection?.start ?: 0,
                    richText?.selection?.end ?: 0),
                composition = if (focused) composition?.let {
                    TextRange(it.start, it.end)
                } else null,
            ),
            onValueChange = { onBodyChange(outline.id, it) },
            visualTransformation = BlockSeparators,
            onTextLayout = { counters.textLayouts++; textLayouts[outline.id] = it },
            decorationBox = { inner ->
                Box(Modifier
                    .drawBehind {
                        textLayouts[outline.id]?.let { layout ->
                            workspaceState.value.textSelectionsFor(outline.id).forEach { range ->
                                if (keyboardTextBox != outline.id || range != workspaceState.value.editorSelection) {
                                    drawPath(layout.getPathForRange(range.min.coerceIn(0, layout.layoutInput.text.length),
                                        range.max.coerceIn(0, layout.layoutInput.text.length)), selectionTint)
                                }
                            }
                            if (menuSelection != null && keyboardTextBox != outline.id) {
                                val start = menuSelection.min.coerceIn(0, layout.layoutInput.text.length)
                                val end = menuSelection.max.coerceIn(0, layout.layoutInput.text.length)
                                if (start < end) drawPath(layout.getPathForRange(start, end), selectionTint)
                            }
                            drawBlockDecorations(layout, outline.blocks, richColors, palette.ink, textMeasurer)
                        }
                    }
                    .linkPointer(PointerIcon.Text, linkCtrl) { point ->
                        hasLinks && textLayouts[outline.id]?.let { currentRichText?.linkAtPoint(it, point) } != null
                    }
                    .pointerInput(outline.id) {
                        // A to-do's box ticks it; Ctrl+click opens a link, as desktop
                        // editors do, leaving a plain click to place the caret.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (down.isConsumed) return@awaitEachGesture
                            val layout = textLayouts[outline.id] ?: return@awaitEachGesture
                            val snapshot = workspaceState.value.richTextFor(outline.id) ?: return@awaitEachGesture
                            val todo = todoAt(layout, snapshot.blocks, down.position, this)
                            val link = if (currentEvent.keyboardModifiers.isCtrlPressed)
                                snapshot.linkAtPoint(layout, down.position) else null
                            if (todo == null && link == null) {
                                // The field puts a click on an empty line past its
                                // separator, on the next line. Once the field has
                                // placed its caret, a click that did not drag puts
                                // it on the line clicked.
                                val caret = caretAt(layout, snapshot.text, down.position)
                                if (currentEvent.keyboardModifiers.isShiftPressed || caret ==
                                    layout.getOffsetForPosition(down.position).coerceIn(0, snapshot.text.length)
                                ) return@awaitEachGesture
                                do {
                                    val change = awaitPointerEvent(PointerEventPass.Final).changes
                                        .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        return@awaitEachGesture
                                    }
                                } while (change.pressed)
                                currentEditTextAt(outline.id, TextSelection(caret))
                                return@awaitEachGesture
                            }
                            down.consume()
                            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                            up.consume()
                            todo?.let { currentToggleTodo(outline.id, it) }
                            link?.let { runCatching { uriHandler.openUri(it) } }
                        }
                    }) { inner() }
            },
            minLines = 3,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                color = palette.ink,
            ),
            modifier = Modifier.fillMaxWidth()
                .alpha(if (showingPreview) 0f else 1f)
                .padding(TextBoxPadding)
                // Ahead of the field's own keys: Tab indents, as on Android, copy and
                // paste keep the formatting the field's own would drop, and Up, Down
                // and End stay on the lines they aim for.
                .onPreviewKeyEvent { event ->
                    linkCtrl.held = event.isCtrlPressed
                    onMoveCaret(outline.id, event) || currentTextShortcut(event)
                }
                .then(if (focused) Modifier.focusRequester(editorFocusRequester) else Modifier)
                .onFocusChanged {
                    if (it.isFocused) {
                        keyboardTextBox = outline.id
                        if (!focused) onFocusTextBox(outline.id)
                    } else if (keyboardTextBox == outline.id) {
                        keyboardTextBox = null
                    }
                }
                .semantics { contentDescription = Strings.text_box_value(index + 1) }
                .testTag(if (index == 0) WorkspaceTestTags.BodyEditor else WorkspaceTestTags.textBox(outline.id) + "-editor"),
        )
        if (textMenu?.outlineId == outline.id) TextContextMenu(
            request = textMenu?.takeIf { it.outlineId == outline.id },
            state = workspaceState.value,
            clipboard = textClipboard,
            onEditorCommand = onTextCommand,
            onLinkRequest = onTextLinkRequest,
            onDeleteBox = onDeleteTextBox,
            onClose = { textMenu = null },
        )
    }
    if (showChrome) {
        Box(Modifier.offset(x + width - 12.dp,
            y + renderedHeight - 12.dp).size(24.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .testTag(WorkspaceTestTags.textResizeHandle(outline.id))
            .semantics { contentDescription = Strings.resize_text_box }
            .pointerInput(outline.id) {
                var total = Offset.Zero
                var startWidth = 0f
                var startHeight = 0f
                var firstChange = true
                detectDragGestures(
                    onDragStart = {
                        total = Offset.Zero
                        firstChange = true
                        val current = workspaceState.value.selectedPage?.document?.outlines
                            ?.filterIsInstance<Outline.Text>()?.firstOrNull { it.id == outline.id }
                        startWidth = current?.width ?: outline.width
                        startHeight = maxOf(current?.minHeight ?: outline.minHeight,
                            measuredTextHeights[outline.id] ?: 80f)
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        total += drag
                        currentResize(outline.id,
                            startWidth + with(density) { total.x.toDp().value },
                            startHeight + with(density) { total.y.toDp().value },
                            firstChange)
                        firstChange = false
                    },
                )
            }) {
            Icon(ObjectSymbols.ExpandContent, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(18.dp).rotate(90f))
        }
    }
}

private val TextBoxPadding = 8.dp

/**
 * A click on a box's margin — the padding round its text, and any height dragged below it — edits
 * the box at the caret nearest the click, as a tap anywhere in an Android container does. The page
 * leaves every press on a box to the box, so the margin otherwise took clicks and did nothing.
 * Points are given from the text's top left. [leaves] is asked of each press: one the text or its
 * preview handles, or a tool that acts on presses over a box, is not this one's.
 */
private fun Modifier.onMarginClick(key: Any?, leaves: (Offset) -> Boolean, onClick: (Offset) -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val padding = TextBoxPadding.toPx()
            val point = down.position - Offset(padding, padding)
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed || leaves(point))
                return@awaitEachGesture
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            up.consume()
            onClick(point)
        }
    }
