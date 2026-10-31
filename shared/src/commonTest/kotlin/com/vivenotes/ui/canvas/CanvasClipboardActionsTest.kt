package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import com.vivenotes.data.CanvasClipboardSource
import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.ClipboardHtml
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.TextClipboard
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Outline
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A paste from another app reads its markup and fetches its pictures at once, and says where it will
 * land until it has. Ported from the Android app's `CanvasExternalPasteTest` view-model cases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasClipboardActionsTest {
    private var state = WorkspaceState.demo().clearCanvasFocus()
    private val source = Source()
    private val pictures = Pictures()
    private val log = mutableListOf<String>()

    private fun TestScope.actions() = CanvasClipboardActions({ state }, { state = it(state) }, source, source, pictures,
        this, DebugLog(enabled = true) { log += it }, pasteDispatcher = StandardTestDispatcher(testScheduler))

    private fun images() = state.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>()

    /** Both pictures are asked for before either has arrived: downloads run side by side. */
    @Test
    fun picturesDownloadAtOnceAndThePasteShowsWhereItWillLand() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.ready = gate
        source.content = ClipboardContent(html = "<img src='https://example.org/a.png'><p>Between</p><img src='https://example.org/b.png'>")
        val actions = actions()
        actions.pasteExternal(150f, 250f)
        assertEquals(listOf(PendingPaste(state.selectedPageId, Offset(150f, 250f))), actions.pendingPastes)
        runCurrent()
        assertEquals(listOf("https://example.org/a.png", "https://example.org/b.png"), source.downloads)
        assertEquals(listOf(PendingPaste(state.selectedPageId, Offset(150f, 250f))), actions.pendingPastes)

        gate.complete(Unit)
        runCurrent()
        actions.pendingLayout?.let { request -> actions.completeLayout(request, request.outlines.associate { it.id to 40f }) }
        advanceUntilIdle()
        assertEquals(2, images().size)
        assertTrue(actions.pendingPastes.isEmpty(), "landed, so nothing is pending")
        assertTrue(log.any { "from 3 runs, 2 pictures" in it }, log.toString())
        assertTrue(log.none { "example.org" in it }, "the debug log never names an address")
    }

    @Test
    fun aCopiedBitmapLeavesTheMarkupsPicturesUndownloaded() = runTest {
        source.content = ClipboardContent(html = "<p>Preview</p><img src='https://example.org/preview.png'>", image = byteArrayOf(4, 2))
        actions().pasteExternal(150f, 250f)
        advanceUntilIdle()
        assertEquals(1, images().size)
        assertTrue(source.downloads.isEmpty())
    }

    @Test
    fun aFailedPasteIsNoLongerPending() = runTest {
        source.failing = true
        source.content = ClipboardContent(text = "anything")
        val before = state.selectedPage!!.document
        val actions = actions()
        actions.pasteExternal(150f, 250f)
        advanceUntilIdle()
        assertTrue(actions.pendingPastes.isEmpty())
        assertEquals(before, state.selectedPage!!.document)
    }

    /** Pastes land one at a time, but each shows where it will land from the moment it is asked for. */
    @Test
    fun aPasteWaitingItsTurnIsAlreadyShown() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.ready = gate
        source.content = ClipboardContent(text = "https://example.org/slow.png")
        val actions = actions()
        actions.pasteExternal(150f, 250f)
        runCurrent()
        source.content = ClipboardContent(text = "Second")
        actions.pasteExternal(400f, 500f)
        runCurrent()
        assertEquals(listOf(Offset(150f, 250f), Offset(400f, 500f)), actions.pendingPastes.map { it.at })

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(actions.pendingPastes.isEmpty())
        assertEquals(1, images().size)
        assertEquals("Second", state.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().last().blocks.single().text)
    }

    private class Source : CanvasClipboardSource, ClipboardHtml, TextClipboard {
        var content = ClipboardContent()
        var ready: CompletableDeferred<Unit>? = null
        var failing = false
        val downloads = mutableListOf<String>()
        override fun read() = content.html
        override fun getText() = content.text?.let(::AnnotatedString)
        override fun setText(annotatedString: AnnotatedString) { content = ClipboardContent(text = annotatedString.text) }
        override fun hasContent() = content.text != null || content.html != null || content.image != null || content.imageUrl != null
        override suspend fun readContent(): ClipboardContent = if (failing) error("unreadable") else content
        override suspend fun imageBytes(address: String): ByteArray? {
            downloads += address
            ready?.await()
            return byteArrayOf(1)
        }
        override fun markInternalCopy() {}
        override fun ownsInternalCopy() = false
    }

    private class Pictures : PictureLibrary {
        var imports = 0
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray) = ImportedPicture("stored-${imports++}", 400, 200)
        override suspend fun bytes(attachmentId: String): ByteArray? = null
    }
}
