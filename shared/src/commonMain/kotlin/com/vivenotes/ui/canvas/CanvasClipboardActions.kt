package com.vivenotes.ui.canvas

import com.vivenotes.data.TextClipboard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.vivenotes.data.CanvasClipboardSource
import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.ClipboardHtml
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.newId
import com.vivenotes.richtext.CanvasPasteItem
import com.vivenotes.richtext.CanvasPastePlan
import com.vivenotes.richtext.plainPasteBlocks
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.stackPastedOutlines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A paste from another app on its way to [pageId], whose top-left will be [at], in page dp. */
internal data class PendingPaste(val pageId: String, val at: Offset)

internal class CanvasClipboardActions(
    private val state: () -> WorkspaceState,
    private val change: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val clipboard: TextClipboard,
    private val html: ClipboardHtml?,
    private val pictures: PictureLibrary?,
    private val scope: CoroutineScope,
    private val log: DebugLog,
    /** Where a paste's markup is scanned and read; a test passes its own scheduler. */
    private val pasteDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val source get() = html as? CanvasClipboardSource
    private val pasteLock = Mutex()
    var pendingLayout by mutableStateOf<CanvasPasteLayoutRequest?>(null)
        private set

    /** Pastes from another app still being read or downloaded, for the canvas to show where they will land. */
    var pendingPastes by mutableStateOf<List<PendingPaste>>(emptyList())
        private set

    fun completeLayout(request: CanvasPasteLayoutRequest, heights: Map<String, Float>) {
        if (pendingLayout === request) request.result.complete(stackPastedOutlines(request.outlines, heights))
    }

    fun useInternal(): Boolean = !state().canvasClipboard.isEmpty && (source?.ownsInternalCopy() != false)

    fun canPaste(): Boolean = state().selectedPage?.editable == true &&
        (useInternal() || source?.hasContent() == true || clipboard.hasText())

    /**
     * Pastes what another app copied, with its top-left at ([x], [y]): plain or rich text becomes a
     * text box, a copied picture or a picture's address a picture, and an HTML table an editable
     * table, in the order the copy held them — see [CanvasPastePlan]. Until it lands,
     * [pendingPastes] says where it will.
     */
    fun pasteExternal(x: Float, y: Float) {
        val before = state()
        val pageId = before.selectedPage?.takeIf { it.editable }?.id ?: return
        val marks = before.editorDefaults.textMarks(before.fontFamilyChoice, before.fontSizeChoice)
        val pending = PendingPaste(pageId, Offset(x, y))
        pendingPastes += pending
        scope.launch {
            try {
                pasteLock.withLock { paste(before, marks, pending) }
            } finally {
                pendingPastes -= pending
            }
        }
    }

    /** One paste from another app, start to finish; [pasteExternal] runs them one at a time. */
    private suspend fun paste(before: WorkspaceState, marks: Set<Mark>, pending: PendingPaste) {
        try {
            val content = source?.readContent() ?: ClipboardContent(clipboard.getText()?.text, html?.read())
            val plan = withContext(pasteDispatcher) { CanvasPastePlan(content, marks) }
            val outlines = coroutineScope {
                // Every picture starts downloading the moment the scan finds it, while the
                // tables and the text between them are read, all of them at once.
                val fetches = plan.pictures.map { picture ->
                    picture to async { pastedPicture(picture, content, marks) }
                }
                val read = plan.runs.map { run -> async(pasteDispatcher) { run.read() } }.awaitAll()
                val items = plan.resolve(read)
                // A picture the paste turned out not to need — a copied bitmap won over the
                // markup — is not waited for.
                fetches.filter { (picture, _) -> items.none { it === picture } }.forEach { it.second.cancel() }
                items.mapNotNull { item ->
                    when (item) {
                        is CanvasPasteItem.Text -> {
                            val blocks = before.textClipboard?.takeIf { content.html == null && it.text == content.text }
                                ?.blocks ?: item.blocks
                            Outline.Text.empty().copy(blocks = blocks)
                        }
                        is CanvasPasteItem.Table -> item.outline
                        is CanvasPasteItem.Image -> fetches.firstOrNull { it.first === item }?.second?.await()
                            ?: pastedPicture(item, content, marks)
                    }
                }
            }
            val placed = if (outlines.size < 2) outlines else {
                val request = CanvasPasteLayoutRequest(outlines)
                pendingLayout = request
                request.result.await()
            }
            if (placed.isNotEmpty()) change { it.insertClipboardOutlines(pending.pageId, placed, pending.at.x, pending.at.y) }
            log.event("clipboard") { "canvas paste: prepared ${outlines.size} objects from ${plan.runs.size} runs, ${plan.pictures.size} pictures" }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            log.event("clipboard") { "canvas paste failed: ${error::class.simpleName}" }
        } finally { pendingLayout = null }
    }

    /**
     * [item] stored as a picture, or — when it cannot be fetched or is not a picture — its address,
     * or the copy's text, as a text box. Unplaced pictures are left to `AttachmentSweep`.
     */
    private suspend fun pastedPicture(item: CanvasPasteItem.Image, content: ClipboardContent, marks: Set<Mark>): Outline? {
        val bytes = item.bytes ?: item.address?.let { source?.imageBytes(it) }
        val picture = bytes?.let { pictures?.import(it) }
        if (picture != null) return Outline.Image(newId(), attachmentId = picture.attachmentId,
            width = Outline.Image.DEFAULT_WIDTH,
            height = (Outline.Image.DEFAULT_WIDTH * picture.pixelHeight / picture.pixelWidth.coerceAtLeast(1))
                .coerceAtLeast(Outline.Image.MIN_SIZE))
        log.event("clipboard") { "canvas paste: picture unavailable" }
        val fallback = item.address?.takeUnless { it.startsWith("data:", true) } ?: content.text
        return fallback?.takeIf { it.isNotBlank() }?.let { Outline.Text.empty().copy(blocks = plainPasteBlocks(it, marks)) }
    }
}

internal class CanvasPasteLayoutRequest(val outlines: List<Outline>) {
    val result = CompletableDeferred<List<Outline>>()
}
