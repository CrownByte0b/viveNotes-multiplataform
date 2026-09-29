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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt
import com.vivenotes.workspace.CanvasViewport
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.model.Mark
import com.vivenotes.richtext.LinkTarget
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.ui.canvas.BlockSeparators
import com.vivenotes.ui.canvas.PictureContent
import com.vivenotes.ui.canvas.TextBoxPreviews
import com.vivenotes.ui.canvas.storedEquationPreviews
import com.vivenotes.ui.canvas.InkLayer
import com.vivenotes.ui.canvas.contentEdge
import com.vivenotes.ui.canvas.RichTextColors
import com.vivenotes.ui.ribbon.document.LinkEditorDialog
import com.vivenotes.ui.ribbon.document.LinkEditorRequest
import com.vivenotes.ui.canvas.asAnnotatedString
import com.vivenotes.ui.canvas.drawBlockDecorations
import com.vivenotes.ui.canvas.linkAtPoint
import com.vivenotes.ui.canvas.rememberPictureAssets
import com.vivenotes.ui.canvas.todoAt
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.LocalPopupLayerDensity
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.ribbon.document.DocumentTab
import com.vivenotes.ui.ribbon.document.DocumentColorSelection
import com.vivenotes.ui.ribbon.draw.DrawRibbon
import com.vivenotes.ui.ribbon.file.FileRibbon
import com.vivenotes.ui.ribbon.file.FilePaneContent
import com.vivenotes.ui.ribbon.file.FileConfirmDialog
import com.vivenotes.ui.ribbon.file.FilePaneTags
import com.vivenotes.ui.ribbon.file.NotebookTransferDialog
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane
import com.vivenotes.ui.ribbon.settings.SettingsRibbon
import com.vivenotes.ui.account.AccountScreen
import com.vivenotes.ui.account.AccountScreenModel
import com.vivenotes.ui.account.AccountService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.vivenotes.ui.ribbon.settings.InterfaceDialog
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.ribbon.view.PaperSizePane
import com.vivenotes.ui.ribbon.view.ViewTab
import com.vivenotes.ui.ribbon.view.viewActions
import com.vivenotes.ui.canvas.PageExtent
import com.vivenotes.ui.canvas.documentExtent
import com.vivenotes.ui.navigation.SectionTabsBar
import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.EditorDefaults
import com.vivenotes.workspace.titleFloor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.canvas.TextContextMenu
import com.vivenotes.ui.canvas.TextMenuRequest
import com.vivenotes.ui.canvas.selectionForRightClick
import com.vivenotes.ui.canvas.selectedCanvasBounds
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.navigation.NavigationDialogs
import com.vivenotes.ui.navigation.NavigationRequests
import com.vivenotes.ui.navigation.NotebookPane
import com.vivenotes.ui.navigation.PageListPane
import com.vivenotes.workspace.InMemoryNavigation
import com.vivenotes.workspace.NavigationActions
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.input.key.KeyEvent
import com.vivenotes.ui.keyboard.LocalKeyBindings
import com.vivenotes.ui.keyboard.ShortcutKeys
import com.vivenotes.ui.ribbon.settings.HardwarePane
import com.vivenotes.ui.ribbon.settings.ResetAllShortcutsDialog
import com.vivenotes.ui.ribbon.settings.ShortcutCaptureDialog
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutScope

private val ObjectColors = listOf(
    "White" to 0xFFFFFFFF.toInt(), "Black" to 0xFF000000.toInt(),
    "Gray" to 0xFF6B7280.toInt(), "Red" to 0xFFEF4444.toInt(),
    "Orange" to 0xFFF59E0B.toInt(), "Green" to 0xFF22C55E.toInt(),
    "Cyan" to 0xFF06B6D4.toInt(), "Blue" to 0xFF3B82F6.toInt(),
    "Purple" to 0xFF8B5CF6.toInt(), "Pink" to 0xFFEC4899.toInt(),
)

/** Stable semantics identifiers used by desktop UI tests and future accessibility automation. */
object WorkspaceTestTags {
    const val HeaderBar = "workspace-header-bar"
    const val RibbonBar = "workspace-ribbon-bar"
    const val NavigationToggle = "workspace-navigation-toggle"
    const val NotebookPane = "workspace-notebook-pane"
    const val PagePane = "workspace-page-pane"
    const val PageCanvas = "workspace-page-canvas"
    const val CanvasBackground = "workspace-canvas-background"
    /** Everything that can be scrolled to; on an infinite page it grows as it is scrolled. */
    const val CanvasExtent = "workspace-canvas-extent"
    /** The sheet while it holds all the content and so is the page's edge. */
    const val PageSheet = "workspace-page-sheet"
    /** The dashed outline of a sheet the content has outgrown. */
    const val SheetGuide = "workspace-sheet-guide"
    const val ZoomIndicator = "workspace-zoom-indicator"
    const val AddPage = "workspace-add-page"
    const val TitleEditor = "workspace-title-editor"
    const val BodyEditor = "workspace-body-editor"
    const val CanvasPaste = "workspace-canvas-paste"
    const val ObjectCopy = "workspace-object-copy"
    const val ObjectDelete = "workspace-object-delete"
    const val ObjectLock = "workspace-object-lock"
    const val ObjectColor = "workspace-object-color"
    const val GroupSelectionFrame = "workspace-group-selection-frame"
    const val StructuralUndo = "workspace-structural-undo"
    const val StructuralRedo = "workspace-structural-redo"
    const val StorageError = "workspace-storage-error"
    const val UnreadablePage = "workspace-unreadable-page"
    fun primeObject(id: String): String = "workspace-prime-object-$id"
    fun objectCorner(id: String, corner: Int): String = "workspace-object-corner-$id-$corner"
    fun groupCorner(corner: Int): String = "workspace-group-corner-$corner"
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
    /**
     * What the notebook and page panes ask for — new, renamed, deleted, folded and reordered items.
     * A stored workspace passes its session, which also stores them.
     */
    navigation: NavigationActions = InMemoryNavigation(onStateChange),
    /** Where pictures are stored; without it the Picture command is unavailable. */
    pictures: PictureLibrary? = null,
    thumbnails: VideoThumbnailSource? = null,
    // Standalone workspace callers retain the unscaled layout; App supplies the user's default.
    interfaceSettings: InterfaceSettings = InterfaceSettings(displayScale = 1f),
    onInterfaceSettingsChange: (InterfaceSettings) -> Unit = {},
    /** The host's system theme, used when Reset removes an explicit choice. */
    systemDarkTheme: Boolean? = null,
    /** This device's View settings — zoom, tabs layout, canvas brightness — and where changes go. */
    viewSettings: ViewSettings = ViewSettings(),
    onViewSettingsChange: (ViewSettings) -> Unit = {},
    onEditorDefaultsChange: (EditorDefaults) -> Unit = {},
    /** The keyboard shortcuts in force, and where Settings → Hardware sends changes to them. */
    keyBindings: KeyBindings = KeyBindings.Default,
    onKeyBindingsChange: (KeyBindings) -> Unit = {},
    /** The File tab's `.vive` export and import; without it those commands are unavailable. */
    fileActions: FileActions? = null,
    accountService: AccountService? = null,
) {
    var previewSettings by remember { mutableStateOf<InterfaceSettings?>(null) }
    // Held here as well, so a caller that does not keep the settings still sees its changes.
    var view by remember(viewSettings) { mutableStateOf(viewSettings.normalized()) }
    var bindings by remember(keyBindings) { mutableStateOf(keyBindings) }
    var accountOpen by remember { mutableStateOf(false) }
    val accountScope = rememberCoroutineScope()
    val accountModel = remember(accountService) { AccountScreenModel(accountService, accountScope) }
    val accountSession = accountService?.session?.collectAsState()?.value
    val baseDensity = LocalDensity.current
    val effectiveSettings = previewSettings ?: interfaceSettings
    val pageDensity = effectiveSettings.documentDensity(baseDensity)
    // Keys reach only the focused element and what contains it, so the workspace is never left
    // without one: at launch, and whenever the focused element goes (a discarded text box, a
    // closed dialog), the canvas takes the focus and the keyboard shortcuts keep working.
    val canvasFocusRequester = remember { FocusRequester() }
    var workspaceHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(workspaceHasFocus) {
        if (workspaceHasFocus) return@LaunchedEffect
        withFrameNanos { }
        canvasFocusRequester.requestFocus()
    }
    val darkTheme = effectiveSettings.darkTheme ?: systemDarkTheme ?:
        (MaterialTheme.colorScheme.background.luminance() < 0.45f)
    ViveNotesTheme(darkTheme = darkTheme) {
        Box(modifier.fillMaxSize().onFocusChanged { workspaceHasFocus = it.hasFocus }.focusGroup()) {
            CompositionLocalProvider(
                LocalDensity provides effectiveSettings.density(baseDensity),
                LocalPopupLayerDensity provides baseDensity,
                LocalKeyBindings provides bindings,
            ) {
                WorkspaceContent(state, onStateChange, Modifier.fillMaxSize(), navigation, pictures, thumbnails,
                    onInterface = { previewSettings = interfaceSettings }, pageDensity = pageDensity,
                    view = view, themePreference = effectiveSettings.darkTheme, onViewChange = { next ->
                        view = next
                        onViewSettingsChange(next)
                    }, bindings = bindings, onBindingsChange = { next ->
                        bindings = next
                        onKeyBindingsChange(next)
                    }, canvasFocusRequester = canvasFocusRequester, fileActions = fileActions,
                    onEditorDefaultsChange = onEditorDefaultsChange,
                    accountConnected = accountSession != null, onOpenAccount = { accountOpen = true })
            }
            if (accountOpen) AccountScreen(accountService, accountModel, onBack = { accountOpen = false })
            previewSettings?.let { draft ->
                InterfaceDialog(
                    settings = draft,
                    onChange = { previewSettings = it.normalized() },
                    onApply = {
                        val chosen = previewSettings ?: draft
                        if (chosen.darkTheme != interfaceSettings.darkTheme && view.canvasDark != null) {
                            val unpinned = view.copy(canvasDark = null, canvasThemeDark = null)
                            view = unpinned
                            onViewSettingsChange(unpinned)
                        }
                        onInterfaceSettingsChange(chosen)
                        previewSettings = null
                    },
                    onDismiss = { previewSettings = null },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceContent(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    modifier: Modifier,
    navigation: NavigationActions,
    pictures: PictureLibrary?,
    thumbnails: VideoThumbnailSource?,
    onInterface: () -> Unit,
    pageDensity: Density,
    view: ViewSettings,
    themePreference: Boolean?,
    onViewChange: (ViewSettings) -> Unit,
    bindings: KeyBindings,
    onBindingsChange: (KeyBindings) -> Unit,
    /** The workspace's own focus, which holds the keyboard when no control does. */
    canvasFocusRequester: FocusRequester,
    fileActions: FileActions?,
    onEditorDefaultsChange: (EditorDefaults) -> Unit,
    accountConnected: Boolean,
    onOpenAccount: () -> Unit,
) {
    val canvasOrigin = remember { CanvasOrigin() }
    val canvasControl = remember { CanvasViewControl() }
    var openPane by remember { mutableStateOf<DockedPane?>(null) }
    var notebookConfirmation by remember { mutableStateOf<String?>(null) }
    var confirmRevision by remember { mutableStateOf(false) }
    fun togglePane(pane: DockedPane) {
        openPane = if (openPane == pane) null else pane
    }
    var editingShortcut by remember { mutableStateOf<ShortcutAction?>(null) }
    var linkEditor by remember { mutableStateOf<LinkEditorRequest?>(null) }
    var confirmResetShortcuts by remember { mutableStateOf(false) }
    val currentView by rememberUpdatedState(view)
    val currentThemePreference by rememberUpdatedState(themePreference)
    val currentOnViewChange by rememberUpdatedState(onViewChange)
    val viewActions = remember(onStateChange) {
        viewActions(onStateChange, { currentView }, { currentOnViewChange(it) }, canvasControl,
            onTogglePaperSizePane = { togglePane(DockedPane.PaperSize) },
            themePreference = { currentThemePreference })
    }
    val canvasDark = view.canvasDarkForTheme(themePreference,
        MaterialTheme.colorScheme.background.luminance() < 0.45f)
    val horizontalTabs = view.tabsLayout == TabsLayout.Horizontal
    val documentColorSelection = remember { DocumentColorSelection() }
    val editorFocusRequester = remember { FocusRequester() }
    var editorFocusRequest by remember { mutableIntStateOf(0) }
    val navigationRequests = remember { NavigationRequests() }
    fun applyEditorCommand(transform: (WorkspaceState) -> WorkspaceState) {
        onStateChange(transform)
        editorFocusRequest++
    }
    LaunchedEffect(editorFocusRequest) {
        if (editorFocusRequest > 0) editorFocusRequester.requestFocus()
    }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.selectedPageId, state.filePane.pane) {
        if (state.filePane.pane == FilePane.VersionHistory &&
            state.filePane.revisionPageId != null && state.filePane.revisionPageId != state.selectedPageId) {
            fileActions?.openPane(FilePane.VersionHistory)
        }
    }
    val currentBindings by rememberUpdatedState(bindings)
    val shortcutKeys = remember { ShortcutKeys() }
    val shortcuts = WorkspaceShortcuts({ currentState }, onStateChange, ::applyEditorCommand, navigation,
        viewActions, TextClipboardActions(LocalClipboardManager.current, ::applyEditorCommand))
    Box(modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Shortcuts are dispatched from one table (Settings → Hardware lists and rebinds it):
            // here ahead of the focused control, after it below, and in the text box being edited.
            .onPreviewKeyEvent { event ->
                shortcutKeys.observe(event) ||
                    shortcutKeys.dispatch(event, currentBindings, ShortcutScope.Anywhere, shortcuts::run)
            }
            .onKeyEvent { event ->
                shortcutKeys.dispatch(event, currentBindings, ShortcutScope.Workspace, shortcuts::run)
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
            accountConnected = accountConnected,
            onOpenAccount = onOpenAccount,
        )
        // Each tab's buttons, and what they do, live in that tab's package under `ui/ribbon`.
        when (state.activeTab) {
            RibbonTab.File -> FileRibbon(notebookOpen = state.selectedSection != null,
                pageOpen = state.selectedPage != null,
                transferRunning = state.notebookTransfer.running, actions = fileActions,
                onCloseNotebook = { notebookConfirmation = "close" },
                onDeleteNotebook = { notebookConfirmation = "delete" })
            RibbonTab.Draw -> DrawRibbon(state, onStateChange)
            RibbonTab.Document -> DocumentTab(state, onStateChange, ::applyEditorCommand, pictures,
                { canvasOrigin.read() }, documentColorSelection, onLinkRequest = { linkEditor = it },
                onEditorDefaultsChange = onEditorDefaultsChange)
            RibbonTab.View -> ViewTab(state, view, canvasDark, viewActions,
                onDefaultRuleLines = { rule ->
                    val next = state.editorDefaults.copy(ruleLines = rule)
                    onStateChange { it.setEditorDefaults(next) }
                    onEditorDefaultsChange(next)
                })
            RibbonTab.Settings -> SettingsRibbon(onInterface, hardwareOpen = openPane == DockedPane.Hardware,
                onHardware = { togglePane(DockedPane.Hardware) }, linkPreviews = view.linkPreviews,
                onLinkPreviewsChange = { enabled -> onViewChange(view.copy(linkPreviews = enabled)) })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Horizontal Tabs Layout: the notebook pane's selection as a strip of section tabs.
        if (horizontalTabs && state.navigationVisible) {
            SectionTabsBar(
                state = state,
                requests = navigationRequests,
                onSelectNotebook = { id -> onStateChange { it.selectNotebook(id) } },
                onSelectSection = { id -> onStateChange { it.selectSection(id) } },
            )
        }
        StorageErrorBanner(state.storageError)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showNotebookPane = maxWidth >= 1040.dp && state.navigationVisible && !horizontalTabs
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
                        navigation = navigation,
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
                        navigation = navigation,
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
                    thumbnails = thumbnails.takeIf { view.linkPreviews },
                    canvasOrigin = canvasOrigin,
                    canvasControl = canvasControl,
                    zoom = view.zoom,
                    onZoomChange = { zoom -> currentOnViewChange(currentView.copy(zoom = zoom).normalized()) },
                    canvasDark = canvasDark,
                    showMargins = openPane == DockedPane.PaperSize,
                    onTextShortcut = { event ->
                        shortcutKeys.dispatch(event, currentBindings, ShortcutScope.TextBox, shortcuts::run)
                    },
                    editorFocusRequester = editorFocusRequester,
                    canvasFocusRequester = canvasFocusRequester,
                    modifier = Modifier.weight(1f),
                    onTitleChange = { title -> onStateChange { it.updateSelectedPage(title = title) } },
                    onFocusTextBox = { id ->
                        onStateChange { it.focusTextBox(id) }
                        editorFocusRequest++
                    },
                    onClearCanvasFocus = { onStateChange { it.clearCanvasFocus() } },
                    onCreateTextBox = { x, y ->
                        // Decided on what is on screen: a box placed there is the one that types next.
                        val focusMoves = state.createTextBox(x, y).focusedTextOutlineId != state.focusedTextOutlineId
                        onStateChange { it.createTextBox(x, y) }
                        if (focusMoves) editorFocusRequest++
                    },
                    onMoveTextBox = { id, dx, dy, history -> onStateChange { it.moveTextBox(id, dx, dy, history) } },
                    onMoveSelection = { dx, dy, history -> onStateChange { it.moveSelectedObjects(dx, dy, history) } },
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
                    onTextCommand = ::applyEditorCommand,
                    onTextLinkRequest = { id, selection, target ->
                        linkEditor = LinkEditorRequest(selection, target) { label, url, captured ->
                            applyEditorCommand { current ->
                                current.focusTextBox(id).selectText(captured ?: selection).insertLink(label, url)
                            }
                        }
                    },
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
                if (state.filePane.pane != null && fileActions != null) {
                    VerticalDivider(modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant)
                    FilePaneContent(state.filePane, fileActions, onRestoreRevision = { confirmRevision = true })
                } else openPane?.let { pane ->
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    when (pane) {
                        DockedPane.PaperSize -> {
                            val page = state.selectedPage?.takeIf { it.editable }
                            PaperSizePane(
                                style = page?.document?.style ?: PageStyle(),
                                enabled = page != null,
                                actions = viewActions,
                                onClose = { openPane = null },
                                defaultPaper = state.editorDefaults.paper,
                                onDefaultPaper = { paper ->
                                    val next = state.editorDefaults.copy(paper = paper,
                                        customPaper = if (paper == com.vivenotes.model.PaperSize.Custom)
                                            state.selectedPage?.document?.style?.customPaper else state.editorDefaults.customPaper)
                                    onStateChange { it.setEditorDefaults(next) }
                                    onEditorDefaultsChange(next)
                                },
                            )
                        }
                        DockedPane.Hardware -> HardwarePane(
                            bindings = bindings,
                            onEdit = { editingShortcut = it },
                            onReset = { onBindingsChange(bindings.reset(it)) },
                            onResetAll = { confirmResetShortcuts = true },
                            onClose = { openPane = null },
                        )
                    }
                }
            }
        }
    }
    NavigationDialogs(state, navigationRequests, navigation)
    linkEditor?.let { request -> LinkEditorDialog(request, onDismiss = { linkEditor = null }) }
    NotebookTransferDialog(state.notebookTransfer, onDismiss = { fileActions?.dismissTransfer() })
    if (confirmRevision) {
        FileConfirmDialog("Restore this version?",
            "The saved version will replace this page. Your current version will be kept in history.",
            "Restore", onDismiss = { confirmRevision = false }, onConfirm = {
                confirmRevision = false
                fileActions?.restoreRevision()
            }, tag = FilePaneTags.ConfirmRestore)
    }
    notebookConfirmation?.let { operation ->
        val notebook = state.notebooks.firstOrNull { entry ->
            entry.sections.any { it.id == state.selectedSectionId }
        }
        if (notebook == null) {
            SideEffect { notebookConfirmation = null }
        } else {
            FileConfirmDialog(
                title = "${if (operation == "close") "Close" else "Delete"} ${notebook.name}?",
                detail = if (operation == "close")
                    "Nothing is deleted. The notebook leaves the panel until you reopen it from Closed Notebooks."
                else "This notebook, its sections and all of their pages will be deleted. Written pages can be restored from Deleted Items for 7 days.",
                verb = if (operation == "close") "Close" else "Delete",
                destructive = operation == "delete",
                onDismiss = { notebookConfirmation = null },
                onConfirm = {
                    notebookConfirmation = null
                    if (operation == "close") fileActions?.closeNotebook() else fileActions?.deleteNotebook()
                },
            )
        }
    }
    editingShortcut?.let { action ->
        ShortcutCaptureDialog(
            action = action,
            bindings = bindings,
            onSet = { chord ->
                onBindingsChange(bindings.rebind(action, chord))
                editingShortcut = null
            },
            onDismiss = { editingShortcut = null },
        )
    }
    if (confirmResetShortcuts) {
        ResetAllShortcutsDialog(
            onConfirm = {
                onBindingsChange(bindings.resetAll())
                confirmResetShortcuts = false
            },
            onDismiss = { confirmResetShortcuts = false },
        )
    }
    }
}

/** The settings pane docked right of the canvas: one at a time, as on Android. */
internal enum class DockedPane { PaperSize, Hardware }

@Composable
private fun PageCanvas(
    state: WorkspaceState,
    pictures: PictureLibrary?,
    thumbnails: VideoThumbnailSource?,
    canvasOrigin: CanvasOrigin,
    canvasControl: CanvasViewControl,
    zoom: Float,
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
    onCreateTextBox: (Float, Float) -> Unit,
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
    onToggleObjectLock: () -> Unit,
    onColorObjects: (Int) -> Unit,
    onSelectObjectsInRect: (Float, Float, Float, Float) -> Unit,
    onToggleTodo: (outlineId: String, blockId: String) -> Unit,
    /** Applies a text command and gives the keyboard back to the text box. */
    onTextCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onTextLinkRequest: (String, TextSelection, LinkTarget) -> Unit,
    /** A right-click in a text box: edit that box, with this range selected. */
    onOpenTextMenu: (String, TextSelection) -> Unit,
    onBodyChange: (String, TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = state.selectedPage
    val uriHandler = LocalUriHandler.current
    val textMeasurer = rememberTextMeasurer()
    val currentToggleTodo by rememberUpdatedState(onToggleTodo)
    val currentTextShortcut by rememberUpdatedState(onTextShortcut)
    val pictureAssets = rememberPictureAssets(
        page?.document?.outlines.orEmpty().filterIsInstance<Outline.Image>().map { it.attachmentId }.distinct(),
        pictures,
    )
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    // Zoom is this device's View setting, kept across pages; read through this in pointer handlers,
    // which outlive the composition that created them.
    val zoomState = rememberUpdatedState(zoom)
    val currentZoomChange by rememberUpdatedState(onZoomChange)
    val zoomTracking = remember { ZoomTracking(zoom) }
    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    var pendingViewport by remember(page?.id) { mutableStateOf<CanvasViewport?>(null) }
    var pastePoint by remember(page?.id) { mutableStateOf<Offset?>(null) }
    var lasso by remember(page?.id) { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var panActive by remember(page?.id) { mutableStateOf(false) }
    var panAnchor by remember(page?.id) { mutableStateOf(Offset.Zero) }
    var panPointer by remember(page?.id) { mutableStateOf(Offset.Zero) }
    val panOffset = panPointer - panAnchor
    val panCursorDirection = panDirection(panOffset.x, panOffset.y)
    ApplyPanCursorWhilePressed(panActive, panCursorDirection)
    val currentCreate by rememberUpdatedState(onCreateTextBox)
    val currentClearCanvasFocus by rememberUpdatedState(onClearCanvasFocus)
    val currentPaste by rememberUpdatedState(onPasteCanvas)
    val currentSelectObjects by rememberUpdatedState(onSelectObjectsInRect)
    val currentMoveSelection by rememberUpdatedState(onMoveSelection)
    val currentState by rememberUpdatedState(state)
    val measuredTextHeights = remember(page?.id) { mutableStateMapOf<String, Float>() }
    val textLayouts = remember(page?.id) { mutableStateMapOf<String, TextLayoutResult>() }
    val textClipboard = TextClipboardActions(LocalClipboardManager.current, onTextCommand)
    // The text box whose field holds the keyboard. Esc and a tap on the page end the editing but
    // not the field's focus, and the field would go on taking keys — Ctrl+Z as its own text undo,
    // and typing — so once its box is no longer the one being edited, the canvas takes the focus.
    var keyboardTextBox by remember(page?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(keyboardTextBox, state.focusedTextOutlineId) {
        val holder = keyboardTextBox ?: return@LaunchedEffect
        if (holder == currentState.focusedTextOutlineId) return@LaunchedEffect
        withFrameNanos { }
        if (keyboardTextBox == holder && holder != currentState.focusedTextOutlineId) {
            canvasFocusRequester.requestFocus()
        }
    }
    var textMenu by remember(page?.id) { mutableStateOf<TextMenuRequest?>(null) }

    LaunchedEffect(zoom) {
        val previous = zoomTracking.applied
        zoomTracking.applied = zoom
        // The wheel asks for its own cursor-anchored scroll. A change from the ribbon keeps the
        // middle of the window still, except on an axis scrolled to its start, whose page edge
        // stays in view — zooming at the top of a page must not scroll its title away. Page Width
        // also brings the page's left edge to the window's.
        val requested = pendingViewport?.takeIf { it.zoom == zoom } ?: if (previous == zoom) null else {
            val alignLeft = canvasControl.alignLeftOnNextZoom
            val scrollX = horizontalScroll.value.toFloat()
            val scrollY = verticalScroll.value.toFloat()
            val anchored = CanvasViewport(previous, scrollX, scrollY).zoomTo(zoom,
                if (alignLeft || scrollX == 0f) 0f else viewportPx.width / 2f,
                if (scrollY == 0f) 0f else viewportPx.height / 2f)
            if (alignLeft) anchored.copy(scrollX = 0f) else anchored
        }
        canvasControl.alignLeftOnNextZoom = false
        requested ?: return@LaunchedEffect
        withFrameNanos { }
        horizontalScroll.scrollTo(requested.scrollX.roundToInt())
        verticalScroll.scrollTo(requested.scrollY.roundToInt())
        if (pendingViewport == requested) pendingViewport = null
    }

    // How far the canvas has been extended to meet the user, in whole screenfuls: a high-water
    // mark, so scrolling back never shrinks the canvas from under the scroll position, and quantised
    // so it changes about once a screenful rather than once a frame.
    var reachedX by remember(page?.id) { mutableIntStateOf(0) }
    var reachedY by remember(page?.id) { mutableIntStateOf(0) }
    LaunchedEffect(page?.id, viewportPx, zoom) {
        snapshotFlow {
            val across = viewportPx.width / zoom
            val down = viewportPx.height / zoom
            val x = if (across > 0f) ((horizontalScroll.value / zoom + across) / across).toInt() else 0
            val y = if (down > 0f) ((verticalScroll.value / zoom + down) / down).toInt() else 0
            x to y
        }.collect { (x, y) ->
            if (x > reachedX) reachedX = x
            if (y > reachedY) reachedY = y
        }
    }

    LaunchedEffect(page?.id, panActive) {
        if (!panActive) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (panActive) {
            if (panDirection(panPointer.x - panAnchor.x, panPointer.y - panAnchor.y) == PanDirection.Center) {
                snapshotFlow { panPointer }.first {
                    panDirection(it.x - panAnchor.x, it.y - panAnchor.y) != PanDirection.Center
                }
                previousFrame = withFrameNanos { it }
            }
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previousFrame = frame
            val offset = panPointer - panAnchor
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

    // The page's size and edge, from everything on it: whether a chosen sheet still holds the
    // content decides whether it bounds the page or is only drawn as a guide.
    val inkEdge = remember(page?.ink) { page?.ink?.contentEdge() ?: Offset.Zero }
    val extent = page?.document?.let { document ->
        PageExtent.of(
            document.style,
            contentRight = maxOf(inkEdge.x, document.outlines.maxOfOrNull { it.x + it.width } ?: 0f),
            contentBottom = maxOf(inkEdge.y, document.outlines.maxOfOrNull { outline ->
                outline.y + if (outline is Outline.Text) measuredTextHeights[outline.id]
                    ?: maxOf(150f, outline.minHeight) else outline.primeHeight()
            } ?: 0f),
        )
    }
    val currentExtent by rememberUpdatedState(extent)
    SideEffect {
        canvasControl.viewportWidthDp = viewportPx.width / density.density
        canvasControl.pageWidthDp = extent?.page?.width?.value ?: 0f
    }

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
        val group = selectedCanvasBounds(outlines,
            snapshot.selectedObjectIds + snapshot.selectedTextOutlineIds, measuredTextHeights)
        val groupBody = group != null && point.x in (group.left - 6f)..(group.right + 6f) &&
            point.y in (group.top - 6f)..(group.bottom + 6f)
        val groupCorner = group != null && snapshot.selectedTextOutlineIds.isEmpty() &&
            !snapshot.selectedObjectsLocked && listOf(
                Offset(group.left - 6f, group.top - 6f), Offset(group.right + 6f, group.top - 6f),
                Offset(group.left - 6f, group.bottom + 6f), Offset(group.right + 6f, group.bottom + 6f),
            ).any { (point - it).getDistance() <= 14f }
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
        return groupBody || groupCorner || textGrip || primeBounds
    }

    Box(
        modifier
            .testTag(WorkspaceTestTags.PageCanvas)
            .onSizeChanged { viewportPx = it }
            .background(MaterialTheme.colorScheme.background)
            .drawWithContent {
                drawContent()
                if (panActive) {
                    val c = panAnchor
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
            .pointerHoverIcon(if (panActive) panPointerIcon(panCursorDirection) else PointerIcon.Default,
                overrideDescendants = panActive)
            .pointerInput(page?.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = if (change.scrollDelta.y != 0f) change.scrollDelta.y
                                else change.scrollDelta.x
                            val base = pendingViewport ?: CanvasViewport(zoomState.value,
                                horizontalScroll.value.toFloat(), verticalScroll.value.toFloat())
                            val next = base.wheel(delta, change.position.x, change.position.y)
                            if (next.zoom != base.zoom) {
                                pendingViewport = next
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
                        if (panActive) {
                            if (event.type == PointerEventType.Move) panPointer = change.position
                            if (event.type == PointerEventType.Release && !event.buttons.isTertiaryPressed) {
                                panActive = false
                            }
                            event.changes.forEach { it.consume() }
                        } else if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                            panAnchor = change.position
                            panPointer = change.position
                            panActive = true
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
                    if (down.isConsumed) {
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        return@awaitEachGesture
                    }
                    val start = toPage(down.position)
                    val titleFloor = currentState.selectedPage?.document?.style?.titleFloor ?: PageStyle.TITLE_BAND_DP
                    val marquee = !hitsSelectedTransform(start, currentState) &&
                        (currentState.objectLassoArmed ||
                        (!currentState.textToolArmed && start.y >= titleFloor &&
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
                        val insideGroup = selectedCanvasBounds(
                            currentState.selectedPage?.document?.outlines.orEmpty(),
                            currentState.selectedObjectIds + currentState.selectedTextOutlineIds,
                            measuredTextHeights)?.let { bounds ->
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
                                if (currentState.textToolArmed && placeable) currentCreate(x, y)
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
            val pageExtent = extent ?: return@Box
            // The window onto the page, in page dp: what the viewport shows at this zoom.
            val window = with(density) { DpSize((viewportPx.width / zoom).toDp(), (viewportPx.height / zoom).toDp()) }
            val canvasSize = pageExtent.canvasSize(window, reachedX, reachedY, density, zoom)
            // Read while drawing, so scrolling redraws the ruling and recomposes nothing.
            val visibleWindow: () -> Rect = {
                val left = horizontalScroll.value / zoom
                val top = verticalScroll.value / zoom
                Rect(left, top, left + viewportPx.width / zoom, top + viewportPx.height / zoom)
            }
            val palette = canvasPalette(page.document.style, canvasDark)
            val darkPage = palette.background.luminance() < 0.45f
            val richColors = RichTextColors(
                // Android's `EditorStyle.accentColor`, for bullets, to-do boxes and quote stripes.
                accent = Color(0xFF4CAF50),
                link = if (darkPage) Color(0xFF8AB4F8) else Color(0xFF1A5FB4),
                codeBackground = palette.ink.copy(alpha = 0.1f),
            )
            ZoomedCanvas(zoom) {
            Box(Modifier.documentExtent(canvasSize).testTag(WorkspaceTestTags.CanvasExtent)) {
                val groupBounds = selectedCanvasBounds(page.document.outlines,
                    state.selectedObjectIds + state.selectedTextOutlineIds, measuredTextHeights)
                CanvasPaper(page.document.style, palette, pageExtent, canvasSize, zoom, visibleWindow,
                    showMargins, lasso)
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
                                onEdit = { onFocusTextBox(outline.id) },
                                onOpenVideo = { url -> runCatching { uriHandler.openUri(url) } },
                                modifier = Modifier.fillMaxWidth().padding(TextBoxPadding).zIndex(1f),
                                storedEquations = richText?.storedEquationPreviews().orEmpty(),
                                links = richText?.linkSpans().orEmpty(),
                                linkColor = richColors.link,
                                onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                            )
                        } else false
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
                                .alpha(if (showingPreview) 0f else 1f)
                                .padding(TextBoxPadding)
                                // Ahead of the field's own keys: Tab indents, as on Android, and copy
                                // and paste keep the formatting the field's own would drop.
                                .onPreviewKeyEvent { event -> currentTextShortcut(event) }
                                .then(if (focused) Modifier.focusRequester(editorFocusRequester) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        keyboardTextBox = outline.id
                                        if (!focused) onFocusTextBox(outline.id)
                                    } else if (keyboardTextBox == outline.id) {
                                        keyboardTextBox = null
                                    }
                                }
                                .semantics { contentDescription = "Text box ${index + 1}" }
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
                        tonalElevation = if (drawnPicture) 0.dp else if (selected && groupBounds == null) 3.dp else 1.dp,
                        modifier = Modifier.offset(x, y).size(width, height)
                            .testTag(WorkspaceTestTags.primeObject(outline.id))
                            // A picture shows its own edges; only its selection is outlined.
                            .then(if (outline is Outline.Image && (!selected || groupBounds != null)) Modifier else Modifier.border(
                                if (selected && groupBounds == null) 2.dp else 1.dp,
                                if (selected && groupBounds == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
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
                                if (outline.id !in currentState.selectedObjectIds) onSelectObject(outline.id)
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
                    if (selected && groupBounds == null && state.selectedTextOutlineIds.isEmpty()) {
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
                InkLayer(page.ink, canvasSize, palette.ink, visibleWindow)
                if (groupBounds != null) {
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
                        .pointerInput(page.id, state.selectedObjectIds, state.selectedTextOutlineIds,
                            state.selectedObjectsLocked) {
                            var firstChange = true
                            detectDragGestures(
                                onDragStart = {
                                    firstChange = true
                                    focusManager.clearFocus()
                                    canvasFocusRequester.requestFocus()
                                },
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
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .border(1.5.dp, accent, CircleShape)
                                    .testTag(WorkspaceTestTags.groupCorner(index))
                                    .semantics { contentDescription = "Resize selection" }
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
                if (state.selectedObjectIds.isNotEmpty()) {
                    val selected = page.document.outlines.firstOrNull { it.id in state.selectedObjectIds }
                    if (selected != null) {
                        var colorMenu by remember(selected.id) { mutableStateOf(false) }
                        Surface(color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp,
                            modifier = Modifier.offset((groupBounds?.left ?: selected.x).dp,
                                ((groupBounds?.top ?: selected.y).dp - 52.dp).coerceAtLeast(0.dp))) {
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
                                        ScaledDropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
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
                            stateDescription = "Canvas zoom ${(zoom * 100).roundToInt()} percent"
                        },
                ) {
                    Text("${(zoom * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/** The zoom indicator's action, which is also its tooltip. */
internal const val ResetZoomLabel = "Reset zoom to 100%"

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

/** The zoom the canvas's scroll offsets were last laid out for, so a new one can be anchored. */
private class ZoomTracking(var applied: Float)

/** The page point at the canvas's visible top left, read when a picture is inserted. */
internal class CanvasOrigin {
    var read: () -> Offset = { Offset.Zero }
}
