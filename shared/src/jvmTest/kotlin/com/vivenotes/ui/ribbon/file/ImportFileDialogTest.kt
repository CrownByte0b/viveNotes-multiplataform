// Ported from viveNotes Android androidTest ui/editor/ImportFileDialogTest.kt: the same cases, against
// the real importer and documents written by the test.
package com.vivenotes.ui.ribbon.file

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.fileimport.DocxTestFile
import com.vivenotes.fileimport.FileImportMode
import com.vivenotes.fileimport.FileImporter
import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.ImportedFile
import com.vivenotes.ui.theme.ViveNotesTheme
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ImportFileDialogTest {
    private val root: File = Files.createTempDirectory("import-dialog").toFile()
    private val importer = FileImporter(File(root, "cache"))
    private val file = File(root, "import-dialog-test.docx")

    private var insertedPages: List<Int>? = null
    private var insertedMode: FileImportMode? = null
    private var dismissed = false

    /** A document of [pages] pages, each cut off from the next by a hard page break. */
    private fun writeDocument(pages: Int) {
        val body = List(pages) { index ->
            val breakBefore = if (index == 0) "" else "<w:pageBreakBefore/>"
            DocxTestFile.paragraph("Page body $index", breakBefore)
        }.joinToString("")
        DocxTestFile.write(file, body)
    }

    private fun ComposeUiTest.setDialog(
        open: suspend () -> ImportedFile = { importer.open(file.path) },
        landed: Boolean = true,
    ) {
        setContent {
            ViveNotesTheme {
                ImportFileDialog(
                    importer = importer,
                    open = open,
                    onInsert = { _, pages, mode ->
                        insertedPages = pages
                        insertedMode = mode
                        landed
                    },
                    onDismiss = { dismissed = true },
                )
            }
        }
        waitUntil(timeoutMillis = WAIT_MS) {
            runCatching { onNodeWithTag(ImportFileTags.page(0)).assertExists() }.isSuccess ||
                runCatching { onNodeWithTag(ImportFileTags.MESSAGE).assertExists() }.isSuccess
        }
    }

    @AfterTest
    fun tearDown() {
        importer.release()
        root.deleteRecursively()
    }

    @Test
    fun everyPageStartsChosen() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 3)
        setDialog()

        (0 until 3).forEach { onNodeWithTag(ImportFileTags.page(it)).assertIsOn() }
        onNodeWithTag(ImportFileTags.INSERT).assertIsEnabled()
        onNodeWithText("import-dialog-test.docx · 3 of 3 pages selected").assertExists()
    }

    @Test
    fun clickingAPageLeavesItOutOfTheInsert() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 3)
        setDialog()

        onNodeWithTag(ImportFileTags.page(1)).performClick()
        onNodeWithTag(ImportFileTags.page(1)).assertIsOff()
        onNodeWithTag(ImportFileTags.INSERT).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { insertedPages != null }

        assertEquals(listOf(0, 2), insertedPages)
    }

    @Test
    fun objectsAreTheDefault() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 1)
        setDialog()

        onNodeWithTag(ImportFileTags.mode(FileImportMode.Objects)).assertIsSelected()
        onNodeWithTag(ImportFileTags.INSERT).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { insertedMode != null }

        assertEquals(FileImportMode.Objects, insertedMode)
    }

    @Test
    fun picturesCanBeChosen() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 1)
        setDialog()

        onNodeWithText("Picture of each page").performClick()
        onNodeWithTag(ImportFileTags.mode(FileImportMode.Pictures)).assertIsSelected()
        onNodeWithTag(ImportFileTags.INSERT).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { insertedMode != null }

        assertEquals(FileImportMode.Pictures, insertedMode)
    }

    @Test
    fun clearingEverythingLeavesNothingToInsertUntilSelectAll() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 2)
        setDialog()

        onNodeWithTag(ImportFileTags.SELECT_NONE).performClick()
        onNodeWithTag(ImportFileTags.page(0)).assertIsOff()
        onNodeWithTag(ImportFileTags.INSERT).assertIsNotEnabled()

        onNodeWithTag(ImportFileTags.SELECT_ALL).performClick()
        onNodeWithTag(ImportFileTags.page(1)).assertIsOn()
        onNodeWithTag(ImportFileTags.INSERT).assertIsEnabled()
    }

    @Test
    fun aSuccessfulInsertClosesTheWindow() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 1)
        setDialog()

        onNodeWithTag(ImportFileTags.INSERT).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { dismissed }

        assertTrue(dismissed)
    }

    @Test
    fun anInsertThatDoesNotLandSaysSoAndStaysOpen() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 1)
        setDialog(landed = false)

        onNodeWithTag(ImportFileTags.INSERT).performClick()
        waitUntil(timeoutMillis = WAIT_MS) { insertedPages != null }
        waitForIdle()

        onNodeWithTag(ImportFileTags.MESSAGE).assertIsDisplayed()
        onNodeWithText("The pages could not be placed on this page.").assertExists()
        assertFalse(dismissed)
    }

    /** Each failure has its own sentence, and a file that cannot be read offers nothing to insert. */
    @Test
    fun aFileThatWillNotOpenSaysWhy() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setDialog(open = { throw ImportException(ImportFailure.TooLarge) })

        onNodeWithText("This file is too large to import.").assertIsDisplayed()
        onNodeWithTag(ImportFileTags.INSERT).assertIsNotEnabled()
    }

    @Test
    fun aPdfWithAPasswordSaysThatRatherThanThatItIsBroken() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setDialog(open = { throw ImportException(ImportFailure.PasswordProtected) })

        onNodeWithText("This file is password-protected.").assertIsDisplayed()
        onNodeWithTag(ImportFileTags.INSERT).assertIsNotEnabled()
    }

    /** A file the importer does not read at all, through the real importer: the sentence names what it reads. */
    @Test
    fun aFileThatIsNoDocumentNamesWhatCanBeImported() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        file.writeText("not a document")
        setDialog()

        onNodeWithText(
            "Only Word documents (.doc, .docx), OpenDocument text (.odt), PowerPoint presentations (.pptx) and PDFs can be imported.",
        ).assertIsDisplayed()
    }

    // --- desktop -----------------------------------------------------------------------------------

    /** The window has the keyboard as soon as it opens, and Escape closes it, as Export PDF's does. */
    @Test
    fun escapeClosesTheWindow() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 1)
        setDialog()

        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }

        waitUntil(timeoutMillis = WAIT_MS) { dismissed }
    }

    @Test
    fun closeClosesTheWindow() = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        writeDocument(pages = 1)
        setDialog()

        onNodeWithTag(ImportFileTags.CLOSE).performClick()

        waitUntil(timeoutMillis = WAIT_MS) { dismissed }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val WIDTH = 1200
        const val HEIGHT = 800
    }
}
