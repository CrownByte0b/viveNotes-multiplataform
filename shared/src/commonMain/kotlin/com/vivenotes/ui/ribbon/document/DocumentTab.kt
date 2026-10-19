package com.vivenotes.ui.ribbon.document

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import com.vivenotes.data.PictureCamera
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Align
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.LocalClipboardHtml
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.EditorDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the Document tab's buttons do. A text command receives the editor range captured when its
 * press began, if there was one, because the click itself can take the editor's selection away.
 */
internal class DocumentCommands(
    val toggleTextTool: () -> Unit,
    val canChooseFont: Boolean,
    val toggleMark: (Mark, TextSelection?) -> Unit,
    val setMark: (Mark, TextSelection?) -> Unit,
    val chooseFontFamily: (String, TextSelection?) -> Unit,
    val chooseFontSize: (Int, TextSelection?) -> Unit,
    val setDefaultFontFamily: (String) -> Unit,
    val setDefaultFontSize: (Int) -> Unit,
    val clearMark: (Mark, TextSelection?) -> Unit,
    val clearFormatting: (TextSelection?) -> Unit,
    val setBlockType: (BlockType, TextSelection?) -> Unit,
    val align: (Align, TextSelection?) -> Unit,
    val indent: (Int, TextSelection?) -> Unit,
    val copy: (TextSelection?) -> Unit,
    val cut: (TextSelection?) -> Unit,
    val paste: (TextSelection?) -> Unit,
    val insertLink: (label: String, url: String, TextSelection?) -> Unit,
    /** Null when pictures cannot be inserted: no picture storage, or a page that may not change. */
    val insertPicture: ((PictureSource) -> Unit)?,
    /** The Picture menu's entries this computer can serve, asked each time the menu opens. */
    val pictureSources: suspend () -> List<PictureSource> = { listOf(PictureSource.Device) },
)

/**
 * The Document tab: its ribbon, wired to the workspace.
 *
 * [onEditorCommand] applies a text command and hands the keyboard back to the text box it edited.
 * [visibleOrigin] is the page point at the canvas's visible top left, where a picture is placed.
 */
@Composable
internal fun DocumentTab(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    pictures: PictureLibrary?,
    visibleOrigin: () -> Offset,
    colorSelection: DocumentColorSelection,
    onLinkRequest: (LinkEditorRequest) -> Unit,
    onEditorDefaultsChange: (EditorDefaults) -> Unit = {},
    /** Opens the Picture menu's Search online and Camera windows over the workspace. */
    onPictureDialog: (PictureDialog) -> Unit = {},
    log: DebugLog = DebugLog(),
) {
    val clipboard = TextClipboardActions(LocalClipboardManager.current, onEditorCommand, LocalClipboardHtml.current)
    val scope = rememberCoroutineScope()
    val currentState by rememberUpdatedState(state)
    fun WorkspaceState.withRibbonSelection(selection: TextSelection?): WorkspaceState =
        selection?.takeIf { it != editorSelection }?.let(::selectText) ?: this
    fun edit(selection: TextSelection?, command: WorkspaceState.() -> WorkspaceState) =
        onEditorCommand { it.withRibbonSelection(selection).command() }

    /**
     * Stores downloaded or photographed [bytes] and puts them on the page open now — Android's
     * `placeImage`: the page is read when the picture arrives, not when its window opened, and
     * [WorkspaceState.insertPicture] leaves the page alone if it changed while the bytes were stored.
     */
    fun placePicture(library: PictureLibrary, bytes: ByteArray) {
        val pageId = currentState.selectedPageId
        val origin = visibleOrigin()
        scope.launch {
            val picture = library.import(bytes)
            if (picture == null) {
                log.event("pictures") { "picture of ${bytes.size} bytes could not be stored" }
                return@launch
            }
            onStateChange { it.insertPicture(pageId, picture, origin.x, origin.y) }
            log.event("pictures") { "inserted ${picture.pixelWidth}x${picture.pixelHeight} picture" }
        }
    }

    val insertPicture = pictures?.takeIf { state.selectedPage?.editable == true }?.let { library ->
        { source: PictureSource ->
            log.event("pictures") { "insert from ${source.name}" }
            when (source) {
                PictureSource.Device -> {
                    val pageId = state.selectedPageId
                    val origin = visibleOrigin()
                    scope.launch {
                        val picture = library.choose() ?: return@launch
                        onStateChange { it.insertPicture(pageId, picture, origin.x, origin.y) }
                    }
                }
                PictureSource.Online -> library.online?.let { images ->
                    onPictureDialog(PictureDialog.Online(images) { bytes -> placePicture(library, bytes) })
                }
                PictureSource.Camera -> library.camera?.let { camera ->
                    onPictureDialog(PictureDialog.Camera(camera) { bytes -> placePicture(library, bytes) })
                }
            }
            Unit
        }
    }
    val pictureSources: suspend () -> List<PictureSource> = {
        buildList {
            add(PictureSource.Device)
            if (pictures?.online != null) add(PictureSource.Online)
            if (pictures?.camera?.hasCamera() == true) add(PictureSource.Camera)
        }
    }

    DocumentRibbon(
        // Text commands need a text box being edited, on a page that may change; without one they
        // are disabled rather than quietly editing some other box.
        richText = state.richText?.takeIf { state.selectedPage?.editable == true },
        textToolArmed = state.textToolArmed,
        commands = DocumentCommands(
            toggleTextTool = { onStateChange { it.toggleTextTool() } },
            canChooseFont = state.selectedPage?.editable == true,
            toggleMark = { mark, selection -> edit(selection) { toggleSelectedMark(mark) } },
            setMark = { mark, selection -> edit(selection) { setSelectedMark(mark) } },
            chooseFontFamily = { family, selection ->
                if (state.richText != null) edit(selection) { chooseFontFamily(family) }
                else onStateChange { it.chooseFontFamily(family) }
            },
            chooseFontSize = { size, selection ->
                if (state.richText != null) edit(selection) { chooseFontSize(size) }
                else onStateChange { it.chooseFontSize(size) }
            },
            setDefaultFontFamily = { family ->
                val next = state.editorDefaults.copy(fontFamily = family).normalized()
                onStateChange { it.setEditorDefaults(next) }
                onEditorDefaultsChange(next)
            },
            setDefaultFontSize = { size ->
                val next = state.editorDefaults.copy(fontSize = size).normalized()
                onStateChange { it.setEditorDefaults(next) }
                onEditorDefaultsChange(next)
            },
            clearMark = { mark, selection -> edit(selection) { clearSelectedMark(mark) } },
            clearFormatting = { selection -> edit(selection) { clearSelectedFormatting() } },
            setBlockType = { type, selection -> edit(selection) { setSelectedBlockType(type) } },
            align = { align, selection -> edit(selection) { alignSelectedText(align) } },
            indent = { delta, selection -> edit(selection) { indentSelectedText(delta) } },
            // The clipboard takes the text the user can see selected, from this frame's state.
            copy = { selection -> clipboard.copy(state, selection) },
            cut = { selection -> clipboard.cut(state, selection) },
            // Formatting included, as Ctrl+V does; the right-click menu also offers it without.
            paste = { selection -> clipboard.paste(selection, keepFormatting = true) },
            insertLink = { label, url, selection -> edit(selection) { insertLink(label, url) } },
            insertPicture = insertPicture,
            pictureSources = pictureSources,
        ),
        colorSelection = colorSelection,
        fontFamily = state.fontFamilyChoice,
        fontSize = state.fontSizeChoice,
        defaults = state.editorDefaults,
        onLinkRequest = onLinkRequest,
    )
}

/**
 * Whether this computer has a camera now. A slow or failing answer counts as none: the Picture menu
 * waits for it, and would rather leave Camera out than not open.
 */
private suspend fun PictureCamera.hasCamera(): Boolean = try {
    withTimeoutOrNull(CAMERA_QUERY_MS) { cameras().isNotEmpty() } == true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}

private const val CAMERA_QUERY_MS = 500L
