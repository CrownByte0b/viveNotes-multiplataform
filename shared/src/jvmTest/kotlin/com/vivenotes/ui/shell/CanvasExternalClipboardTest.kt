package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import com.vivenotes.data.TextClipboard
import com.vivenotes.data.LocalTextClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.vivenotes.data.CanvasClipboardSource
import com.vivenotes.data.ClipboardContent
import com.vivenotes.data.ClipboardHtml
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.localization.Strings
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.ui.canvas.CanvasPasteTags
import com.vivenotes.ui.canvas.LocalClipboardHtml
import com.vivenotes.ui.ribbon.draw.TableTags
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CanvasExternalClipboardTest {
    @Test
    fun ctrlVPastesExternalTextAsASelectedBoxAndUndoRedoRestoreIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val source = Source(ClipboardContent(text = "A new box\nSecond line"))
        setWorkspace(observed, source) { observed = it }
        point()
        ctrl(Key.V)
        waitUntil { observed.selectedTextOutlineIds.isNotEmpty() }
        runOnIdle {
            val text = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().last()
            assertEquals(listOf("A new box", "Second line"), text.blocks.map { it.text })
            assertEquals(650f, text.x, 0.5f)
            assertEquals(500f, text.y, 0.5f)
            assertEquals(1, observed.structuralUndo.size)
        }
        ctrl(Key.Z)
        runOnIdle { assertEquals(1, observed.selectedPage!!.document.outlines.size) }
        ctrl(Key.R)
        runOnIdle { assertEquals(2, observed.selectedPage!!.document.outlines.size) }
    }

    @Test
    fun rightClickPasteCreatesARichEditableTable() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val source = Source(ClipboardContent(html = "<table><tr><th>Name</th><th>Value</th></tr><tr><td><b>Alpha</b></td><td>42</td></tr></table>"))
        setWorkspace(observed, source) { observed = it }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(500f, 350f), MouseButton.Secondary) }
        onNodeWithTag(WorkspaceTestTags.CanvasPaste).performClick()
        waitUntil { observed.selectedPage!!.document.outlines.any { it is Outline.Table } }
        val table = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        runOnIdle {
            assertFalse(table.inkOnly)
            assertTrue(table.headerRow)
            assertEquals("42", table.cellAt(1, 1)!!.plainText)
            assertTrue(table.cellAt(1, 0)!!.blocks.flatMap { it.runs }.all { Mark.Bold in it.marks })
        }
        onNodeWithTag(TableTags.cell(table.cellAt(1, 1)!!.id)).performClick().performTextInput("!")
        runOnIdle {
            assertTrue(observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single().cellAt(1, 1)!!.plainText.contains('!'))
        }
    }

    @Test
    fun browserImageReferenceIsDownloadedImportedAndPlacedWithItsAspectRatio() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val source = Source(ClipboardContent(html = "<a href='https://example.org/page'><img src='https://example.org/image.png'></a>"))
        val pictures = Pictures()
        setWorkspace(observed, source, pictures) { observed = it }
        point()
        ctrl(Key.V)
        waitUntil { observed.selectedPage!!.document.outlines.any { it is Outline.Image } }
        runOnIdle {
            assertEquals(listOf("https://example.org/image.png"), source.downloads)
            assertEquals(1, pictures.imports)
            val image = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().single()
            assertEquals("stored-image", image.attachmentId)
            assertEquals(320f, image.width)
            assertEquals(160f, image.height)
            assertEquals(650f, image.x, 0.5f)
            assertEquals(500f, image.y, 0.5f)
            assertEquals(1, observed.structuralUndo.size)
        }
    }

    @Test
    fun copyingInAnotherAppTakesPrecedenceOverAnOlderObjectCopy() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val shape = Outline.Shape("original", x = 300f, y = 300f)
        var observed = initial().let { state -> state.updatePage(state.selectedPageId) {
            it.copy(document = it.document.copy(outlines = it.document.outlines + shape))
        } }.selectObject(shape.id)
        val source = Source()
        setWorkspace(observed, source) { observed = it }
        ctrl(Key.C)
        runOnIdle { assertTrue(source.internal) }
        point()
        ctrl(Key.V)
        runOnIdle { assertEquals(2, observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().size) }
        runOnIdle {
            source.internal = false
            source.content = ClipboardContent(text = "Copied in the browser")
        }
        ctrl(Key.V)
        waitUntil { observed.selectedTextOutlineIds.isNotEmpty() }
        runOnIdle {
            assertEquals(2, observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().size)
            assertEquals("Copied in the browser", observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().last().blocks.single().text)
        }
    }

    @Test
    fun failedImageDownloadPastesTheAddressAsText() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val source = Source(ClipboardContent(text = "https://example.org/missing.png"), image = null)
        setWorkspace(observed, source, Pictures()) { observed = it }
        point()
        ctrl(Key.V)
        waitUntil { observed.selectedTextOutlineIds.isNotEmpty() }
        runOnIdle {
            assertEquals("https://example.org/missing.png", observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().last().blocks.single().text)
        }
    }

    @Test
    fun aNativeCopiedPictureDoesNotNeedANetworkRequest() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val source = Source(ClipboardContent(image = byteArrayOf(1, 2)))
        setWorkspace(observed, source, Pictures()) { observed = it }
        point()
        ctrl(Key.V)
        waitUntil { observed.selectedPage!!.document.outlines.any { it is Outline.Image } }
        runOnIdle { assertTrue(source.downloads.isEmpty()) }
    }

    @Test
    fun aPendingImagePasteDoesNotLandOnAPageOpenedDuringTheDownload() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val originalPage = observed.selectedPageId
        val ready = CompletableDeferred<Unit>()
        val source = Source(ClipboardContent(text = "https://example.org/slow.png"), ready = ready)
        val pictures = Pictures()
        setWorkspace(observed, source, pictures) { observed = it }
        point()
        ctrl(Key.V)
        waitUntil { source.downloads.isNotEmpty() }
        ctrl(Key.N)
        waitUntil { observed.selectedPageId != originalPage }
        runOnIdle { ready.complete(Unit) }
        waitUntil { pictures.imports == 1 }
        runOnIdle {
            assertTrue(observed.selectedPage!!.document.outlines.none { it is Outline.Image })
            val old = observed.notebooks.flatMap { it.sections }.flatMap { it.pages }.single { it.id == originalPage }
            assertTrue(old.document.outlines.none { it is Outline.Image })
        }
    }

    /** While a picture downloads, the paste shows where it will land, and stops when it has. */
    @Test
    fun aPasteStillDownloadingShowsItsProgressWhereItWillLand() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val ready = CompletableDeferred<Unit>()
        val source = Source(ClipboardContent(html = "<img src='https://example.org/slow.png'>"), image = null, ready = ready)
        setWorkspace(observed, source, Pictures()) { observed = it }
        val at = Offset(500f, 350f)
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(at, MouseButton.Secondary) }
        onNodeWithTag(WorkspaceTestTags.CanvasPaste).performClick()
        waitUntil { onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().isNotEmpty() }
        val page = onNodeWithTag(WorkspaceTestTags.CanvasExtent).fetchSemanticsNode().boundsInRoot
        val shown = onNodeWithTag(CanvasPasteTags.Progress).fetchSemanticsNode().boundsInRoot
        assertEquals(page.left + at.x, shown.left, 2f, "drawn from where the paste lands")
        assertEquals(page.top + at.y, shown.top, 2f)
        onNodeWithTag(CanvasPasteTags.Progress).assertContentDescriptionEquals(Strings.pasting)

        runOnIdle { ready.complete(Unit) }
        waitUntil { onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().isEmpty() }
        // Nothing came back for the picture, so its address is pasted instead, where it was shown.
        runOnIdle {
            val text = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().last()
            assertEquals("https://example.org/slow.png", text.blocks.single().text)
            assertEquals(at.x, text.x, 0.5f)
            assertEquals(at.y, text.y, 0.5f)
        }
    }

    /** Ctrl+V pressed again while a picture downloads: two pastes at one point, each shown and placed. */
    @Test
    fun twoPastesAtOnePointEachShowAndLand() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val ready = CompletableDeferred<Unit>()
        val source = Source(ClipboardContent(text = "https://example.org/slow.png"), ready = ready)
        val pictures = Pictures()
        setWorkspace(observed, source, pictures) { observed = it }
        point()
        ctrl(Key.V)
        ctrl(Key.V)
        waitUntil { onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().size == 2 }
        runOnIdle { ready.complete(Unit) }
        waitUntil { observed.selectedPage!!.document.outlines.count { it is Outline.Image } == 2 }
        waitUntil { onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().isEmpty() }
        runOnIdle { assertEquals(2, observed.structuralUndo.size, "each paste is its own step of Undo") }
    }

    @Test
    fun aPasteStillDownloadingIsShownOnlyOnItsOwnPage() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val originalPage = observed.selectedPageId
        val ready = CompletableDeferred<Unit>()
        val source = Source(ClipboardContent(text = "https://example.org/slow.png"), ready = ready)
        val pictures = Pictures()
        setWorkspace(observed, source, pictures) { observed = it }
        point()
        ctrl(Key.V)
        waitUntil { onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().isNotEmpty() }
        ctrl(Key.N)
        waitUntil { observed.selectedPageId != originalPage }
        waitForIdle()
        assertTrue(onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().isEmpty())

        onNodeWithTag(WorkspaceTestTags.page(originalPage)).performClick()
        waitUntil { observed.selectedPageId == originalPage }
        waitUntil { onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { ready.complete(Unit) }
        waitUntil { pictures.imports == 1 }
        waitUntil { onAllNodesWithTag(CanvasPasteTags.Progress).fetchSemanticsNodes().isEmpty() }
    }

    private fun initial() = WorkspaceState.demo().let { state ->
        state.updatePage(state.selectedPageId) { page -> page.copy(document = page.document.copy(
            outlines = listOf(state.bodyTextOutline!!.copy(blocks = listOf(Block.of("Existing")))))) }
    }.clearCanvasFocus()

    private fun ComposeUiTest.point() = onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { moveTo(Offset(650f, 500f)) }

    private fun ComposeUiTest.ctrl(key: Key) = onRoot().performKeyInput {
        keyDown(Key.CtrlLeft); keyDown(key); keyUp(key); keyUp(Key.CtrlLeft)
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, source: Source, pictures: PictureLibrary? = null,
        observe: (WorkspaceState) -> Unit) {
        val clipboard = object : TextClipboard {
            override fun getText() = source.content.text?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) { source.content = ClipboardContent(text = annotatedString.text); source.internal = false }
        }
        setContent {
            var state by remember { mutableStateOf(initial) }
            CompositionLocalProvider(LocalTextClipboard provides clipboard, LocalClipboardHtml provides source) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); observe(state) }, pictures = pictures)
            }
        }
    }

    private class Source(var content: ClipboardContent = ClipboardContent(), private val image: ByteArray? = byteArrayOf(1),
        private val ready: CompletableDeferred<Unit>? = null) : CanvasClipboardSource, ClipboardHtml {
        var internal = false
        val downloads = mutableListOf<String>()
        override fun read() = content.html
        override fun hasContent() = content.text != null || content.html != null || content.image != null || content.imageUrl != null
        override suspend fun readContent() = content
        override suspend fun imageBytes(address: String): ByteArray? {
            downloads += address
            ready?.await()
            return image
        }
        override fun markInternalCopy() { internal = true }
        override fun ownsInternalCopy() = internal
    }

    private class Pictures : PictureLibrary {
        var imports = 0
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray): ImportedPicture { imports++; return ImportedPicture("stored-image", 400, 200) }
        override suspend fun bytes(attachmentId: String): ByteArray? = null
    }
}
