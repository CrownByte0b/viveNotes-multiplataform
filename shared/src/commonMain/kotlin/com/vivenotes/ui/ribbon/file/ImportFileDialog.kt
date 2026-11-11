// Ported from viveNotes Android ui/editor/ImportFileDialog.kt, in the desktop's Export PDF window.
package com.vivenotes.ui.ribbon.file

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivenotes.fileimport.FileImportMode
import com.vivenotes.fileimport.FileImportService
import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.ImportedFile
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.PaneGroup
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.icons.ViewSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import multiplataform_vive.shared.generated.resources.Res
import multiplataform_vive.shared.generated.resources.import_file_selected
import org.jetbrains.compose.resources.pluralStringResource
import kotlin.math.roundToInt

internal object ImportFileTags {
    const val DIALOG = "import-file-dialog"
    const val PAGES = "import-file-pages"
    const val INSERT = "import-file-insert"
    const val CLOSE = "import-file-close"
    const val MESSAGE = "import-file-message"
    const val SELECT_ALL = "import-file-select-all"
    const val SELECT_NONE = "import-file-select-none"

    /** Android's `PanelTags.field("Insert as")`, with each choice as one of its options. */
    const val AS_FIELD = "Insert as"

    /** One page in the grid. */
    fun page(index: Int): String = "import-file-page-$index"

    fun mode(mode: FileImportMode): String = ToolPaneTags.option(AS_FIELD, mode.name)
}

/**
 * Choosing what to bring in from a file.
 *
 * Export as PDF's window and for the same reason: this is a decision taken once, and the way to take it
 * well is to see the pages. The pages are a grid rather than a stack because here they are something
 * to pick from, and a picker wants several in view at once.
 *
 * Every page starts chosen, so the common case is one click on Insert; the ones not wanted are a click
 * each.
 *
 * It covers the workspace rather than replacing it, as Export PDF's does: the canvas under it is what
 * measures the text the pages bring and says where the page's content ends.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ImportFileDialog(
    importer: FileImportService,
    open: suspend () -> ImportedFile,
    onInsert: suspend (ImportedFile, List<Int>, FileImportMode) -> Boolean,
    onDismiss: () -> Unit,
) {
    var file by remember { mutableStateOf<ImportedFile?>(null) }
    var opening by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(emptySet<Int>()) }
    var mode by remember { mutableStateOf(FileImportMode.Objects) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(Unit) {
        try {
            val opened = open()
            file = opened
            chosen = opened.pages.indices.toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            message = failureMessage(failure)
        }
        opening = false
    }

    // A document holds a staged copy, decoded pictures and a page of layouts; none of it outlives this.
    DisposableEffect(Unit) {
        onDispose { importer.release() }
    }

    val insert: () -> Unit = insert@{
        val current = file ?: return@insert
        if (busy || chosen.isEmpty()) return@insert
        busy = true
        message = null
        scope.launch {
            val landed = try {
                onInsert(current, chosen.sorted(), mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            busy = false
            if (landed) onDismiss() else message = Strings.import_file_insert_failed
        }
    }

    Box(
        Modifier.fillMaxSize().focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            // The window is the whole of the screen while it is open; Tab stays inside it.
            .focusProperties { onExit = { cancelFocusChange() } }.focusGroup().focusable(),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ImportFileTags.DIALOG),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                ImportFileBar(
                    file = file,
                    chosen = chosen.size,
                    ready = file != null && chosen.isNotEmpty() && !busy,
                    busy = busy,
                    onInsert = insert,
                    onClose = onDismiss,
                )
                HorizontalDivider()

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag(ImportFileTags.MESSAGE),
                    )
                }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sideBySide = maxWidth >= SIDE_BY_SIDE_BREAKPOINT
                    val pagesPane: @Composable (Modifier) -> Unit = { modifier ->
                        PageGrid(
                            importer = importer,
                            file = file,
                            opening = opening,
                            chosen = chosen,
                            onToggle = { index, on -> chosen = if (on) chosen + index else chosen - index },
                            modifier = modifier,
                        )
                    }
                    val optionsPane: @Composable (Modifier) -> Unit = { modifier ->
                        ImportFileOptions(
                            mode = mode,
                            enabled = file != null,
                            onMode = { mode = it },
                            onSelectAll = { file?.let { chosen = it.pages.indices.toSet() } },
                            onSelectNone = { chosen = emptySet() },
                            modifier = modifier,
                        )
                    }
                    if (sideBySide) {
                        Row(Modifier.fillMaxSize()) {
                            pagesPane(Modifier.weight(1f).fillMaxHeight())
                            VerticalDivider()
                            optionsPane(Modifier.width(OPTIONS_WIDTH).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            pagesPane(Modifier.weight(1f).fillMaxWidth())
                            HorizontalDivider()
                            optionsPane(Modifier.fillMaxWidth().height(OPTIONS_HEIGHT))
                        }
                    }
                }
            }
        }
    }
}

private fun failureMessage(failure: Exception): String = when ((failure as? ImportException)?.failure) {
    ImportFailure.NotWord -> Strings.import_file_not_word
    ImportFailure.PasswordProtected -> Strings.import_file_password
    ImportFailure.TooLarge -> Strings.import_file_too_large
    else -> Strings.import_file_unreadable
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportFileBar(
    file: ImportedFile?,
    chosen: Int,
    ready: Boolean,
    busy: Boolean,
    onInsert: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(ImportFileTags.CLOSE)) {
            Icon(ViewSymbols.Close, contentDescription = Strings.close)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(Strings.import_file_title, style = MaterialTheme.typography.titleMedium)
            if (file != null) {
                val count = file.pages.size
                Text(
                    text = "${file.name} · " + pluralStringResource(Res.plurals.import_file_selected, count, chosen, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (busy) {
            LoadingIndicator(Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Button(
            onClick = onInsert,
            enabled = ready,
            modifier = Modifier.testTag(ImportFileTags.INSERT),
        ) {
            Text(Strings.import_file_insert)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageGrid(
    importer: FileImportService,
    file: ImportedFile?,
    opening: Boolean,
    chosen: Set<Int>,
    onToggle: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (file == null) {
            if (opening) LoadingIndicator()
            return@Box
        }
        // Lazily, because each is a full render of a page and a document can be hundreds of them.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(SHEET_MIN_WIDTH),
            modifier = Modifier
                .fillMaxSize()
                .testTag(ImportFileTags.PAGES),
            contentPadding = PaddingValues(GRID_PADDING),
            verticalArrangement = Arrangement.spacedBy(GRID_PADDING),
            horizontalArrangement = Arrangement.spacedBy(GRID_PADDING),
        ) {
            items(file.pages.indices.toList(), key = { it }) { index ->
                PageSheet(
                    importer = importer,
                    file = file,
                    index = index,
                    selected = index in chosen,
                    onToggle = { onToggle(index, it) },
                )
            }
        }
    }
}

/**
 * One page: click anywhere on it to choose it or not.
 *
 * The check is the state and the page is the target, which is why the [Checkbox] takes no clicks of
 * its own. The box has its shape before any picture is drawn into it, so the grid does not shuffle.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageSheet(
    importer: FileImportService,
    file: ImportedFile,
    index: Int,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val page = file.pages[index]
    val border by animateDpAsState(
        targetValue = if (selected) SELECTED_BORDER else UNSELECTED_BORDER,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val paperAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else UNSELECTED_ALPHA,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(sheetRatio(page))
                .background(SHEET_PAPER)
                .border(border, borderColor)
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = onToggle)
                .testTag(ImportFileTags.page(index)),
            contentAlignment = Alignment.Center,
        ) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                .let { (it * shownShare(page)).roundToInt() }
            var bitmap by remember(file, index, widthPx) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(file, index, widthPx) {
                if (widthPx > 0) bitmap = runCatching { importer.preview(file, index, widthPx) }.getOrNull()
            }
            val sheet = bitmap
            if (sheet == null) {
                LoadingIndicator()
            } else {
                Image(
                    bitmap = sheet,
                    contentDescription = Strings.import_file_page(index + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(paperAlpha),
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = Strings.import_file_page_of(index + 1, file.pages.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sheetRatio(page: ImportedPageSize): Float = pageRatio(page).coerceIn(MIN_SHEET_RATIO, MAX_SHEET_RATIO)

private fun pageRatio(page: ImportedPageSize): Float {
    val ratio = page.widthDp / page.heightDp
    return if (ratio.isFinite() && ratio > 0f) ratio else 1f
}

private fun shownShare(page: ImportedPageSize): Float = minOf(1f, pageRatio(page) / sheetRatio(page))

@Composable
private fun ImportFileOptions(
    mode: FileImportMode,
    enabled: Boolean,
    onMode: (FileImportMode) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        PaneGroup(Strings.pages) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onSelectAll,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag(ImportFileTags.SELECT_ALL),
                ) {
                    Text(Strings.select_all, maxLines = 1)
                }
                TextButton(
                    onClick = onSelectNone,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag(ImportFileTags.SELECT_NONE),
                ) {
                    Text(Strings.clear, maxLines = 1)
                }
            }
        }

        // Two choices, each a whole row as in a GNOME preferences list, and what they mean under the
        // title where Android keeps it behind an info button.
        PaneGroup(Strings.import_file_as) {
            FileImportMode.entries.forEachIndexed { index, choice ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModeRow(
                    label = when (choice) {
                        FileImportMode.Objects -> Strings.import_file_as_objects
                        FileImportMode.Pictures -> Strings.import_file_as_pictures
                    },
                    selected = choice == mode,
                    enabled = enabled,
                    onClick = { onMode(choice) },
                    modifier = Modifier.testTag(ImportFileTags.mode(choice)),
                )
            }
        }
        Text(
            text = Strings.import_file_as_info,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
        )
    }
}

@Composable
private fun ModeRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        )
    }
}

/** Wide enough for a useful grid and a pane of controls side by side. */
private val SIDE_BY_SIDE_BREAKPOINT = 720.dp
private val OPTIONS_WIDTH = 320.dp
private val OPTIONS_HEIGHT = 260.dp

/** Several pages in view at once, while each stays recognisable. */
private val SHEET_MIN_WIDTH = 240.dp
private const val MIN_SHEET_RATIO = 0.25f
private const val MAX_SHEET_RATIO = 4f
private val GRID_PADDING = 20.dp
private val SELECTED_BORDER = 3.dp
private val UNSELECTED_BORDER = 1.dp

/** A page left out reads as set aside, and still shows what it is. */
private const val UNSELECTED_ALPHA = 0.5f
private const val DISABLED_ALPHA = 0.42f

/** A rendered page is white whatever the theme, so the placeholder under it is too. */
private val SHEET_PAPER = Color.White
