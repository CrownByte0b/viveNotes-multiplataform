package com.vivenotes.ui.ribbon.file

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.data.DeletedItem
import com.vivenotes.fileimport.DocxTestFile
import com.vivenotes.fileimport.FileImporter
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Import ▸ File through the whole workspace: the ribbon's menu, the platform's file chooser, the
 * window, and the canvas under it measuring what lands and where the page's content ends — Android's
 * `NotesApp` and `EditorPane` parts of 8a27a5f and 813693e.
 */
@OptIn(ExperimentalTestApi::class)
class ImportFileWorkspaceTest {
    private val root: File = Files.createTempDirectory("import-workspace").toFile()
    private val docx = File(root, "lecture.docx")
    private val importer = FileImporter(File(root, "cache"), chooseFile = { docx.path })
    private val actions = object : FileActions {
        override val fileImporter = importer
        override fun openPane(pane: FilePane) = Unit
        override fun closePane() = Unit
        override fun selectRevision(id: String) = Unit
        override fun restoreRevision() = Unit
        override fun restoreDeletedItem(item: DeletedItem) = Unit
        override fun reopenNotebook(id: String) = Unit
        override fun closeNotebook() = Unit
        override fun deleteNotebook() = Unit
        override fun exportNotebook() = Unit
        override fun importNotebook() = Unit
        override fun dismissTransfer() = Unit
    }
    private var shown = WorkspaceState.demo()

    @AfterTest
    fun tearDown() {
        importer.release()
        root.deleteRecursively()
    }

    private fun page(vararg outlines: Outline): WorkspaceState {
        val demo = WorkspaceState.demo()
        return demo.copy(notebooks = demo.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { page ->
                    if (page.id == demo.selectedPageId) page.copy(document = PageDoc(outlines = outlines.toList())) else page
                })
            })
        })
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState) {
        shown = initial
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); shown = state }, fileActions = actions)
            }
        }
        waitForIdle()
    }

    private val outlines: List<Outline> get() = shown.selectedPage!!.document.outlines

    /** Import ▸ File, the chooser answering with [docx], and Insert with every page chosen. */
    private fun ComposeUiTest.importFromTheRibbon() {
        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.File)).performClick()
        onNodeWithTag(FileRibbonTags.Import).performClick()
        onNodeWithTag(FileRibbonTags.ImportFile).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { runCatching { onNodeWithTag(ImportFileTags.page(0)).assertExists() }.isSuccess }
        val before = outlines.size
        onNodeWithTag(ImportFileTags.INSERT).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { outlines.size > before }
        waitUntil(timeoutMillis = WAIT_MS) { runCatching { onNodeWithTag(ImportFileTags.DIALOG).assertDoesNotExist() }.isSuccess }
        waitForIdle()
    }

    private fun ComposeUiTest.boundsOf(box: Outline.Text): Rect = onNodeWithTag(WorkspaceTestTags.textBox(box.id)).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun writtenBox(text: String, minHeight: Float = 80f): Outline.Text =
        Outline.Text.empty(y = PageStyle.TITLE_BAND_DP).copy(minHeight = minHeight, blocks = listOf(Block.of(text)))

    /** What a page already holds stays where it is, and a document's text lands a margin under it, as drawn. */
    @Test
    fun aDocumentLandsUnderWhatThePageAlreadyHoldsInOneStepOfUndo() = runDesktopComposeUiTest(width = 1400, height = 900) {
        DocxTestFile.write(docx, DocxTestFile.paragraph("From the imported document."))
        val existing = writtenBox("A line of text that wraps. ".repeat(40))
        setWorkspace(page(existing))
        val wrapped = boundsOf(existing)
        val scale = wrapped.width / existing.width

        importFromTheRibbon()

        val added = outlines.filterIsInstance<Outline.Text>().single { it.id != existing.id }
        assertEquals("From the imported document.", added.blocks.joinToString("\n") { it.text })
        val bottom = existing.y + wrapped.height / scale
        assertTrue(bottom > existing.y + existing.minHeight, "the existing box should have wrapped past its floor")
        assertEquals(bottom + ImportedPages.INSERT_MARGIN, added.y, 2f, "the import should start a margin under the drawn text")
        assertEquals(listOf(existing), outlines.filter { it.id == existing.id }, "what was there stays where it was")

        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { outlines.map { it.id } == listOf(existing.id) }
    }

    /** Below the window nothing on screen would say the import arrived, so the canvas goes to it. */
    @Test
    fun aLandingBelowTheWindowIsScrolledTo() = runDesktopComposeUiTest(width = 1400, height = 900) {
        DocxTestFile.write(docx, DocxTestFile.paragraph("Far down the page."))
        val tall = writtenBox("A tall box.", minHeight = 3000f)
        setWorkspace(page(tall))

        importFromTheRibbon()

        val added = outlines.filterIsInstance<Outline.Text>().single { it.id != tall.id }
        assertTrue(added.y > 3000f)
        waitUntil(timeoutMillis = WAIT_MS) {
            val landed = boundsOf(added)
            val window = onRoot().getBoundsInRoot()
            landed.top >= 0f && landed.top < window.bottom.value
        }
    }

    /** A blank page has nothing to go and find: the import starts under the title and the page stays put. */
    @Test
    fun aLandingAlreadyInViewLeavesThePageWhereItIs() = runDesktopComposeUiTest(width = 1400, height = 900) {
        DocxTestFile.write(docx, DocxTestFile.paragraph("Onto a blank page."))
        setWorkspace(page(Outline.Text.empty(y = PageStyle.TITLE_BAND_DP)))
        val canvasTop = onNodeWithTag(WorkspaceTestTags.CanvasExtent).getBoundsInRoot().top

        importFromTheRibbon()

        val added = outlines.filterIsInstance<Outline.Text>().single { !it.isEmptyBox() }
        assertEquals(PageStyle.TITLE_BAND_DP, added.y, 0.01f)
        waitForIdle()
        assertEquals(canvasTop, onNodeWithTag(WorkspaceTestTags.CanvasExtent).getBoundsInRoot().top)
    }

    private fun Outline.Text.isEmptyBox(): Boolean = blocks.all { it.text.isBlank() }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
