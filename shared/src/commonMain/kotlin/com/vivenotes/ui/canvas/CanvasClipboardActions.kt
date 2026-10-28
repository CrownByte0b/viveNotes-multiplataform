package com.vivenotes.ui.canvas

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivenotes.data.CanvasClipboardSource
import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.ClipboardHtml
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Outline
import com.vivenotes.model.newId
import com.vivenotes.richtext.CanvasPasteItem
import com.vivenotes.richtext.canvasPasteItems
import com.vivenotes.richtext.plainPasteBlocks
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.stackPastedOutlines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CanvasClipboardActions(
    private val state: () -> WorkspaceState,
    private val change: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val clipboard: ClipboardManager,
    private val html: ClipboardHtml?,
    private val pictures: PictureLibrary?,
    private val scope: CoroutineScope,
    private val log: DebugLog,
) {
    private val source get() = html as? CanvasClipboardSource
    private val pasteLock = Mutex()
    var pendingLayout by mutableStateOf<CanvasPasteLayoutRequest?>(null)
        private set

    fun completeLayout(request: CanvasPasteLayoutRequest, heights: Map<String, Float>) {
        if (pendingLayout === request) request.result.complete(stackPastedOutlines(request.outlines, heights))
    }

    fun useInternal(): Boolean = !state().canvasClipboard.isEmpty && (source?.ownsInternalCopy() != false)

    fun canPaste(): Boolean = state().selectedPage?.editable == true &&
        (useInternal() || source?.hasContent() == true || clipboard.hasText())

    fun pasteExternal(x: Float, y: Float) {
        val before = state()
        val pageId = before.selectedPage?.takeIf { it.editable }?.id ?: return
        val marks = before.editorDefaults.textMarks(before.fontFamilyChoice, before.fontSizeChoice)
        scope.launch {
            pasteLock.withLock {
                try {
                    val content = source?.readContent() ?: ClipboardContent(clipboard.getText()?.text, html?.read())
                    val items = withContext(Dispatchers.Default) { canvasPasteItems(content, marks) }
                    val outlines = mutableListOf<Outline>()
                    for (item in items) {
                        val outline = when (item) {
                            is CanvasPasteItem.Text -> {
                                val blocks = before.textClipboard?.takeIf { content.html == null && it.text == content.text }
                                    ?.blocks ?: item.blocks
                                Outline.Text.empty().copy(blocks = blocks)
                            }
                            is CanvasPasteItem.Table -> item.outline
                            is CanvasPasteItem.Image -> {
                                val bytes = item.bytes ?: item.address?.let { source?.imageBytes(it) }
                                val picture = bytes?.let { pictures?.import(it) }
                                if (picture != null) Outline.Image(newId(), attachmentId = picture.attachmentId,
                                    width = Outline.Image.DEFAULT_WIDTH,
                                    height = (Outline.Image.DEFAULT_WIDTH * picture.pixelHeight / picture.pixelWidth.coerceAtLeast(1))
                                        .coerceAtLeast(Outline.Image.MIN_SIZE))
                                else {
                                    log.event("clipboard") { "canvas paste: picture unavailable" }
                                    val fallback = item.address?.takeUnless { it.startsWith("data:", true) } ?: content.text
                                    fallback?.takeIf { it.isNotBlank() }?.let {
                                        Outline.Text.empty().copy(blocks = plainPasteBlocks(it, marks))
                                    }
                                }
                            }
                        } ?: continue
                        outlines += outline
                    }
                    val placed = if (outlines.size < 2) outlines else {
                        val request = CanvasPasteLayoutRequest(outlines)
                        pendingLayout = request
                        request.result.await()
                    }
                    if (placed.isNotEmpty()) change { it.insertClipboardOutlines(pageId, placed, x, y) }
                    log.event("clipboard") { "canvas paste: prepared ${outlines.size} objects" }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    log.event("clipboard") { "canvas paste failed: ${error::class.simpleName}" }
                } finally { pendingLayout = null }
            }
        }
    }
}

internal class CanvasPasteLayoutRequest(val outlines: List<Outline>) {
    val result = CompletableDeferred<List<Outline>>()
}
