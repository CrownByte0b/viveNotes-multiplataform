package com.vivenotes.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt
import com.vivenotes.workspace.CanvasViewport
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.model.Mark
import com.vivenotes.model.BlockType
import com.vivenotes.model.Align
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.workspace.primeHeight
import com.vivenotes.workspace.isPrimeObject
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import com.vivenotes.data.PictureLibrary
import com.vivenotes.ui.canvas.BlockSeparators
import com.vivenotes.ui.canvas.PictureContent
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.canvas.asAnnotatedString
import com.vivenotes.ui.canvas.drawBlockDecorations
import com.vivenotes.ui.canvas.linkAtPoint
import com.vivenotes.ui.canvas.rememberPictureAssets
import com.vivenotes.ui.canvas.todoAt
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.ribbon.document.DocumentTab
import com.vivenotes.ui.ribbon.draw.DrawRibbon
import com.vivenotes.ui.ribbon.file.FileRibbon
import com.vivenotes.ui.ribbon.settings.SettingsRibbon
import com.vivenotes.ui.ribbon.settings.InterfaceDialog
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.ribbon.view.ViewRibbon
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.canvas.TextContextMenu
import com.vivenotes.ui.canvas.TextMenuRequest
import com.vivenotes.ui.canvas.selectionForRightClick
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.navigation.NavigationDialogs
import com.vivenotes.ui.navigation.NavigationRequests
import com.vivenotes.ui.navigation.NotebookPane
import com.vivenotes.ui.navigation.PageListPane
import com.vivenotes.workspace.NavigationItem
import com.vivenotes.workspace.delete
import com.vivenotes.workspace.rename
import androidx.compose.ui.platform.LocalClipboardManager

private val ObjectColors = listOf(
    "White" to 0xFFFFFFFF.toInt(), "Black" to 0xFF000000.toInt(),
    "Gray" to 0xFF6B7280.toInt(), "Red" to 0xFFEF4444.toInt(),
    "Orange" to 0xFFF59E0B.toInt(), "Green" to 0xFF22C55E.toInt(),
    "Cyan" to 0xFF06B6D4.toInt(), "Blue" to 0xFF3B82F6.toInt(),
    "Purple" to 0xFF8B5CF6.toInt(), "Pink" to 0xFFEC4899.toInt(),
)

/** Stable semantics identifiers used by desktop UI tests and future accessibility automation. */
object WorkspaceTestTags {
    const val NavigationToggle = "workspace-navigation-toggle"
    const val NotebookPane = "workspace-notebook-pane"
    const val PagePane = "workspace-page-pane"
    const val PageCanvas = "workspace-page-canvas"
    const val CanvasBackground = "workspace-canvas-background"
    const val ZoomIndicator = "workspace-zoom-indicator"
    const val AddPage = "workspace-add-page"
    const val TitleEditor = "workspace-title-editor"
    const val BodyEditor = "workspace-body-editor"
    const val CanvasPaste = "workspace-canvas-paste"
    const val ObjectCopy = "workspace-object-copy"
    const val ObjectDelete = "workspace-object-delete"
    const val ObjectLock = "workspace-object-lock"
    const val ObjectColor = "workspace-object-color"
    const val StructuralUndo = "workspace-structural-undo"
    const val StructuralRedo = "workspace-structural-redo"
    const val StorageError = "workspace-storage-error"
    const val UnreadablePage = "workspace-unreadable-page"
    fun primeObject(id: String): String = "workspace-prime-object-$id"
    fun objectCorner(id: String, corner: Int): String = "workspace-object-corner-$id-$corner"
    fun textBox(id: String): String = "workspace-text-box-$id"
    fun textBoxOutline(id: String): String = "workspace-text-box-outline-$id"
    fun textGrip(id: String): String = "workspace-text-grip-$id"
    fun textResizeHandle(id: String): String = "workspace-text-resize-$id"

    fun ribbonTab(tab: RibbonTab): String = "workspace-ribbon-${tab.name}"
    fun section(id: String): String = "workspace-section-$id"
    fun page(id: String): String = "workspace-page-$id"
}

/**
 * The workspace window's content.
 *
 * [onStateChange] receives a transition rather than a finished state, and whoever holds the state
 * applies it to the newest one it has — once, on the UI thread. Storage delivers pages and bodies
 * between frames, and a state computed from the last frame would quietly undo them.
 */
@Composable
fun WorkspaceScreen(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    modifier: Modifier = Modifier,
    onAddPage: () -> Unit = { onStateChange { it.addPage() } },
    /** Renames from the navigation menus; a stored workspace passes its session's, which also stores it. */
    onRename: (NavigationItem, String) -> Unit = { item, name -> onStateChange { it.rename(item, name) } },
    /** Deletes from the navigation menus, after they have asked; see [onRename]. */
    onDelete: (NavigationItem) -> Unit = { item -> onStateChange { it.delete(item) } },
    /** Where pictures are stored; without it the Picture command is unavailable. */
    pictures: PictureLibrary? = null,
    // Standalone workspace callers retain the unscaled layout; App supplies the user's default.
    interfaceSettings: InterfaceSettings = InterfaceSettings(displayScale = 1f),
    onInterfaceSettingsChange: (InterfaceSettings) -> Unit = {},
) {
    var previewSettings by remember { mutableStateOf<InterfaceSettings?>(null) }
    val baseDensity = LocalDensity.current
    val effectiveSettings = previewSettings ?: interfaceSettings
    val pageDensity = effectiveSettings.documentDensity(baseDensity)
    CompositionLocalProvider(LocalDensity provides effectiveSettings.density(baseDensity)) {
        WorkspaceContent(state, onStateChange, modifier, onAddPage, onRename, onDelete, pictures,
            onInterface = { previewSettings = interfaceSettings }, pageDensity = pageDensity)
    }
    previewSettings?.let { draft ->
        InterfaceDialog(
            settings = draft,
            onChange = { previewSettings = it.normalized() },
            onApply = {
                onInterfaceSettingsChange(previewSettings ?: draft)
                previewSettings = null
            },
            onDismiss = { previewSettings = null },
        )
    }
}

@Composable
private fun WorkspaceContent(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    modifier: Modifier,
    onAddPage: () -> Unit,
    onRename: (NavigationItem, String) -> Unit,
    onDelete: (NavigationItem) -> Unit,
    pictures: PictureLibrary?,
    onInterface: () -> Unit,
    pageDensity: Density,
) {
    val canvasOrigin = remember { CanvasOrigin() }
    val editorFocusRequester = remember { FocusRequester() }
    val canvasFocusRequester = remember { FocusRequester() }
    var editorFocusRequest by remember { mutableIntStateOf(0) }
    val navigationRequests = remember { NavigationRequests() }
    fun applyEditorCommand(transform: (WorkspaceState) -> WorkspaceState) {
        onStateChange(transform)
        editorFocusRequest++
    }
    LaunchedEffect(editorFocusRequest) {
        if (editorFocusRequest > 0) editorFocusRequester.requestFocus()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onStateChange { it.selectPointer() }
                    true
                } else false
            }
            .focusRequester(canvasFocusRequester)
            .focusable(),
    ) {
        TopNavigation(
            activeTab = state.activeTab,
            navigationVisible = state.navigationVisible,
            onToggleNavigation = {
                onStateChange { it.copy(navigationVisible = !it.navigationVisible) }
            },
            onSelectTab = { tab -> onStateChange { it.selectPointer().copy(activeTab = tab) } },
            canUndo = state.structuralUndo.isNotEmpty(),
            canRedo = state.structuralRedo.isNotEmpty(),
            onUndo = { onStateChange { it.undoStructure() } },
            onRedo = { onStateChange { it.redoStructure() } },
        )
        // Each tab's buttons, and what they do, live in that tab's package under `ui/ribbon`.
        when (state.activeTab) {
            RibbonTab.File -> FileRibbon()
            RibbonTab.Draw -> DrawRibbon(state, onStateChange)
            RibbonTab.Document -> DocumentTab(state, onStateChange, ::applyEditorCommand, pictures, { canvasOrigin.read() })
            RibbonTab.View -> ViewRibbon()
            RibbonTab.Settings -> SettingsRibbon(onInterface)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StorageErrorBanner(state.storageError)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showNotebookPane = maxWidth >= 1040.dp && state.navigationVisible
            val showPagePane = maxWidth >= 760.dp
            val paneMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .animateContentSize(animationSpec = paneMotion),
            ) {
                AnimatedVisibility(
                    visible = showNotebookPane,
                    enter = expandHorizontally(animationSpec = paneMotion),
                    exit = shrinkHorizontally(animationSpec = paneMotion),
                ) {
                    NotebookPane(
                        state = state,
                        requests = navigationRequests,
                        onSelectNotebook = { id -> onStateChange { it.selectNotebook(id) } },
                        onSelectSection = { id -> onStateChange { it.selectSection(id) } },
                    )
                }
                if (showNotebookPane) {
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                if (showPagePane) {
                    PageListPane(
                        section = state.selectedSection,
                        selectedPageId = state.selectedPageId,
                        requests = navigationRequests,
                        onAddPage = onAddPage,
                        onSelectPage = { id -> onStateChange { it.selectPage(id) } },
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                CompositionLocalProvider(LocalDensity provides pageDensity) {
                PageCanvas(
                    state = state,
                    pictures = pictures,
                    canvasOrigin = canvasOrigin,
                    editorFocusRequester = editorFocusRequester,
                    canvasFocusRequester = canvasFocusRequester,
                    modifier = Modifier.weight(1f),
                    onTitleChange = { title -> onStateChange { it.updateSelectedPage(title = title) } },
                    onFocusTextBox = { id -> onStateChange { it.focusTextBox(id) } },
                    onClearCanvasFocus = { onStateChange { it.clearCanvasFocus() } },
                    onCreateTextBox = { x, y ->
                        // Decided on what is on screen: a box placed there is the one that types next.
                        val focusMoves = state.createTextBox(x, y).focusedTextOutlineId != state.focusedTextOutlineId
                        onStateChange { it.createTextBox(x, y) }
                        if (focusMoves) editorFocusRequest++
                    },
                    onMoveTextBox = { id, dx, dy, history -> onStateChange { it.moveTextBox(id, dx, dy, history) } },
                    onMoveSelectedTexts = { dx, dy, history -> onStateChange { it.moveSelectedObjects(dx, dy, history) } },
                    onResizeTextBox = { id, width, height, history ->
                        onStateChange { it.resizeTextBox(id, width, height, history) }
                    },
                    onDeleteTextBox = { id -> onStateChange { it.deleteTextBox(id) } },
                    onPasteCanvas = { x, y -> onStateChange { it.pasteCanvasAt(x, y) } },
                    onSelectObject = { id -> onStateChange { it.selectObject(id) } },
                    onMoveObject = { id, dx, dy, history ->
                        onStateChange { current ->
                            val selected = if (id in current.selectedObjectIds) current else current.selectObject(id)
                            selected.moveSelectedObjects(dx, dy, history)
                        }
                    },
                    onResizeObjects = { ax, ay, sx, sy, history ->
                        onStateChange { it.resizeSelectedObjects(ax, ay, sx, sy, history) }
                    },
                    onCopyObjects = { onStateChange { it.copySelectedObjects() } },
                    onDeleteObjects = { onStateChange { it.deleteSelectedObjects() } },
                    onToggleObjectLock = { onStateChange { it.toggleObjectLock() } },
                    onColorObjects = { argb -> onStateChange { it.colorSelectedObjects(argb) } },
                    onSelectObjectsInRect = { l, t, r, b ->
                        onStateChange { it.selectObjectsInRect(l, t, r, b) }
                    },
                    onToggleTodo = { outlineId, blockId -> onStateChange { it.toggleTodo(outlineId, blockId) } },
                    onIndent = { delta -> onStateChange { it.indentSelectedText(delta) } },
                    onTextCommand = ::applyEditorCommand,
                    onOpenTextMenu = { id, selection ->
                        onStateChange { current ->
                            (if (current.focusedTextOutlineId == id) current else current.focusTextBox(id)).selectText(selection)
                        }
                    },
                    onBodyChange = { id, value ->
                        onStateChange { current ->
                            val focused = if (current.focusedTextOutlineId == id) current else current.focusTextBox(id)
                            focused.editSelectedText(
                                value.text,
                                TextSelection(value.selection.start, value.selection.end),
                                value.composition?.let { TextSelection(it.start, it.end) },
                            )
                        }
                    },
                )
                }
            }
        }
    }
    NavigationDialogs(state, navigationRequests, onRename, onDelete)
}

@Composable
private fun PageCanvas(
    state: WorkspaceState,
    pictures: PictureLibrary?,
    canvasOrigin: CanvasOrigin,
    editorFocusRequester: FocusRequester,
    canvasFocusRequester: FocusRequester,
    onTitleChange: (String) -> Unit,
    onFocusTextBox: (String) -> Unit,
    onClearCanvasFocus: () -> Unit,
    onCreateTextBox: (Float, Float) -> Unit,
    onMoveTextBox: (String, Float, Float, Boolean) -> Unit,
    onMoveSelectedTexts: (Float, Float, Boolean) -> Unit,
    onResizeTextBox: (String, Float?, Float?, Boolean) -> Unit,
    onDeleteTextBox: (String) -> Unit,
    onPasteCanvas: (Float, Float) -> Unit,
    onSelectObject: (String) -> Unit,
    onMoveObject: (String, Float, Float, Boolean) -> Unit,
    onResizeObjects: (Float, Float, Float, Float, Boolean) -> Unit,
    onCopyObjects: () -> Unit,
    onDeleteObjects: () -> Unit,
    onToggleObjectLock: () -> Unit,
    onColorObjects: (Int) -> Unit,
    onSelectObjectsInRect: (Float, Float, Float, Float) -> Unit,
    onToggleTodo: (outlineId: String, blockId: String) -> Unit,
    onIndent: (Int) -> Unit,
    /** Applies a text command and gives the keyboard back to the text box. */
    onTextCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    /** A right-click in a text box: edit that box, with this range selected. */
    onOpenTextMenu: (String, TextSelection) -> Unit,
    onBodyChange: (String, TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = state.selectedPage
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val currentToggleTodo by rememberUpdatedState(onToggleTodo)
    val currentIndent by rememberUpdatedState(onIndent)
    val pictureAssets = rememberPictureAssets(
        page?.document?.outlines.orEmpty().filterIsInstance<Outline.Image>().map { it.attachmentId }.distinct(),
        pictures,
    )
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val shellDark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    var zoom by remember(page?.id) { mutableFloatStateOf(1f) }
    var pendingViewport by remember(page?.id) { mutableStateOf<CanvasViewport?>(null) }
    var pastePoint by remember(page?.id) { mutableStateOf<Offset?>(null) }
    var lasso by remember(page?.id) { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val currentCreate by rememberUpdatedState(onCreateTextBox)
    val currentClearCanvasFocus by rememberUpdatedState(onClearCanvasFocus)
    val currentPaste by rememberUpdatedState(onPasteCanvas)
    val currentSelectObjects by rememberUpdatedState(onSelectObjectsInRect)
    val currentState by rememberUpdatedState(state)
    val measuredTextHeights = remember(page?.id) { mutableStateMapOf<String, Float>() }
    val textLayouts = remember(page?.id) { mutableStateMapOf<String, TextLayoutResult>() }
    val textClipboard = TextClipboardActions(LocalClipboardManager.current, onTextCommand)
    var textMenu by remember(page?.id) { mutableStateOf<TextMenuRequest?>(null) }

    LaunchedEffect(zoom) {
        val requested = pendingViewport ?: return@LaunchedEffect
        withFrameNanos { }
        horizontalScroll.scrollTo(requested.scrollX.roundToInt())
        verticalScroll.scrollTo(requested.scrollY.roundToInt())
        pendingViewport = null
    }

    fun toPage(point: Offset): Offset = with(density) {
        Offset(
            ((point.x + horizontalScroll.value) / zoom).toDp().value,
            ((point.y + verticalScroll.value) / zoom).toDp().value,
        )
    }
    SideEffect { canvasOrigin.read = { toPage(Offset.Zero) } }

    fun hitsContent(point: Offset, snapshot: WorkspaceState): Boolean =
        snapshot.selectedPage?.document?.outlines?.any { outline ->
            val height = if (outline is Outline.Text) measuredTextHeights[outline.id]
                ?: maxOf(150f, outline.minHeight) else outline.primeHeight()
            val focusedText = outline is Outline.Text && snapshot.focusedTextOutlineId == outline.id
            val margin = if (focusedText) 16f else 0f
            val chromeTop = if (focusedText) 32f else if (outline.id in snapshot.selectedObjectIds) 52f else 0f
            point.x >= outline.x - margin && point.x <= outline.x + outline.width + margin &&
                point.y >= outline.y - chromeTop &&
                point.y <= outline.y + height + margin
        } == true

    fun hitsSelectedTransform(point: Offset, snapshot: WorkspaceState): Boolean {
        val outlines = snapshot.selectedPage?.document?.outlines ?: return false
        val textGrip = outlines.filterIsInstance<Outline.Text>().any { text ->
            text.id in snapshot.selectedTextOutlineIds &&
                point.x in text.x..(text.x + 48f) &&
                point.y in (text.y - 32f).coerceAtLeast(0f)..text.y
        }
        val primeBounds = outlines.any { outline ->
            outline.id in snapshot.selectedObjectIds && outline.isPrimeObject() &&
                point.x in (outline.x - 10f)..(outline.x + outline.width + 10f) &&
                point.y in (outline.y - 10f)..(outline.y + outline.primeHeight() + 10f)
        }
        return textGrip || primeBounds
    }

    Box(
        modifier
            .testTag(WorkspaceTestTags.PageCanvas)
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(page?.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = if (change.scrollDelta.y != 0f) change.scrollDelta.y
                                else change.scrollDelta.x
                            val base = pendingViewport ?: CanvasViewport(zoom,
                                horizontalScroll.value.toFloat(), verticalScroll.value.toFloat())
                            val next = base.wheel(delta, change.position.x, change.position.y)
                            if (next.zoom != zoom) {
                                zoom = next.zoom
                                pendingViewport = next
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(page?.id) {
                var previousTapTime = 0L
                var previousTapPoint = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = toPage(down.position)
                    val marquee = !hitsSelectedTransform(start, currentState) &&
                        (currentState.objectLassoArmed ||
                        (!currentState.textToolArmed && start.y >= PageStyle.TITLE_BAND_DP &&
                            !hitsContent(start, currentState)))
                    var last = down
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        if (marquee &&
                            (change.position - down.position).getDistance() >= 12.dp.toPx()) {
                            change.consume()
                            lasso = start to toPage(change.position)
                        }
                    } while (last.pressed)
                    val distance = (last.position - down.position).getDistance()
                    if (marquee && distance >= 12.dp.toPx()) {
                        val a = start
                        val b = toPage(last.position)
                        currentSelectObjects(
                            minOf(a.x, b.x), minOf(a.y, b.y),
                            maxOf(a.x, b.x), maxOf(a.y, b.y),
                        )
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
                        if (!handled && !hitsContent(point, currentState) && y >= PageStyle.TITLE_BAND_DP) {
                            focusManager.clearFocus()
                            canvasFocusRequester.requestFocus()
                            val isDouble = !currentState.canvasClipboard.isEmpty &&
                                last.uptimeMillis - previousTapTime in 1..350 &&
                                (point - previousTapPoint).getDistance() < 32f
                            if (isDouble) {
                                pastePoint = point
                            } else {
                                pastePoint = null
                                if (currentState.textToolArmed) currentCreate(x, y)
                                else currentClearCanvasFocus()
                            }
                            previousTapTime = last.uptimeMillis
                            previousTapPoint = point
                        }
                    }
                }
            },
    ) {
        if (page == null) {
            Text("Choose a page to begin", modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        // A body still being read: for that instant there is nothing to draw and nothing to edit.
        if (page.content == PageContent.Unloaded) return@Box
        Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll)
            .verticalScroll(verticalScroll)) {
            val sheet = page.document.style.pageSizeDp
            val contentRight = page.document.outlines.maxOfOrNull { it.x + it.width } ?: 0f
            val contentBottom = page.document.outlines.maxOfOrNull { outline ->
                outline.y + if (outline is Outline.Text) measuredTextHeights[outline.id]
                    ?: maxOf(150f, outline.minHeight) else outline.primeHeight()
            } ?: 0f
            val sheetFits = sheet != null && contentRight <= sheet.first && contentBottom <= sheet.second
            val canvasWidth = maxOf(2200f, sheet?.first ?: 0f, contentRight + 200f)
            val canvasHeight = maxOf(4500f, sheet?.second ?: 0f, contentBottom + 200f)
            val palette = canvasPalette(page.document.style, shellDark)
            val darkPage = palette.background.luminance() < 0.45f
            val richColors = RichTextColors(
                // Android's `EditorStyle.accentColor`, for bullets, to-do boxes and quote stripes.
                accent = Color(0xFF4CAF50),
                link = if (darkPage) Color(0xFF8AB4F8) else Color(0xFF1A5FB4),
                codeBackground = palette.ink.copy(alpha = 0.1f),
            )
            ZoomedCanvas(zoom) {
            Box(Modifier.requiredSize(canvasWidth.dp, canvasHeight.dp)) {
                CanvasPaper(page.document.style, palette, sheetFits, lasso)
                if (!page.document.style.hideTitle) {
                    Column(Modifier.offset(16.dp, 8.dp).width(720.dp)) {
                        BasicTextField(
                            value = page.title,
                            onValueChange = onTitleChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(color = palette.ink),
                            cursorBrush = SolidColor(palette.ink),
                            decorationBox = { inner ->
                                Box {
                                    if (page.title.isEmpty()) Text("Untitled page",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = palette.secondaryInk)
                                    inner()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                                // Typing a title leaves the text box: its formatting commands must
                                // not go on acting on a box the caret has left.
                                .onFocusChanged { if (it.isFocused) onClearCanvasFocus() }
                                .testTag(WorkspaceTestTags.TitleEditor),
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.width(420.dp).height(1.dp).background(palette.rule))
                        Spacer(Modifier.height(6.dp))
                        Text(page.createdLabel, style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryInk)
                    }
                }
                page.document.outlines.filterIsInstance<Outline.Text>().forEachIndexed { index, outline ->
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
                    val currentMoveSelectedTexts by rememberUpdatedState(onMoveSelectedTexts)
                    val currentResize by rememberUpdatedState(onResizeTextBox)
                    if (lassoSelected && state.selectedObjectIds.isEmpty() &&
                        outline.id == state.selectedTextOutlineIds.firstOrNull()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 4.dp,
                            modifier = Modifier.offset(x, (y - 76.dp).coerceAtLeast(0.dp)),
                        ) {
                            Row(Modifier.padding(3.dp)) {
                                TooltipIconButton("Copy selected text boxes", onClick = onCopyObjects, tooltipPosition = TooltipAnchorPosition.Above,
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectCopy)) {
                                    Icon(DocumentSymbols.ContentCopy, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                }
                                TooltipIconButton("Delete selected text boxes", onClick = onDeleteObjects, tooltipPosition = TooltipAnchorPosition.Above,
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectDelete)) {
                                    Icon(ObjectSymbols.Delete, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    if (showChrome || lassoSelected) {
                        Box(
                            modifier = Modifier.offset(x, (y - 32.dp).coerceAtLeast(0.dp))
                                .size(48.dp, 32.dp)
                                .testTag(WorkspaceTestTags.textGrip(outline.id))
                                .semantics { contentDescription = "Move text box" }
                                .pointerInput(outline.id, lassoSelected) {
                                    var firstChange = true
                                    detectDragGestures(
                                        onDragStart = { firstChange = true },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            val dx = with(density) { drag.x.toDp().value }
                                            val dy = with(density) { drag.y.toDp().value }
                                            if (lassoSelected) currentMoveSelectedTexts(dx, dy, firstChange)
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
                            // Ours in place of the text field's own menu, which it never sees.
                            .onSecondaryPress(outline.id) { position ->
                                val snapshot = currentState
                                val inset = with(density) { TextBoxPadding.toPx() }
                                val selection = selectionForRightClick(
                                    current = snapshot.editorSelection.takeIf { snapshot.focusedTextOutlineId == outline.id },
                                    layout = textLayouts[outline.id],
                                    point = position - Offset(inset, inset),
                                )
                                onOpenTextMenu(outline.id, selection)
                                textMenu = TextMenuRequest(outline.id, position, selection)
                            }
                            .testTag(WorkspaceTestTags.textBox(outline.id)),
                    ) {
                        if (focused || lassoSelected) Box(Modifier.matchParentSize()
                            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            .testTag(WorkspaceTestTags.textBoxOutline(outline.id)))
                        BasicTextField(
                            value = TextFieldValue(
                                annotatedString = richText?.asAnnotatedString(richColors) ?: buildAnnotatedString {},
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
                                            if (todo == null && link == null) return@awaitEachGesture
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
                                .padding(TextBoxPadding)
                                // Tab indents, as on Android: in a note, indenting is what a writer means.
                                .onPreviewKeyEvent { event ->
                                    // Copy and paste keep formatting, which the field's own would drop.
                                    if (textClipboard.onShortcut(event, currentState)) return@onPreviewKeyEvent true
                                    if (event.key != Key.Tab) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) currentIndent(if (event.isShiftPressed) -1 else 1)
                                    true
                                }
                                .then(if (focused) Modifier.focusRequester(editorFocusRequester) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused && !focused) onFocusTextBox(outline.id)
                                }
                                .semantics { contentDescription = "Text box ${index + 1}" }
                                .testTag(if (index == 0) WorkspaceTestTags.BodyEditor else WorkspaceTestTags.textBox(outline.id) + "-editor"),
                        )
                        TextContextMenu(
                            request = textMenu?.takeIf { it.outlineId == outline.id },
                            state = state,
                            clipboard = textClipboard,
                            onEditorCommand = onTextCommand,
                            onDeleteBox = onDeleteTextBox,
                            onClose = { textMenu = null },
                        )
                    }
                    if (showChrome) {
                        Box(Modifier.offset(x + width - 12.dp,
                            y + renderedHeight - 12.dp).size(24.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                            .testTag(WorkspaceTestTags.textResizeHandle(outline.id))
                            .semantics { contentDescription = "Resize text box" }
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
                val currentMoveObject by rememberUpdatedState(onMoveObject)
                val currentSelectObject by rememberUpdatedState(onSelectObject)
                val currentResizeObjects by rememberUpdatedState(onResizeObjects)
                page.document.outlines.filter { it.isPrimeObject() }.forEach { outline ->
                    val selected = outline.id in state.selectedObjectIds
                    val x = outline.x.dp
                    val y = outline.y.dp
                    val width = outline.width.coerceAtLeast(24f).dp
                    val height = outline.primeHeight().coerceAtLeast(24f).dp
                    val label = when (outline) {
                        is Outline.Shape -> "Shape"
                        is Outline.Table -> "Table"
                        is Outline.Equation -> "Equation: ${outline.latex}"
                        is Outline.Image -> "Picture"
                        else -> "Object"
                    }
                    // A picture sits on the page itself, so its transparent parts show the paper;
                    // only a picture that cannot be drawn gets a plate (see PictureContent).
                    val drawnPicture = outline is Outline.Image && pictures != null
                    Surface(
                        color = if (drawnPicture) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small,
                        tonalElevation = if (drawnPicture) 0.dp else if (selected) 3.dp else 1.dp,
                        modifier = Modifier.offset(x, y).size(width, height)
                            .testTag(WorkspaceTestTags.primeObject(outline.id))
                            // A picture shows its own edges; only its selection is outlined.
                            .then(if (outline is Outline.Image && !selected) Modifier else Modifier.border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                MaterialTheme.shapes.small))
                            .pointerInput(outline.id) {
                                var firstChange = true
                                detectDragGestures(
                                    onDragStart = {
                                        firstChange = true
                                        if (outline.id !in currentState.selectedObjectIds)
                                            currentSelectObject(outline.id)
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        currentMoveObject(outline.id,
                                            with(density) { drag.x.toDp().value },
                                            with(density) { drag.y.toDp().value }, firstChange)
                                        firstChange = false
                                    },
                                )
                            }
                            .clickable {
                                focusManager.clearFocus()
                                canvasFocusRequester.requestFocus()
                                onSelectObject(outline.id)
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (outline is Outline.Image && pictures != null) {
                                PictureContent(pictureAssets[outline.attachmentId])
                            } else {
                                Text(label, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (selected && state.selectedTextOutlineIds.isEmpty()) {
                        val corners = listOf(
                            x to y, x + width to y, x to y + height, x + width to y + height,
                        )
                        corners.forEachIndexed { index, (cx, cy) ->
                            val anchorX = if (index % 2 == 0) outline.x + outline.width else outline.x
                            val anchorY = if (index < 2) outline.y + outline.primeHeight() else outline.y
                            Box(Modifier.offset(cx - 7.dp, cy - 7.dp).size(14.dp)
                                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                                .testTag(WorkspaceTestTags.objectCorner(outline.id, index))
                                .semantics { contentDescription = "Resize $label" }
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
                }
                if (state.selectedObjectIds.isNotEmpty()) {
                    val selected = page.document.outlines.firstOrNull { it.id in state.selectedObjectIds }
                    if (selected != null) {
                        var colorMenu by remember(selected.id) { mutableStateOf(false) }
                        Surface(color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp,
                            modifier = Modifier.offset(selected.x.dp,
                                (selected.y.dp - 52.dp).coerceAtLeast(0.dp))) {
                            Row(Modifier.padding(3.dp)) {
                                if (selected !is Outline.Image && state.selectedTextOutlineIds.isEmpty()) {
                                    Box {
                                        val swatch = when (selected) {
                                            is Outline.Shape -> Color(selected.borderArgb)
                                            is Outline.Table -> Color(selected.borderArgb)
                                            is Outline.Equation -> Color(selected.colorArgb ?: 0xFF000000.toInt())
                                            else -> Color.Black
                                        }
                                        TooltipIconButton("Change object colour", onClick = { colorMenu = true },
                                            tooltipPosition = TooltipAnchorPosition.Above,
                                            modifier = Modifier.size(40.dp)
                                                .testTag(WorkspaceTestTags.ObjectColor)) {
                                            Box(Modifier.size(18.dp).clip(CircleShape).background(swatch))
                                        }
                                        DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                                            ObjectColors.chunked(5).forEach { colors ->
                                                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                    colors.forEach { (name, argb) ->
                                                        HoverTooltip(name) {
                                                            Box(Modifier.size(40.dp).padding(4.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(argb))
                                                                .clickable(role = Role.Button) {
                                                                    colorMenu = false
                                                                    onColorObjects(argb)
                                                                }
                                                                .semantics { contentDescription = name })
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                TooltipIconButton("Copy selection", onClick = onCopyObjects, tooltipPosition = TooltipAnchorPosition.Above,
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectCopy)) {
                                    Icon(DocumentSymbols.ContentCopy, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                }
                                if (state.selectedTextOutlineIds.isEmpty()) {
                                    TooltipIconButton(if (state.selectedObjectsLocked) "Unlock selection" else "Lock selection",
                                        onClick = onToggleObjectLock, tooltipPosition = TooltipAnchorPosition.Above,
                                        modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectLock)) {
                                        Icon(if (state.selectedObjectsLocked) ObjectSymbols.Lock
                                            else ObjectSymbols.LockOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp))
                                    }
                                }
                                TooltipIconButton("Delete selection", onClick = onDeleteObjects, tooltipPosition = TooltipAnchorPosition.Above,
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectDelete)) {
                                    Icon(ObjectSymbols.Delete, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                pastePoint?.let { point ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.offset(point.x.dp, point.y.dp),
                    ) {
                        TextButton(onClick = {
                            currentPaste(point.x, point.y)
                            pastePoint = null
                        }, modifier = Modifier.testTag(WorkspaceTestTags.CanvasPaste)) { Text("Paste") }
                    }
                }
            }
            }
        }
        if (page.content == PageContent.Unreadable) {
            StatusBanner(
                UnreadablePageMessage,
                Modifier.align(Alignment.TopCenter).testTag(WorkspaceTestTags.UnreadablePage),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                .testTag(WorkspaceTestTags.ZoomIndicator)
                .semantics { contentDescription = "Canvas zoom ${(zoom * 100).roundToInt()} percent" },
        ) {
            Text("${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** Between a text box's edge and its text. */
private val TextBoxPadding = 8.dp

/** Android's wording, for a page whose stored body could not be decoded. */
internal const val UnreadablePageMessage =
    "This page could not be read, so editing is disabled to protect its contents."

/**
 * Tells the user that notes storage failed, for as long as it keeps failing; the next read or
 * write that succeeds takes it away. Motion comes from the theme, like the panes'.
 */
@Composable
private fun StorageErrorBanner(message: String?) {
    // Kept through the exit animation, which still has to show what it is taking away.
    var shown by remember { mutableStateOf(message.orEmpty()) }
    if (message != null) shown = message
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
            fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
    ) {
        StatusBanner(shown, Modifier.fillMaxWidth().testTag(WorkspaceTestTags.StorageError))
    }
}

@Composable
private fun StatusBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // One announcement, read out when it appears or changes.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Zoom participates in layout so both scroll axes can reach the whole scaled page. */
@Composable
private fun ZoomedCanvas(zoom: Float, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, _ ->
        val placeable = measurables.first().measure(Constraints())
        layout((placeable.width * zoom).roundToInt(), (placeable.height * zoom).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = zoom
                scaleY = zoom
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** The page point at the canvas's visible top left, read when a picture is inserted. */
internal class CanvasOrigin {
    var read: () -> Offset = { Offset.Zero }
}
