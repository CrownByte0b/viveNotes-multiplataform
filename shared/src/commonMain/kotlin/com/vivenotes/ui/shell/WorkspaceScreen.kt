package com.vivenotes.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.workspace.NotebookSummary
import com.vivenotes.workspace.PageSummary
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.SectionSummary
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
import multiplataform_vive.shared.generated.resources.Res
import multiplataform_vive.shared.generated.resources.inter
import multiplataform_vive.shared.generated.resources.jetbrains_mono
import multiplataform_vive.shared.generated.resources.lora
import org.jetbrains.compose.resources.Font

private val NotebookPaneWidth = 260.dp
private val PagePaneWidth = 292.dp
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
    const val AddPage = "workspace-add-page"
    const val TitleEditor = "workspace-title-editor"
    const val BodyEditor = "workspace-body-editor"
    const val CanvasPaste = "workspace-canvas-paste"
    const val TextBoxCopy = "workspace-text-box-copy"
    const val TextBoxSelectAll = "workspace-text-box-select-all"
    const val TextBoxDelete = "workspace-text-box-delete"
    const val ObjectCopy = "workspace-object-copy"
    const val ObjectDelete = "workspace-object-delete"
    const val ObjectLock = "workspace-object-lock"
    const val ObjectColor = "workspace-object-color"
    const val ObjectLasso = "workspace-object-lasso"
    const val StructuralUndo = "workspace-structural-undo"
    const val StructuralRedo = "workspace-structural-redo"
    fun primeObject(id: String): String = "workspace-prime-object-$id"
    fun objectCorner(id: String, corner: Int): String = "workspace-object-corner-$id-$corner"
    fun textBox(id: String): String = "workspace-text-box-$id"
    fun textBoxOutline(id: String): String = "workspace-text-box-outline-$id"
    fun textGrip(id: String): String = "workspace-text-grip-$id"
    fun textWidthHandle(id: String): String = "workspace-text-width-$id"
    fun textHeightHandle(id: String): String = "workspace-text-height-$id"
    const val ClearFormatting = "workspace-clear-formatting"
    const val Styles = "workspace-document-styles"

    fun blockType(type: BlockType): String = "workspace-document-block-${type.name}"
    fun alignment(align: Align): String = "workspace-document-align-${align.name}"

    fun documentMark(mark: Mark): String = "workspace-document-${mark::class.simpleName}"

    fun ribbonTab(tab: RibbonTab): String = "workspace-ribbon-${tab.name}"
    fun section(id: String): String = "workspace-section-$id"
    fun page(id: String): String = "workspace-page-$id"
}

@Composable
fun WorkspaceScreen(
    state: WorkspaceState,
    onStateChange: (WorkspaceState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val editorFocusRequester = remember { FocusRequester() }
    var editorFocusRequest by remember { mutableIntStateOf(0) }
    fun applyEditorCommand(next: WorkspaceState) {
        onStateChange(next)
        editorFocusRequest++
    }
    LaunchedEffect(editorFocusRequest) {
        if (editorFocusRequest > 0) editorFocusRequester.requestFocus()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopNavigation(
            activeTab = state.activeTab,
            navigationVisible = state.navigationVisible,
            onToggleNavigation = {
                onStateChange(state.copy(navigationVisible = !state.navigationVisible))
            },
            onSelectTab = { onStateChange(state.copy(activeTab = it, selectedObjectIds = emptySet())) },
            canUndo = state.structuralUndo.isNotEmpty(),
            canRedo = state.structuralRedo.isNotEmpty(),
            onUndo = { onStateChange(state.undoStructure()) },
            onRedo = { onStateChange(state.redoStructure()) },
        )
        if (state.activeTab == RibbonTab.Document) {
            DocumentRibbon(
                richText = state.richText,
                textToolArmed = state.textToolArmed,
                onToggleTextTool = { onStateChange(state.toggleTextTool()) },
                onToggleMark = { mark, selection ->
                    applyEditorCommand((selection?.let(state::selectText) ?: state).toggleSelectedMark(mark))
                },
                onSetMark = { mark, selection ->
                    applyEditorCommand((selection?.let(state::selectText) ?: state).setSelectedMark(mark))
                },
                onClearMark = { mark, selection ->
                    applyEditorCommand((selection?.let(state::selectText) ?: state).clearSelectedMark(mark))
                },
                onClearFormatting = { selection ->
                    applyEditorCommand((selection?.let(state::selectText) ?: state).clearSelectedFormatting())
                },
                onBlockType = { type, selection ->
                    applyEditorCommand((selection?.let(state::selectText) ?: state).setSelectedBlockType(type))
                },
                onAlign = { align, selection ->
                    applyEditorCommand((selection?.let(state::selectText) ?: state).alignSelectedText(align))
                },
                onIndent = { delta, selection ->
                    applyEditorCommand((selection?.let(state::selectText) ?: state).indentSelectedText(delta))
                },
                onCopy = { selection ->
                    val selected = selection?.let(state::selectText) ?: state
                    clipboard.setText(AnnotatedString(selected.selectedText))
                    applyEditorCommand(selected)
                },
                onCut = { selection ->
                    val selected = selection?.let(state::selectText) ?: state
                    clipboard.setText(AnnotatedString(selected.selectedText))
                    applyEditorCommand(selected.replaceSelectedText(""))
                },
                onPaste = { selection ->
                    val selected = selection?.let(state::selectText) ?: state
                    clipboard.getText()?.text?.let { applyEditorCommand(selected.replaceSelectedText(it)) }
                },
            )
        } else {
            CommandRibbon(activeTab = state.activeTab, lassoArmed = state.objectLassoArmed,
                onToggleLasso = { onStateChange(state.toggleObjectLasso()) })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                        onSelectNotebook = { onStateChange(state.selectNotebook(it)) },
                        onSelectSection = { onStateChange(state.selectSection(it)) },
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
                        onAddPage = { onStateChange(state.addPage()) },
                        onSelectPage = { onStateChange(state.selectPage(it)) },
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                PageCanvas(
                    state = state,
                    editorFocusRequester = editorFocusRequester,
                    modifier = Modifier.weight(1f),
                    onTitleChange = { onStateChange(state.updateSelectedPage(title = it)) },
                    onFocusTextBox = { onStateChange(state.focusTextBox(it)) },
                    onClearCanvasFocus = { onStateChange(state.clearCanvasFocus()) },
                    onCreateTextBox = { x, y -> onStateChange(state.createTextBox(x, y)) },
                    onMoveTextBox = { id, dx, dy -> onStateChange(state.moveTextBox(id, dx, dy)) },
                    onResizeTextBox = { id, width, height -> onStateChange(state.resizeTextBox(id, width, height)) },
                    onCopyTextBox = { onStateChange(state.copyTextBox(it)) },
                    onSelectAllTextBox = { onStateChange(state.selectAllTextBox(it)) },
                    onDeleteTextBox = { onStateChange(state.deleteTextBox(it)) },
                    onPasteCanvas = { x, y -> onStateChange(state.pasteCanvasAt(x, y)) },
                    onSelectObject = { onStateChange(state.selectObject(it)) },
                    onMoveObjects = { dx, dy -> onStateChange(state.moveSelectedObjects(dx, dy)) },
                    onResizeObjects = { ax, ay, sx, sy ->
                        onStateChange(state.resizeSelectedObjects(ax, ay, sx, sy))
                    },
                    onCopyObjects = { onStateChange(state.copySelectedObjects()) },
                    onDeleteObjects = { onStateChange(state.deleteSelectedObjects()) },
                    onToggleObjectLock = { onStateChange(state.toggleObjectLock()) },
                    onColorObjects = { onStateChange(state.colorSelectedObjects(it)) },
                    onSelectObjectsInRect = { l, t, r, b ->
                        onStateChange(state.selectObjectsInRect(l, t, r, b))
                    },
                    onBodyChange = { id, value ->
                        val focused = if (state.focusedTextOutlineId == id) state else state.focusTextBox(id)
                        onStateChange(focused.editSelectedText(
                            value.text,
                            TextSelection(value.selection.start, value.selection.end),
                            value.composition?.let { TextSelection(it.start, it.end) },
                        ))
                    },
                )
            }
        }
    }
}

@Composable
private fun TopNavigation(
    activeTab: RibbonTab,
    navigationVisible: Boolean,
    onToggleNavigation: () -> Unit,
    onSelectTab: (RibbonTab) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onToggleNavigation,
                modifier = Modifier
                    .testTag(WorkspaceTestTags.NavigationToggle)
                    .semantics {
                        contentDescription = if (navigationVisible) {
                            "Hide notebook navigation"
                        } else {
                            "Show notebook navigation"
                        }
                    },
            ) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                text = "ViveNotes",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RibbonTab.entries.forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { onSelectTab(tab) },
                        label = { Text(tab.name) },
                        modifier = Modifier.testTag(WorkspaceTestTags.ribbonTab(tab)),
                    )
                }
            }
            IconButton(onClick = onUndo, enabled = canUndo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralUndo)
                    .semantics { contentDescription = "Undo canvas action" }) {
                Text("↶", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = onRedo, enabled = canRedo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralRedo)
                    .semantics { contentDescription = "Redo canvas action" }) {
                Text("↷", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "Local preview",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun CommandRibbon(
    activeTab: RibbonTab,
    lassoArmed: Boolean,
    onToggleLasso: () -> Unit,
) {
    val actions = when (activeTab) {
        RibbonTab.File -> listOf("Import", "Export", "Print", "History")
        RibbonTab.Document -> emptyList()
        RibbonTab.Draw -> listOf("Select", "Pen", "Highlighter", "Eraser", "Lasso", "Shape", "Ruler")
        RibbonTab.View -> listOf("Zoom", "Paper", "Page color", "Paper size", "Background")
        RibbonTab.Settings -> listOf("Appearance", "Hardware", "Models", "Account", "About")
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                OutlinedButton(
                    onClick = { if (action == "Lasso") onToggleLasso() },
                    enabled = action == "Lasso",
                    modifier = if (action == "Lasso") Modifier.testTag(WorkspaceTestTags.ObjectLasso) else Modifier,
                ) {
                    Text(if (action == "Lasso" && lassoArmed) "Lasso ✓" else action)
                }
            }
            Text(
                text = "Tools unlock as each port phase lands",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun NotebookPane(
    state: WorkspaceState,
    onSelectNotebook: (String) -> Unit,
    onSelectSection: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(NotebookPaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.NotebookPane),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "Notebooks",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.notebooks, key = NotebookSummary::id) { notebook ->
                    NotebookRow(
                        notebook = notebook,
                        selected = notebook.id == state.selectedNotebookId,
                        onClick = { onSelectNotebook(notebook.id) },
                    )
                    if (notebook.id == state.selectedNotebookId) {
                        notebook.sections.forEach { section ->
                            SectionRow(
                                section = section,
                                selected = section.id == state.selectedSectionId,
                                onClick = { onSelectSection(section.id) },
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.padding(10.dp),
            ) {
                Text("＋ New notebook")
            }
        }
    }
}

@Composable
private fun NotebookRow(
    notebook: NotebookSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.Transparent
    }
    Surface(
        onClick = onClick,
        color = container,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        ListItem(
            headlineContent = {
                Text(notebook.name, fontWeight = FontWeight.SemiBold)
            },
            leadingContent = {
                Box(
                    Modifier
                        .size(13.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(notebook.colorArgb)),
                )
            },
            trailingContent = { Text(if (selected) "⌄" else "›") },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun SectionRow(
    section: SectionSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .testTag(WorkspaceTestTags.section(section.id))
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(5.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(section.colorArgb)),
        )
        Text(
            text = section.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun PageListPane(
    section: SectionSummary?,
    selectedPageId: String,
    onAddPage: () -> Unit,
    onSelectPage: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(PagePaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.PagePane),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = section?.name ?: "No section",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${section?.pages?.size ?: 0} pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onAddPage,
                    enabled = section != null,
                    modifier = Modifier.testTag(WorkspaceTestTags.AddPage),
                ) {
                    Text("＋ Page")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(section?.pages.orEmpty(), key = PageSummary::id) { page ->
                    PageRow(
                        page = page,
                        selected = page.id == selectedPageId,
                        onClick = { onSelectPage(page.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageRow(
    page: PageSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(WorkspaceTestTags.page(page.id)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = page.title.ifBlank { "Untitled page" },
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = page.preview,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = page.createdLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageCanvas(
    state: WorkspaceState,
    editorFocusRequester: FocusRequester,
    onTitleChange: (String) -> Unit,
    onFocusTextBox: (String) -> Unit,
    onClearCanvasFocus: () -> Unit,
    onCreateTextBox: (Float, Float) -> Unit,
    onMoveTextBox: (String, Float, Float) -> Unit,
    onResizeTextBox: (String, Float?, Float?) -> Unit,
    onCopyTextBox: (String) -> Unit,
    onSelectAllTextBox: (String) -> Unit,
    onDeleteTextBox: (String) -> Unit,
    onPasteCanvas: (Float, Float) -> Unit,
    onSelectObject: (String) -> Unit,
    onMoveObjects: (Float, Float) -> Unit,
    onResizeObjects: (Float, Float, Float, Float) -> Unit,
    onCopyObjects: () -> Unit,
    onDeleteObjects: () -> Unit,
    onToggleObjectLock: () -> Unit,
    onColorObjects: (Int) -> Unit,
    onSelectObjectsInRect: (Float, Float, Float, Float) -> Unit,
    onBodyChange: (String, TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = state.selectedPage
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    var pastePoint by remember(page?.id) { mutableStateOf<Offset?>(null) }
    var lasso by remember(page?.id) { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val currentCreate by rememberUpdatedState(onCreateTextBox)
    val currentClearCanvasFocus by rememberUpdatedState(onClearCanvasFocus)
    val currentPaste by rememberUpdatedState(onPasteCanvas)
    val currentSelectObjects by rememberUpdatedState(onSelectObjectsInRect)
    val currentState by rememberUpdatedState(state)
    val measuredTextHeights = remember(page?.id) { mutableStateMapOf<String, Float>() }

    Box(
        modifier
            .testTag(WorkspaceTestTags.PageCanvas)
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(page?.id) {
                var previousTapTime = 0L
                var previousTapPoint = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var last = down
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        if (currentState.objectLassoArmed &&
                            (change.position - down.position).getDistance() >= 12.dp.toPx()) {
                            change.consume()
                            val scroll = Offset(horizontalScroll.value.toFloat(), verticalScroll.value.toFloat())
                            lasso = (down.position + scroll) to (change.position + scroll)
                        }
                    } while (last.pressed)
                    val distance = (last.position - down.position).getDistance()
                    if (currentState.objectLassoArmed && distance >= 12.dp.toPx()) {
                        val scroll = Offset(horizontalScroll.value.toFloat(), verticalScroll.value.toFloat())
                        val a = down.position + scroll
                        val b = last.position + scroll
                        currentSelectObjects(
                            with(density) { minOf(a.x, b.x).toDp().value },
                            with(density) { minOf(a.y, b.y).toDp().value },
                            with(density) { maxOf(a.x, b.x).toDp().value },
                            with(density) { maxOf(a.y, b.y).toDp().value },
                        )
                        lasso = null
                    } else if (distance < 12.dp.toPx()) {
                        val point = last.position + Offset(horizontalScroll.value.toFloat(), verticalScroll.value.toFloat())
                        val x = with(density) { point.x.toDp().value }
                        val y = with(density) { point.y.toDp().value }
                        val doc = currentState.selectedPage?.document
                        val hitsObject = doc?.outlines?.any { outline ->
                            val height = if (outline is Outline.Text) measuredTextHeights[outline.id]
                                ?: maxOf(150f, outline.minHeight)
                                else outline.primeHeight()
                            val focusedText = outline is Outline.Text &&
                                currentState.focusedTextOutlineId == outline.id
                            val hasText = outline is Outline.Text && outline.blocks.any { block ->
                                block.runs.any { run -> run.plainText.isNotEmpty() }
                            }
                            val toolbarBelow = focusedText && hasText && outline.y < 140f
                            val margin = if (outline is Outline.Text &&
                                currentState.focusedTextOutlineId == outline.id) 16f else 0f
                            val chromeTop = if (focusedText) 80f else if (
                                outline.id in currentState.selectedObjectIds) 52f else 0f
                            val right = outline.x + if (toolbarBelow) maxOf(outline.width, 280f)
                                else outline.width
                            x >= outline.x - margin && x <= right + margin &&
                                y >= outline.y - chromeTop &&
                                y <= outline.y + height + margin + if (toolbarBelow) 60f else 0f
                        } == true
                        if (!hitsObject && y >= PageStyle.TITLE_BAND_DP) {
                            focusManager.clearFocus()
                            val isDouble = !currentState.canvasClipboard.isEmpty &&
                                last.uptimeMillis - previousTapTime in 1..350 &&
                                (point - previousTapPoint).getDistance() < 32.dp.toPx()
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
        Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll)
            .verticalScroll(verticalScroll)) {
            Box(Modifier.requiredSize(2200.dp, 4500.dp)) {
                Canvas(Modifier.fillMaxSize().testTag(WorkspaceTestTags.CanvasBackground)) {
                    val step = 32.dp.toPx()
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                        x += step
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                        y += step
                    }
                    lasso?.let { (a, b) ->
                        val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                        val bounds = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                        drawRect(color = Color(0xFF1B6FA8).copy(alpha = 0.12f),
                            topLeft = topLeft, size = bounds)
                        drawRect(color = Color(0xFF1B6FA8), topLeft = topLeft,
                            size = bounds, style = Stroke(width = 2.dp.toPx()))
                    }
                }
                OutlinedTextField(
                    value = page.title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    label = { Text("Page title") },
                    textStyle = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.offset(16.dp, 8.dp).width(720.dp)
                        .testTag(WorkspaceTestTags.TitleEditor),
                )
                page.document.outlines.filterIsInstance<Outline.Text>().forEachIndexed { index, outline ->
                    val focused = state.focusedTextOutlineId == outline.id
                    val richText = state.richTextFor(outline.id)
                    val width = outline.width.coerceIn(120f, 2000f).dp
                    val x = outline.x.coerceAtLeast(0f).dp
                    val y = outline.y.coerceAtLeast(0f).dp
                    val renderedHeight = (measuredTextHeights[outline.id]
                        ?: maxOf(outline.minHeight, 150f)).dp
                    val showChrome = focused && outline.blocks.any { it.runs.any { run -> run.plainText.isNotEmpty() } }
                    val currentMove by rememberUpdatedState(onMoveTextBox)
                    val currentResize by rememberUpdatedState(onResizeTextBox)
                    if (showChrome) {
                        val toolbarY = if (y < 140.dp) y + renderedHeight + 8.dp
                            else y - 76.dp
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 4.dp,
                            modifier = Modifier.offset(x, toolbarY),
                        ) {
                            Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(onClick = { onCopyTextBox(outline.id) },
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.TextBoxCopy)) {
                                    Icon(DocumentSymbols.ContentCopy, contentDescription = "Copy text box",
                                        modifier = Modifier.size(18.dp))
                                }
                                TextButton(onClick = { onSelectAllTextBox(outline.id) },
                                    modifier = Modifier.testTag(WorkspaceTestTags.TextBoxSelectAll)) { Text("Select all") }
                                IconButton(onClick = { onDeleteTextBox(outline.id) },
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.TextBoxDelete)) {
                                    Icon(ObjectSymbols.Delete, contentDescription = "Delete text box",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    if (showChrome) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.offset(x, (y - 28.dp).coerceAtLeast(0.dp))
                                .width(96.dp).height(24.dp)
                                .testTag(WorkspaceTestTags.textGrip(outline.id))
                                .semantics { contentDescription = "Move text box" }
                                .pointerInput(outline.id) {
                                    var total = Offset.Zero
                                    detectDragGestures(
                                        onDragStart = { total = Offset.Zero },
                                        onDragEnd = { currentMove(outline.id,
                                            with(density) { total.x.toDp().value },
                                            with(density) { total.y.toDp().value }) },
                                        onDrag = { change, drag -> change.consume(); total += drag },
                                    )
                                },
                        ) { Box(contentAlignment = Alignment.Center) { Text("⋮⋮", style = MaterialTheme.typography.labelMedium) } }
                    }
                    Box(
                        modifier = Modifier.offset(x, y).width(width)
                            .heightIn(min = maxOf(outline.minHeight, 80f).dp)
                            .onSizeChanged { size ->
                                measuredTextHeights[outline.id] = with(density) { size.height.toDp().value }
                            }
                            .testTag(WorkspaceTestTags.textBox(outline.id)),
                    ) {
                        if (focused) Box(Modifier.matchParentSize()
                            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            .testTag(WorkspaceTestTags.textBoxOutline(outline.id)))
                        BasicTextField(
                            value = TextFieldValue(
                                annotatedString = richText?.asAnnotatedString() ?: buildAnnotatedString {},
                                selection = TextRange(richText?.selection?.start ?: 0,
                                    richText?.selection?.end ?: 0),
                                composition = if (focused) state.editorComposition?.let {
                                    TextRange(it.start, it.end)
                                } else null,
                            ),
                            onValueChange = { onBodyChange(outline.id, it) },
                            minLines = 3,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth()
                                .padding(8.dp)
                                .then(if (focused) Modifier.focusRequester(editorFocusRequester) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused && !focused) onFocusTextBox(outline.id)
                                }
                                .semantics { contentDescription = "Text box ${index + 1}" }
                                .testTag(if (index == 0) WorkspaceTestTags.BodyEditor else WorkspaceTestTags.textBox(outline.id) + "-editor"),
                        )
                    }
                    if (showChrome) {
                        Box(Modifier.offset(x + width - 6.dp,
                            y + renderedHeight / 2 - 28.dp).size(20.dp, 56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                            .testTag(WorkspaceTestTags.textWidthHandle(outline.id))
                            .semantics { contentDescription = "Resize text box width" }
                            .pointerInput(outline.id) {
                                var total = 0f
                                detectDragGestures(
                                    onDragStart = { total = 0f },
                                    onDragEnd = { currentResize(outline.id,
                                        outline.width + with(density) { total.toDp().value }, null) },
                                    onDrag = { change, drag -> change.consume(); total += drag.x },
                                )
                            })
                        Box(Modifier.offset(x + width / 2 - 24.dp,
                            y + renderedHeight - 5.dp).size(48.dp, 20.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                            .testTag(WorkspaceTestTags.textHeightHandle(outline.id))
                            .semantics { contentDescription = "Resize text box minimum height" }
                            .pointerInput(outline.id) {
                                var total = 0f
                                detectDragGestures(
                                    onDragStart = { total = 0f },
                                    onDragEnd = { currentResize(outline.id, null,
                                        outline.minHeight + with(density) { total.toDp().value }) },
                                    onDrag = { change, drag -> change.consume(); total += drag.y },
                                )
                            })
                    }
                }
                val currentMoveObjects by rememberUpdatedState(onMoveObjects)
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
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small,
                        tonalElevation = if (selected) 3.dp else 1.dp,
                        modifier = Modifier.offset(x, y).size(width, height)
                            .testTag(WorkspaceTestTags.primeObject(outline.id))
                            .border(if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                MaterialTheme.shapes.small)
                            .pointerInput(outline.id, selected, state.selectedObjectsLocked) {
                                if (selected && !state.selectedObjectsLocked) {
                                    var total = Offset.Zero
                                    detectDragGestures(
                                        onDragStart = { total = Offset.Zero },
                                        onDragEnd = { currentMoveObjects(
                                            with(density) { total.x.toDp().value },
                                            with(density) { total.y.toDp().value }) },
                                        onDrag = { change, drag -> change.consume(); total += drag },
                                    )
                                }
                            }
                            .clickable {
                                focusManager.clearFocus()
                                onSelectObject(outline.id)
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selected) {
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
                                        detectDragGestures(
                                            onDragStart = { total = Offset.Zero },
                                            onDragEnd = {
                                                val sx = 1f + with(density) { total.x.toDp().value } /
                                                    outline.width.coerceAtLeast(1f) * (if (index % 2 == 0) -1 else 1)
                                                val sy = 1f + with(density) { total.y.toDp().value } /
                                                    outline.primeHeight().coerceAtLeast(1f) * (if (index < 2) -1 else 1)
                                                currentResizeObjects(anchorX, anchorY, sx, sy)
                                            },
                                            onDrag = { change, drag -> change.consume(); total += drag },
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
                                if (selected !is Outline.Image) {
                                    Box {
                                        val swatch = when (selected) {
                                            is Outline.Shape -> Color(selected.borderArgb)
                                            is Outline.Table -> Color(selected.borderArgb)
                                            is Outline.Equation -> Color(selected.colorArgb ?: 0xFF000000.toInt())
                                            else -> Color.Black
                                        }
                                        IconButton(onClick = { colorMenu = true },
                                            modifier = Modifier.size(40.dp)
                                                .testTag(WorkspaceTestTags.ObjectColor)) {
                                            Box(Modifier.size(18.dp).clip(CircleShape)
                                                .background(swatch)
                                                .semantics { contentDescription = "Change object colour" })
                                        }
                                        DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                                            ObjectColors.chunked(5).forEach { colors ->
                                                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                    colors.forEach { (name, argb) ->
                                                        Box(Modifier.size(40.dp).padding(4.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(argb))
                                                            .semantics { contentDescription = name }
                                                            .clickable {
                                                                colorMenu = false
                                                                onColorObjects(argb)
                                                            })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                IconButton(onClick = onCopyObjects,
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectCopy)) {
                                    Icon(DocumentSymbols.ContentCopy, contentDescription = "Copy selection",
                                        modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = onToggleObjectLock,
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectLock)) {
                                    Icon(if (state.selectedObjectsLocked) ObjectSymbols.Lock
                                        else ObjectSymbols.LockOpen,
                                        contentDescription = if (state.selectedObjectsLocked)
                                            "Unlock selection" else "Lock selection",
                                        modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = onDeleteObjects,
                                    modifier = Modifier.size(40.dp).testTag(WorkspaceTestTags.ObjectDelete)) {
                                    Icon(ObjectSymbols.Delete, contentDescription = "Delete selection",
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
                        modifier = Modifier.offset(with(density) { point.x.toDp() },
                            with(density) { point.y.toDp() }),
                    ) {
                        TextButton(onClick = {
                            currentPaste(with(density) { point.x.toDp().value },
                                with(density) { point.y.toDp().value })
                            pastePoint = null
                        }, modifier = Modifier.testTag(WorkspaceTestTags.CanvasPaste)) { Text("Paste") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichTextBuffer.asAnnotatedString(): AnnotatedString {
    val inter = FontFamily(Font(Res.font.inter))
    val lora = FontFamily(Font(Res.font.lora))
    val jetbrainsMono = FontFamily(Font(Res.font.jetbrains_mono))
    return buildAnnotatedString {
    blocks.forEachIndexed { blockIndex, block ->
        if (blockIndex > 0) append('\n')
        pushStyle(ParagraphStyle(textAlign = when (block.align) {
            Align.Start -> TextAlign.Start
            Align.Center -> TextAlign.Center
            Align.End -> TextAlign.End
        }))
        pushStyle(SpanStyle(
            fontWeight = when (block.type) {
                BlockType.Heading1, BlockType.Heading2, BlockType.Heading3 -> FontWeight.Bold
                else -> null
            },
            fontSize = when (block.type) {
                BlockType.Heading1 -> 30.sp
                BlockType.Heading2 -> 24.sp
                BlockType.Heading3 -> 20.sp
                else -> androidx.compose.ui.unit.TextUnit.Unspecified
            },
        ))
        block.runs.forEach { run ->
            val marks = run.marks
            val selectedSize = marks.filterIsInstance<Mark.FontSize>().firstOrNull()?.sp
            val script = Mark.Subscript in marks || Mark.Superscript in marks
            val style = SpanStyle(
                fontWeight = if (Mark.Bold in marks) FontWeight.Bold else null,
                fontStyle = if (Mark.Italic in marks) FontStyle.Italic else null,
                fontFamily = marks.filterIsInstance<Mark.FontFamily>().firstOrNull()?.let {
                    when (it.name.lowercase()) {
                        "serif" -> FontFamily.Serif
                        "monospace" -> FontFamily.Monospace
                        "inter" -> inter
                        "lora" -> lora
                        "jetbrains_mono" -> jetbrainsMono
                        "cursive" -> FontFamily.Cursive
                        else -> FontFamily.SansSerif
                    }
                },
                fontSize = when {
                    script -> ((selectedSize ?: 15) * 0.8f).sp
                    selectedSize != null -> selectedSize.sp
                    else -> androidx.compose.ui.unit.TextUnit.Unspecified
                },
                baselineShift = when {
                    Mark.Subscript in marks -> BaselineShift.Subscript
                    Mark.Superscript in marks -> BaselineShift.Superscript
                    else -> null
                },
                color = marks.filterIsInstance<Mark.TextColor>().firstOrNull()
                    ?.let { Color(it.argb) } ?: Color.Unspecified,
                background = marks.filterIsInstance<Mark.Highlight>().firstOrNull()
                    ?.let { Color(it.argb) } ?: Color.Unspecified,
                textDecoration = when {
                    Mark.Underline in marks && Mark.Strikethrough in marks ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    Mark.Underline in marks -> TextDecoration.Underline
                    Mark.Strikethrough in marks -> TextDecoration.LineThrough
                    else -> null
                },
            )
            pushStyle(style)
            append(run.editorText)
            pop()
        }
        pop()
        pop()
    }
    }
}
