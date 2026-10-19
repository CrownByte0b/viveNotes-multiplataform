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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.richtext.LinkTarget
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.BlockSeparators
import com.vivenotes.ui.canvas.PreviewLayout
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.canvas.TextBoxPreviews
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.canvas.TextContextMenu
import com.vivenotes.ui.canvas.TextMenuRequest
import com.vivenotes.ui.canvas.asAnnotatedString
import com.vivenotes.ui.canvas.caretAt
import com.vivenotes.ui.canvas.drawBlockDecorations
import com.vivenotes.ui.canvas.linkAtPoint
import com.vivenotes.ui.canvas.scrollOnlyForTyping
import com.vivenotes.ui.canvas.storedEquationPreviews
import com.vivenotes.ui.canvas.textMenuAt
import com.vivenotes.ui.canvas.todoAt
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun CanvasTextBoxes(
    state: WorkspaceState,
    workspaceState: State<WorkspaceState>,
    displayedOutlines: List<Outline>,
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
    val currentState by workspaceState
    var textMenu by menuState
    var keyboardTextBox by keyboardFocusState
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val currentMoveSelection by rememberUpdatedState(onMoveSelection)
    val currentEditTextAt by rememberUpdatedState(onEditTextAt)
    val currentToggleTodo by rememberUpdatedState(onToggleTodo)
    val currentTextShortcut by rememberUpdatedState(onTextShortcut)
    displayedOutlines.filterIsInstance<Outline.Text>().forEachIndexed { index, outline ->
        val focused = state.focusedTextOutlineId == outline.id
        val lassoSelected = outline.id in state.selectedTextOutlineIds
        val richText = state.richTextFor(outline.id)
        val width = outline.width.coerceIn(120f, 2000f).dp
        val x = outline.x.coerceAtLeast(0f).dp
        val y = outline.y.coerceAtLeast(0f).dp
        val renderedHeight = (measuredTextHeights[outline.id]
            ?: maxOf(outline.minHeight, 150f)).dp
        val showChrome = focused && outline.blocks.any { it.runs.any { run -> run.plainText.isNotEmpty() } }
        val currentMove by rememberUpdatedState(onMoveTextBox)
        val currentResize by rememberUpdatedState(onResizeTextBox)
        if (lassoSelected && state.selectedInk == null && state.selectedObjectIds.isEmpty() &&
            outline.id == state.selectedTextOutlineIds.firstOrNull()) {
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
        if ((showChrome || lassoSelected) && state.selectedInk == null) {
            Box(
                modifier = Modifier.offset(x, (y - 32.dp).coerceAtLeast(0.dp))
                    .size(48.dp, 32.dp)
                    .testTag(WorkspaceTestTags.textGrip(outline.id))
                    .semantics { contentDescription = Strings.move_text_box }
                    .pointerInput(outline.id, lassoSelected) {
                        var firstChange = true
                        detectDragGestures(
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
                .onSizeChanged { size ->
                    measuredTextHeights[outline.id] = with(density) { size.height.toDp().value }
                }
                // Clicking or focusing a box leaves the page where it is; typing still
                // keeps the caret in view.
                .scrollOnlyForTyping { currentState.richTextFor(outline.id)?.text }
                // Ours in place of the text field's own menu, which it never sees.
                .onSecondaryPress(outline.id) { position ->
                    val request = textMenuAt(outline.id, position, with(density) { TextBoxPadding.toPx() },
                        currentState, previewLayouts[outline.id], textLayouts[outline.id])
                    onOpenTextMenu(outline.id, request.selection)
                    textMenu = request
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
                )
            } else false
            SideEffect { if (!showingPreview) previewLayouts.remove(outline.id) }
            BasicTextField(
                value = TextFieldValue(
                    annotatedString = styledText,
                    selection = TextRange(richText?.selection?.start ?: 0,
                        richText?.selection?.end ?: 0),
                    composition = if (focused) state.editorComposition?.let {
                        TextRange(it.start, it.end)
                    } else null,
                ),
                onValueChange = { onBodyChange(outline.id, it) },
                visualTransformation = BlockSeparators,
                onTextLayout = { textLayouts[outline.id] = it },
                decorationBox = { inner ->
                    Box(Modifier
                        .drawBehind {
                            textLayouts[outline.id]?.let { layout ->
                                if (menuSelection != null && keyboardTextBox != outline.id) {
                                    val start = menuSelection.min.coerceIn(0, layout.layoutInput.text.length)
                                    val end = menuSelection.max.coerceIn(0, layout.layoutInput.text.length)
                                    if (start < end) drawPath(layout.getPathForRange(start, end), selectionTint)
                                }
                                drawBlockDecorations(layout, outline.blocks, richColors, palette.ink, textMeasurer)
                            }
                        }
                        .pointerInput(outline.id) {
                            // A to-do's box ticks it; Ctrl+click opens a link, as desktop
                            // editors do, leaving a plain click to place the caret.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val layout = textLayouts[outline.id] ?: return@awaitEachGesture
                                val snapshot = currentState.richTextFor(outline.id) ?: return@awaitEachGesture
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
                    .onPreviewKeyEvent { event -> onMoveCaret(outline.id, event) || currentTextShortcut(event) }
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
            TextContextMenu(
                request = textMenu?.takeIf { it.outlineId == outline.id },
                state = state,
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
                            val current = currentState.selectedPage?.document?.outlines
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
}

private val TextBoxPadding = 8.dp
