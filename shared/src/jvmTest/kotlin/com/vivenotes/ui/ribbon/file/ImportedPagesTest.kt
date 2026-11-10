// Ported from viveNotes Android androidTest ui/NotesViewModelTest.kt: the import cases of
// 8a27a5f and 813693e, against the desktop workspace's state instead of the ViewModel.
package com.vivenotes.ui.ribbon.file

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.fileimport.DocxTestFile
import com.vivenotes.fileimport.FileImportMode
import com.vivenotes.fileimport.FileImporter
import com.vivenotes.fileimport.ImportedFile
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.ui.shell.CanvasPlacement
import com.vivenotes.ui.shell.CanvasScrollTarget
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedPagesTest {
    private val root: File = Files.createTempDirectory("imported-pages").toFile()
    private val importer = FileImporter(File(root, "cache"))
    private var state = WorkspaceState.demo()
    private val placement = CanvasPlacement()
    private val pictures = Pictures()
    private val pages = ImportedPages({ state }, { state = it(state) }, pictures, placement)

    @AfterTest
    fun tearDown() {
        importer.release()
        root.deleteRecursively()
    }

    /** Stores nothing: a picture is an id and the size it says, as the attachment store reports one. */
    private class Pictures : PictureLibrary {
        val drawn = ArrayList<Pair<Int, Int>>()
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray) = ImportedPicture("picture-${bytes.size}", 400, 200)
        override suspend fun importDrawn(picture: ImageBitmap): ImportedPicture {
            drawn += picture.width to picture.height
            return ImportedPicture("page-${drawn.size}", picture.width, picture.height)
        }
        override suspend fun bytes(attachmentId: String): ByteArray? = null
    }

    private val outlines: List<Outline> get() = state.selectedPage!!.document.outlines

    private suspend fun open(name: String, body: String): ImportedFile {
        val docx = File(root, name)
        DocxTestFile.write(docx, body)
        return importer.open(docx.path)
    }

    /** A one-page document, ready to import. */
    private suspend fun openedImport(name: String): ImportedFile = open(name, DocxTestFile.paragraph("from the file"))

    /** What the canvas says once it has measured: each object [height] tall, and the content ending at [contentBottom]. */
    private fun TestScope.canvasAnswers(height: Float, contentBottom: Float) = launch(start = CoroutineStart.UNDISPATCHED) {
        placement.contentBottom = { contentBottom }
        while (placement.pendingLayout == null) delay(1)
        val request = placement.pendingLayout!!
        placement.complete(request, request.outlines.associate { it.id to height })
    }

    private fun blankPage(): WorkspaceState = state.copy(notebooks = state.notebooks.map { notebook ->
        notebook.copy(sections = notebook.sections.map { section ->
            section.copy(pages = section.pages.map { page ->
                if (page.id == state.selectedPageId) page.copy(document = PageDoc.empty()) else page
            })
        })
    })

    /** A document's pages arrive the way a paste does: separate objects, one step of Undo. */
    @Test
    fun aFilesPagesLandAsObjectsInOneStepOfUndo() = runTest {
        val opened = open(
            "view-model-import-objects.docx",
            DocxTestFile.paragraph("from the file") +
                DocxTestFile.table(
                    2,
                    DocxTestFile.row(
                        DocxTestFile.cell(DocxTestFile.paragraph("left")),
                        DocxTestFile.cell(DocxTestFile.paragraph("right")),
                    ),
                ),
        )
        val before = outlines

        val landed = pages.insert(importer, opened, listOf(0), FileImportMode.Objects)

        assertTrue(landed)
        val added = outlines - before.toSet()
        assertEquals(listOf("from the file"), added.filterIsInstance<Outline.Text>().map { box -> box.blocks.joinToString("\n") { it.text } })
        assertEquals("left", added.filterIsInstance<Outline.Table>().single().rows[0].cells[0].plainText)

        state = state.undoStructure()

        assertEquals(before, outlines)
    }

    /** Chosen as pictures, each page is one picture, in its own proportions, stacked down the canvas. */
    @Test
    fun aFilesPagesLandAsOnePicturePerPage() = runTest {
        val opened = open(
            "view-model-import-pictures.docx",
            DocxTestFile.paragraph("first") + DocxTestFile.paragraph("second", "<w:pageBreakBefore/>"),
        )

        val landed = pages.insert(importer, opened, listOf(0, 1), FileImportMode.Pictures)

        assertTrue(landed)
        val images = outlines.filterIsInstance<Outline.Image>()
        assertEquals(2, images.size)
        assertEquals(1760f / 1360f, images[0].height / images[0].width, 0.01f)
        assertTrue(images[1].y >= images[0].y + images[0].height, "the second page should be below the first")
        // Drawn as sharp as the store keeps, and never past it: 2048 pixels is the most a side may be.
        assertEquals(listOf(1582 to 2047, 1582 to 2047), pictures.drawn)
    }

    /** The bug this pins: both landed at the top of the view, one on top of the other. */
    @Test
    fun aSecondImportLandsBelowTheFirst() = runTest {
        val opened = openedImport("second-import.docx")

        assertTrue(pages.insert(importer, opened, listOf(0), FileImportMode.Pictures))
        assertTrue(pages.insert(importer, opened, listOf(0), FileImportMode.Pictures))

        val (first, second) = outlines.filterIsInstance<Outline.Image>()
        assertTrue(second.y >= first.y + first.height, "the second document overlaps the first")
    }

    /** The canvas's own figure wins: only it knows how the page's text wrapped. */
    @Test
    fun anImportLandsBelowWhereTheCanvasSaysTheContentEnds() = runTest {
        val pageId = state.selectedPageId!!
        val opened = openedImport("below-the-content.docx")
        val answering = canvasAnswers(height = 500f, contentBottom = 3000f)

        assertTrue(pages.insert(importer, opened, listOf(0), FileImportMode.Pictures))
        answering.cancel()

        val landed = outlines.filterIsInstance<Outline.Image>().single()
        assertEquals(3024f, landed.y, 0.01f)
        assertEquals(CanvasScrollTarget(pageId, 3024f), placement.scrollTarget, "the canvas should be sent to it")
        placement.onScrollTargetHandled()
        assertNull(placement.scrollTarget)
    }

    /** An empty text box is a place for a caret, not content to go below. */
    @Test
    fun anImportOntoABlankPageStartsUnderTheTitle() = runTest {
        state = blankPage()
        val opened = openedImport("blank-page.docx")
        // What the canvas reports for a blank page: the empty box still occupies its line.
        val answering = canvasAnswers(height = 500f, contentBottom = 180f)

        assertTrue(pages.insert(importer, opened, listOf(0), FileImportMode.Pictures))
        answering.cancel()

        assertEquals(PageStyle.TITLE_BAND_DP, outlines.filterIsInstance<Outline.Image>().single().y, 0.01f)
    }

    // --- desktop -----------------------------------------------------------------------------------

    /** On a blank page the import starts where the user is looking, a margin inside the view. */
    @Test
    fun anImportOntoABlankPageScrolledDownStartsInTheView() = runTest {
        state = blankPage()
        placement.visibleOrigin = { Offset(0f, 1200f) }
        val opened = openedImport("blank-page-scrolled.docx")

        assertTrue(pages.insert(importer, opened, listOf(0), FileImportMode.Pictures))

        assertEquals(1224f, outlines.filterIsInstance<Outline.Image>().single().y, 0.01f)
    }

    /** A page picture spans the view, no wider than the page really is and no narrower than a text box. */
    @Test
    fun aPagePictureIsAsWideAsTheViewUpToThePagesOwnWidth() = runTest {
        val opened = openedImport("wide-view.docx")
        placement.visibleWidth = { 1000f }
        assertTrue(pages.insert(importer, opened, listOf(0), FileImportMode.Pictures))
        placement.visibleWidth = { 4000f }
        assertTrue(pages.insert(importer, opened, listOf(0), FileImportMode.Pictures))

        val (narrow, wide) = outlines.filterIsInstance<Outline.Image>()
        assertEquals(1000f - 48f, narrow.width, 0.01f)
        assertEquals(1360f, wide.width, 0.01f)
    }

    /** A page whose body could not be read is never written to, so nothing lands there. */
    @Test
    fun anUnreadablePageTakesNothing() = runTest {
        state = state.copy(notebooks = state.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { page ->
                    if (page.id == state.selectedPageId) page.copy(content = PageContent.Unreadable) else page
                })
            })
        })
        val before = outlines
        val opened = openedImport("unreadable-page.docx")

        assertFalse(pages.insert(importer, opened, listOf(0), FileImportMode.Objects))

        assertEquals(before, outlines)
        assertNull(placement.scrollTarget)
    }
}
