package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import com.vivenotes.ai.AiModels
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import com.vivenotes.search.ContentSearchService
import com.vivenotes.ui.account.AccountHost
import com.vivenotes.ui.canvas.primeResizeCursorProviders
import com.vivenotes.ui.components.LocalPopupLayerDensity
import com.vivenotes.ui.components.LocalTooltipDismissals
import com.vivenotes.ui.components.TooltipDismissals
import com.vivenotes.ui.keyboard.LocalKeyBindings
import com.vivenotes.ui.keyboard.ShortcutKeys
import com.vivenotes.ui.keyboard.TextInputSessions
import com.vivenotes.ui.navigation.NotebookPane
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.ui.ribbon.settings.AboutDialog
import com.vivenotes.ui.ribbon.settings.InterfaceDialog
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.EditorDefaults
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.InMemoryNavigation
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState

/** Stable semantics identifiers used by desktop UI tests and future accessibility automation. */
object WorkspaceTestTags {
    const val HeaderBar = "workspace-header-bar"
    /** The top bar's account button, and the two badges Android's ribbon puts on it. */
    const val Account = "account-open"
    const val AccountConnected = "account-open-connected"
    const val AccountOffline = "account-open-offline"
    const val RibbonBar = "workspace-ribbon-bar"
    const val NavigationToggle = "workspace-navigation-toggle"
    const val NavigationIcon = "workspace-navigation-icon"
    const val NotebookPane = "workspace-notebook-pane"
    const val PagePane = "workspace-page-pane"
    const val NotebookDivider = "workspace-notebook-divider"
    const val PageDivider = "workspace-page-divider"
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
    const val ObjectToolkit = "workspace-object-toolkit"
    const val ObjectColor = "workspace-object-color"
    const val ObjectColorDivider = "workspace-object-color-divider"
    const val InkGroup = "workspace-ink-group"
    const val InkMath = "workspace-ink-math"
    const val InkColorDivider = "workspace-ink-color-divider"
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
    onShapeSettingsChange: (ShapeToolSettings) -> Unit = {},
    onInkSettingsChange: (InkSettings) -> Unit = {},
    /** The keyboard shortcuts in force, and where Settings → Hardware sends changes to them. */
    keyBindings: KeyBindings = KeyBindings.Default,
    onKeyBindingsChange: (KeyBindings) -> Unit = {},
    /** This device's input choices — whether a finger draws — and where Settings → Hardware sends changes. */
    inputSettings: InputSettings = InputSettings(),
    onInputSettingsChange: (InputSettings) -> Unit = {},
    /** The File tab's `.vive` export and import; without it those commands are unavailable. */
    fileActions: FileActions? = null,
    /** The account feature: the top bar's account button and the Account destination it opens. */
    accountHost: AccountHost? = null,
    /** This device's recognition models, for Settings → Models; without them it is unavailable. */
    aiModels: AiModels? = null,
    drawMath: DrawMath? = null,
    appVersion: String = Strings.development,
    search: ContentSearchService? = null,
    onOpenSearchPage: ((String, String) -> Unit)? = null,
    debugLog: DebugLog = remember { DebugLog() },
    developmentBuild: Boolean = false,
) {
    var previewSettings by remember { mutableStateOf<InterfaceSettings?>(null) }
    // Held here as well, so a caller that does not keep the settings still sees its changes.
    var view by remember(viewSettings) { mutableStateOf(viewSettings.normalized()) }
    var bindings by remember(keyBindings) { mutableStateOf(keyBindings) }
    var input by remember(inputSettings) { mutableStateOf(inputSettings) }
    var accountOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var pdfExportOpen by remember { mutableStateOf(false) }
    val accountConnected = accountHost?.connected?.collectAsState()?.value == true
    val accountUnreachable = accountHost?.serverUnreachable?.collectAsState()?.value == true
    val baseDensity = LocalDensity.current
    val effectiveSettings = previewSettings ?: interfaceSettings
    val pageDensity = effectiveSettings.documentDensity(baseDensity)
    // Keys reach only the focused element and what contains it, so the workspace is never left
    // without one: at launch, and whenever the focused element goes (a discarded text box, a
    // closed dialog), the canvas takes the focus and the keyboard shortcuts keep working. Not while
    // the Account destination covers the page: it keeps the focus itself, and a canvas nobody can
    // see must not take Escape or the shortcuts.
    val canvasFocusRequester = remember { FocusRequester() }
    val tooltipDismissals = remember(debugLog) { TooltipDismissals(debugLog) }
    val shortcutKeys = remember { ShortcutKeys() }
    val textInput = remember { TextInputSessions() }
    var workspaceHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(workspaceHasFocus, tooltipDismissals.hasOpenPopup, pdfExportOpen, accountOpen) {
        if (pdfExportOpen || accountOpen || workspaceHasFocus || tooltipDismissals.hasOpenPopup) return@LaunchedEffect
        withFrameNanos { }
        if (!workspaceHasFocus && !tooltipDismissals.hasOpenPopup) canvasFocusRequester.requestFocus()
    }
    val darkTheme = effectiveSettings.darkTheme ?: systemDarkTheme ?:
        (MaterialTheme.colorScheme.background.luminance() < 0.45f)
    ViveNotesTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalTooltipDismissals provides tooltipDismissals, *primeResizeCursorProviders()) {
            Box(modifier.fillMaxSize().onFocusChanged { workspaceHasFocus = it.hasFocus }.focusGroup()
                .onPreviewKeyEvent { event ->
                    shortcutKeys.observe(event) || shortcutKeys.dismiss(event, tooltipDismissals::dismiss)
                }) {
                CompositionLocalProvider(
                    LocalDensity provides effectiveSettings.density(baseDensity),
                    LocalPopupLayerDensity provides baseDensity,
                    LocalKeyBindings provides bindings,
                ) {
                    if (pdfExportOpen && fileActions?.pdfExporter != null) {
                        com.vivenotes.ui.ribbon.file.ExportPdfDialog(
                            exporter = fileActions.pdfExporter!!,
                            pageId = state.selectedPage?.id,
                            sectionId = state.selectedSection?.id,
                            pageTitle = state.selectedPage?.title.orEmpty(),
                            sectionName = state.selectedSection?.name.orEmpty(),
                            onDismiss = { pdfExportOpen = false }, prepare = fileActions::preparePdfExport,
                        )
                    // Keep workspace letter shortcuts out of active text input.
                    } else InterceptPlatformTextInput(textInput) { WorkspaceContent(state, onStateChange, Modifier.fillMaxSize(), navigation, pictures, thumbnails,
                        onInterface = { previewSettings = interfaceSettings }, pageDensity = pageDensity,
                        view = view, themePreference = effectiveSettings.darkTheme, onViewChange = { next ->
                            view = next
                            onViewSettingsChange(next)
                        }, bindings = bindings, onBindingsChange = { next ->
                            bindings = next
                            onKeyBindingsChange(next)
                        }, input = input, onInputChange = { next ->
                            input = next
                            onInputSettingsChange(next)
                        }, canvasFocusRequester = canvasFocusRequester, fileActions = fileActions,
                        onEditorDefaultsChange = onEditorDefaultsChange,
                        onShapeSettingsChange = onShapeSettingsChange,
                        onInkSettingsChange = onInkSettingsChange,
                        accountConnected = accountConnected, serverUnreachable = accountUnreachable,
                        onOpenAccount = { accountOpen = true },
                        onAbout = { aboutOpen = true }, aiModels = aiModels, drawMath = drawMath,
                        onExportPdf = { pdfExportOpen = true },
                        search = search, onOpenSearchPage = onOpenSearchPage, shortcutKeys = shortcutKeys,
                        textInput = textInput, developmentBuild = developmentBuild, debugLog = debugLog) }
                }
                // Composed while closed as well: it holds the state Android keeps above its Account
                // destination, so a sign-in in flight outlives the screen it was started from.
                accountHost?.Destination(visible = accountOpen, onBack = { accountOpen = false })
                if (aboutOpen) AboutDialog(appVersion, onDismiss = { aboutOpen = false })
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
}
