package com.vivenotes.ui.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.AiModels
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentKind
import com.vivenotes.richtext.TextSelection
import com.vivenotes.search.ContentSearchService
import com.vivenotes.search.revealSearchHit
import com.vivenotes.ui.canvas.LocalClipboardHtml
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.components.ToolPaneWidth
import com.vivenotes.ui.keyboard.ShortcutKeys
import com.vivenotes.ui.keyboard.TextInputSessions
import com.vivenotes.ui.navigation.NavigationDividerWidth
import com.vivenotes.ui.navigation.NavigationRequests
import com.vivenotes.ui.navigation.SectionTabsBar
import com.vivenotes.ui.navigation.navigationPaneWidths
import com.vivenotes.ui.ribbon.document.DocumentColorSelection
import com.vivenotes.ui.ribbon.document.LinkEditorRequest
import com.vivenotes.ui.ribbon.document.PictureDialog
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.ui.ribbon.view.viewActions
import com.vivenotes.workspace.EditorDefaults
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutScope
import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.toggleNavigation
import kotlinx.coroutines.flow.first

@Composable
internal fun WorkspaceContent(
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
    input: InputSettings,
    onInputChange: (InputSettings) -> Unit,
    /** The workspace's own focus, which holds the keyboard when no control does. */
    canvasFocusRequester: FocusRequester,
    fileActions: FileActions?,
    onEditorDefaultsChange: (EditorDefaults) -> Unit,
    onShapeSettingsChange: (ShapeToolSettings) -> Unit,
    onInkSettingsChange: (InkSettings) -> Unit,
    accountConnected: Boolean,
    serverUnreachable: Boolean,
    onOpenAccount: () -> Unit,
    onAbout: () -> Unit,
    onExportPdf: () -> Unit,
    aiModels: AiModels?,
    drawMath: DrawMath?,
    developmentBuild: Boolean,
    debugLog: DebugLog,
    search: ContentSearchService?,
    onOpenSearchPage: ((String, String) -> Unit)?,
    shortcutKeys: ShortcutKeys,
    /** Whether a text field has the keyboard, which is when a letter is a letter and not a shortcut. */
    textInput: TextInputSessions,
) {
    val canvasOrigin = remember { CanvasOrigin() }
    val canvasControl = remember { CanvasViewControl() }
    var openPane by remember { mutableStateOf<DockedPane?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHit by remember { mutableStateOf<ContentHit?>(null) }
    var searchRevealTick by remember { mutableIntStateOf(0) }
    var searchFocusTick by remember { mutableIntStateOf(0) }
    var notebookConfirmation by remember { mutableStateOf<String?>(null) }
    var confirmRevision by remember { mutableStateOf(false) }
    fun togglePane(pane: DockedPane) {
        openPane = if (openPane == pane) null else pane
    }
    var editingShortcut by remember { mutableStateOf<ShortcutAction?>(null) }
    var linkEditor by remember { mutableStateOf<LinkEditorRequest?>(null) }
    var pictureDialog by remember { mutableStateOf<PictureDialog?>(null) }
    var confirmResetShortcuts by remember { mutableStateOf(false) }
    val currentView by rememberUpdatedState(view)
    val currentThemePreference by rememberUpdatedState(themePreference)
    val currentOnViewChange by rememberUpdatedState(onViewChange)
    val viewActions = remember(onStateChange) {
        viewActions(onStateChange, { currentView }, { currentOnViewChange(it) }, canvasControl,
            onTogglePaperSizePane = { togglePane(DockedPane.PaperSize) },
            themePreference = { currentThemePreference })
    }
    var tabTextSelection by remember { mutableStateOf<Pair<String, TextSelection>?>(null) }
    var calculatorOpen by remember { mutableStateOf(false) }
    var mathSource by remember { mutableStateOf("") }
    var inkMathSelection by remember { mutableStateOf<InkSelection?>(null) }
    val formulaReady = aiModels?.state?.collectAsState()?.value?.formulaReady == true && drawMath != null
    val inkMathValid = inkMathSelection?.let { selection ->
        state.inkOnlySelection && state.selectedInk === selection && state.selectedPage?.ink === selection.page && formulaReady
    } ?: true
    LaunchedEffect(inkMathValid) {
        if (!inkMathValid) {
            inkMathSelection = null
            if (openPane == DockedPane.Math) openPane = null
        }
    }
    LaunchedEffect(openPane) { if (openPane != DockedPane.Math) inkMathSelection = null }
    LaunchedEffect(formulaReady) { if (!formulaReady) calculatorOpen = false }
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
    LaunchedEffect(searchHit, searchRevealTick, state.selectedPageId, state.selectedPage?.content) {
        val hit = searchHit ?: return@LaunchedEffect
        if (state.selectedPageId != hit.unit.pageId || state.selectedPage?.editable != true) return@LaunchedEffect
        onStateChange { it.revealSearchHit(hit) }
        if (hit.unit.kind == ContentKind.Text) {
            withFrameNanos { }
            editorFocusRequest++
        }
    }
    val currentBindings by rememberUpdatedState(bindings)
    val shortcuts = WorkspaceShortcuts({ currentState }, onStateChange, ::applyEditorCommand, navigation,
        viewActions, TextClipboardActions(LocalClipboardManager.current, ::applyEditorCommand, LocalClipboardHtml.current),
        onSearch = if (search == null) null else { {
            fileActions?.closePane()
            openPane = DockedPane.Search
            searchFocusTick++
        } })
    LaunchedEffect(state.navigationVisible, state.pageListVisible) {
        debugLog.event("navigation") { "visibility notebooks=${state.navigationVisible} pages=${state.pageListVisible}" }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val notebookNavigationAvailable = horizontalTabs || maxWidth >= 1040.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Shortcuts are dispatched from one table (Settings → Hardware lists and rebinds it):
                // here ahead of the focused control, after it below, and in the text box being edited.
                .onPreviewKeyEvent { event ->
                    shortcutKeys.dismiss(event) {
                        if (state.filePane.docked && fileActions != null) {
                            fileActions.closePane()
                            // A File pane may be covering a previously selected settings/search pane.
                            openPane = null
                            true
                        } else if (openPane != null) {
                            openPane = null
                            true
                        } else false
                    } ||
                        shortcutKeys.dispatch(event, currentBindings, ShortcutScope.Anywhere, shortcuts::run)
                }
                .onKeyEvent { event ->
                    shortcutKeys.dispatch(event, currentBindings, ShortcutScope.Workspace, shortcuts::run) ||
                        !textInput.typing &&
                        shortcutKeys.dispatch(event, currentBindings, ShortcutScope.OutsideText, shortcuts::run)
                }
                .focusRequester(canvasFocusRequester)
                .focusable(),
        ) {
            TopNavigation(
                activeTab = state.activeTab,
                navigationVisible = state.navigationVisible && notebookNavigationAvailable,
                onToggleNavigation = { onStateChange { it.toggleNavigation() } },
                onTabPress = { tabTextSelection = state.focusedTextOutlineId?.let { it to state.editorSelection } },
                onSelectTab = { tab ->
                    val captured = tabTextSelection
                    tabTextSelection = null
                    onStateChange { current ->
                        val next = current.selectRibbonTab(tab)
                        if (tab == RibbonTab.Document && captured != null && next.focusedTextOutlineId == captured.first)
                            next.selectText(captured.second) else next
                    }
                },
                canUndo = state.structuralUndo.isNotEmpty(),
                canRedo = state.structuralRedo.isNotEmpty(),
                onUndo = { onStateChange { it.undoStructure() } },
                onRedo = { onStateChange { it.redoStructure() } },
                accountConnected = accountConnected,
                serverUnreachable = serverUnreachable,
                onOpenAccount = onOpenAccount,
                searchAvailable = search != null,
                onSearch = { fileActions?.closePane(); togglePane(DockedPane.Search) },
            )
            // Each tab's buttons, and what they do, live in that tab's package under `ui/ribbon`.
            WorkspaceRibbon(
                state = state,
                onStateChange = onStateChange,
                fileActions = fileActions,
                onNotebookConfirmation = { notebookConfirmation = it },
                onExportPdf = onExportPdf,
                onShapeSettingsChange = onShapeSettingsChange,
                onInkSettingsChange = onInkSettingsChange,
                canvasDark = canvasDark,
                formulaReady = formulaReady,
                calculatorOpen = calculatorOpen,
                onToggleCalculator = { calculatorOpen = !calculatorOpen },
                applyEditorCommand = ::applyEditorCommand,
                pictures = pictures,
                canvasOrigin = canvasOrigin,
                documentColorSelection = documentColorSelection,
                onLinkRequest = { linkEditor = it },
                onEditorDefaultsChange = onEditorDefaultsChange,
                onPictureDialog = { pictureDialog = it },
                debugLog = debugLog,
                view = view,
                viewActions = viewActions,
                onInterface = onInterface,
                openPane = openPane,
                togglePane = ::togglePane,
                onViewChange = onViewChange,
                onAbout = onAbout,
                aiModels = aiModels
            )
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
                val showPagePane = maxWidth >= 760.dp && state.pageListVisible
                val paneMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
                val dockedWidth = when {
                    state.filePane.docked && fileActions != null -> ToolPaneWidth + 1.dp
                    openPane == DockedPane.Math -> MathPaneWidth + 1.dp
                    openPane != null -> ToolPaneWidth + 1.dp
                    else -> 0.dp
                }
                val widths = navigationPaneWidths(
                    available = (maxWidth - dockedWidth - 240.dp).value,
                    notebook = if (showNotebookPane) state.notebookPaneWidth else 0f,
                    pages = if (showPagePane) state.pagePaneWidth else 0f,
                    dividerWidth = NavigationDividerWidth.value,
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .animateContentSize(animationSpec = paneMotion),
                ) {
                    WorkspaceNavigation(
                        state = state,
                        onStateChange = onStateChange,
                        showNotebookPane = showNotebookPane,
                        showPagePane = showPagePane,
                        widths = widths,
                        paneMotion = paneMotion,
                        navigationRequests = navigationRequests,
                        navigation = navigation,
                        debugLog = debugLog
                    )
                    CompositionLocalProvider(LocalDensity provides pageDensity) {
                        WorkspacePage(
                            debugLog = debugLog,
                            state = state,
                            onStateChange = onStateChange,
                            searchHit = searchHit,
                            searchRevealTick = searchRevealTick,
                            pictures = pictures,
                            thumbnails = thumbnails.takeIf { view.linkPreviews },
                            canvasOrigin = canvasOrigin,
                            canvasControl = canvasControl,
                            view = view,
                            input = input,
                            canvasDark = canvasDark,
                            showMargins = openPane == DockedPane.PaperSize,
                            onZoomChange = { zoom -> currentOnViewChange(currentView.copy(zoom = zoom).normalized()) },
                            onTextShortcut = { event -> shortcutKeys.dispatch(event, currentBindings, ShortcutScope.TextBox, shortcuts::run) },
                            editorFocusRequester = editorFocusRequester,
                            canvasFocusRequester = canvasFocusRequester,
                            modifier = Modifier.weight(1f),
                            requestEditorFocus = { editorFocusRequest++ },
                            formulaReady = formulaReady,
                            onInkMath = { selection -> if (formulaReady && state.inkOnlySelection && state.selectedInk === selection) { inkMathSelection = selection; openPane = DockedPane.Math } },
                            applyEditorCommand = ::applyEditorCommand,
                            onLinkRequest = { linkEditor = it }
                        )
                    }
                    WorkspaceDockedPane(
                        state = state,
                        onStateChange = onStateChange,
                        fileActions = fileActions,
                        openPane = openPane,
                        onRestoreRevision = { confirmRevision = true },
                        onClosePane = { openPane = null },
                        viewActions = viewActions,
                        onEditorDefaultsChange = onEditorDefaultsChange,
                        input = input,
                        onInputChange = onInputChange,
                        bindings = bindings,
                        onEditShortcut = { editingShortcut = it },
                        onBindingsChange = onBindingsChange,
                        onResetShortcuts = { confirmResetShortcuts = true },
                        search = search,
                        searchQuery = searchQuery,
                        onSearchQuery = { searchQuery = it },
                        onSearchHit = { hit -> searchHit = hit; searchRevealTick++; if (onOpenSearchPage != null) onOpenSearchPage(hit.unit.sectionId, hit.unit.pageId) else onStateChange { it.selectPage(hit.unit.pageId) } },
                        searchFocusTick = searchFocusTick,
                        drawMath = drawMath,
                        inkMathSelection = inkMathSelection,
                        mathSource = mathSource,
                        inkMathValid = inkMathValid,
                        developmentBuild = developmentBuild,
                        debugLog = debugLog,
                        onCloseInkMath = { inkMathSelection = null; openPane = null },
                        aiModels = aiModels
                    )

                }
            }
        }
        if (calculatorOpen && formulaReady && drawMath != null) FloatingCalculator(state.inkSettings,
            state.activePenIndex.takeIf { state.inkTool == com.vivenotes.workspace.InkTool.Pen } ?: 0,
            input.drawWithFinger, drawMath, onOpenInPanel = { source ->
                inkMathSelection = null; mathSource = source; openPane = DockedPane.Math
            },
            onClose = { calculatorOpen = false }, modifier = Modifier.padding(end = when (openPane) {
                DockedPane.Math -> MathPaneWidth
                null -> 0.dp
                else -> ToolPaneWidth
            }))
        WorkspaceDialogs(
            state = state,
            navigationRequests = navigationRequests,
            navigation = navigation,
            linkEditor = linkEditor,
            onDismissLink = { linkEditor = null },
            pictureDialog = pictureDialog,
            onDismissPicture = { pictureDialog = null },
            fileActions = fileActions,
            confirmRevision = confirmRevision,
            onDismissRevision = { confirmRevision = false },
            notebookConfirmation = notebookConfirmation,
            onDismissNotebook = { notebookConfirmation = null },
            editingShortcut = editingShortcut,
            onDismissShortcut = { editingShortcut = null },
            bindings = bindings,
            onBindingsChange = onBindingsChange,
            confirmResetShortcuts = confirmResetShortcuts,
            onDismissReset = { confirmResetShortcuts = false }
        )

    }
}


internal enum class DockedPane { PaperSize, Hardware, Models, Search, Math }
