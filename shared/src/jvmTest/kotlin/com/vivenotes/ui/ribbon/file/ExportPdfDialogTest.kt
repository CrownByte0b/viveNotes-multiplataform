package com.vivenotes.ui.ribbon.file

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.*
import com.vivenotes.pdf.*
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.components.HoverTooltipDelayMillis
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ExportPdfDialogTest {
    private val fitInfo = "Content that would be cut by a page edge is moved back onto the page it starts on. Anything larger than a page is left where it is."
    private val rulingInfo = "Prints the lines, squares or dots the page is written on."
    internal class Exporter : PdfExportService {
        var defaults = PdfExportOptions(paper = PaperSize.A5, orientation = Orientation.Landscape,
            margins = PrintMargins.uniform(.5f))
        val requests = mutableListOf<PdfExportRequest>()
        val previews = mutableListOf<Int>()
        var destination: String? = null
        var writes = 0
        var released = 0
        var failure: Exception? = null
        var planningGate: CompletableDeferred<Unit>? = null
        override suspend fun defaultOptionsFor(pageId: String?) = defaults
        override suspend fun plan(request: PdfExportRequest): PdfExportPlan {
            requests += request
            planningGate?.await()
            failure?.let { throw it }
            val paper = PdfPaper.of(request.options.paper, request.options.orientation, margins = request.options.margins)
            val tiles = (0..2).map { PdfTile(0, it, PdfBounds(0f, it * 1000f, 1000f, (it + 1) * 1000f)) }
            return PdfExportPlan(request.options, paper,
                listOf(PlannedPage("page", "Draft", PdfPagePlan(tiles, emptyMap()))),
                tiles.indices.map { PdfSheetRef(0, it) }, "Draft.pdf")
        }
        override suspend fun preview(plan: PdfExportPlan, sheetIndex: Int, maxWidthPx: Int): ImageBitmap {
            previews += sheetIndex
            return ImageBitmap(20, 30)
        }
        override suspend fun chooseDestination(suggestedName: String) = destination
        override suspend fun write(plan: PdfExportPlan, destination: String, onProgress: (Int, Int) -> Unit): Int {
            failure?.let { throw it }; writes++; return plan.sheetCount
        }
        override suspend fun release() { released++ }
    }

    @Test
    fun androidOptionsReplanThePreviewAndAllSheetsScroll() = runDesktopComposeUiTest(width = 1100, height = 800) {
        val exporter = Exporter()
        setContent { ViveNotesTheme { ExportPdfDialog(exporter, "page", "section", "Draft", "Work", {}) } }
        waitUntil { exporter.requests.isNotEmpty() }
        onNodeWithText("3 sheets").assertExists()
        onNodeWithTag(ToolPaneTags.field("Orientation")).assertTextContains("Landscape")
        onNodeWithTag(ToolPaneTags.field("Size")).assertTextContains("A5")
        onNodeWithTag(ToolPaneTags.field("Range")).performClick()
        onNodeWithTag(ToolPaneTags.option("Range", "This section")).performClick()
        waitUntil { exporter.requests.last().options.scope == PdfExportScope.Section }
        assertEquals("Work", exporter.requests.last().name)
        onNodeWithTag(ToolPaneTags.field("Fit content to pages")).performClick()
        waitUntil { !exporter.requests.last().options.fitContent }
        onNodeWithTag(ToolPaneTags.field("Page ruling")).performClick()
        waitUntil { !exporter.requests.last().options.includeRuling }
        onNodeWithTag(ExportPdfTags.SHEETS).performScrollToIndex(2)
        onNodeWithTag(ExportPdfTags.sheet(2)).assertIsDisplayed()
        waitUntil { 2 in exporter.previews }
    }

    @Test
    fun layoutExplanationsAreHiddenUntilTheirHelpButtonIsClicked() = runDesktopComposeUiTest(width = 1100, height = 800) {
        val exporter = Exporter()
        setContent { ViveNotesTheme { ExportPdfDialog(exporter, "page", "section", "Draft", "Work", {}) } }
        waitUntil { exporter.requests.isNotEmpty() }
        val planned = exporter.requests.size
        onNodeWithText(fitInfo).assertDoesNotExist()
        onNodeWithText(rulingInfo).assertDoesNotExist()
        listOf("Fit content to pages" to fitInfo, "Page ruling" to rulingInfo).forEach { (field, info) ->
            onNodeWithTag(ToolPaneTags.field(field)).assertIsOn()
            onNodeWithTag(ExportPdfTags.help(field)).assertContentDescriptionEquals("About $field").performClick()
            onNodeWithTag(ExportPdfTags.helpText(field)).assertIsDisplayed().assertTextEquals(info)
                .performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
            onNodeWithTag(ExportPdfTags.helpText(field)).assertDoesNotExist()
            onNodeWithTag(ExportPdfTags.DIALOG).assertExists()
            onNodeWithTag(ToolPaneTags.field(field)).assertIsOn()
        }
        runOnIdle { assertEquals(planned, exporter.requests.size, "help does not change export options") }
    }

    @Test
    fun hoveringEachLayoutHelpButtonShowsItsExplanationAndLeavingHidesIt() = runDesktopComposeUiTest(width = 1100, height = 800) {
        val exporter = Exporter()
        setContent { ViveNotesTheme { ExportPdfDialog(exporter, "page", "section", "Draft", "Work", {}) } }
        waitUntil { exporter.requests.isNotEmpty() }
        mainClock.autoAdvance = false
        listOf("Fit content to pages" to fitInfo, "Page ruling" to rulingInfo).forEach { (field, info) ->
            val help = onNodeWithTag(ExportPdfTags.help(field))
            help.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(HoverTooltipDelayMillis + 32)
            onNodeWithText(info).assertIsDisplayed()
            help.performMouseInput { exit() }
            mainClock.advanceTimeBy(32)
            onNodeWithText(info).assertDoesNotExist()
            onNodeWithTag(ToolPaneTags.field(field)).assertIsOn()
        }
    }

    @Test
    fun pickerCancellationKeepsPreviewAndSuccessfulExportClosesAndReleases() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val exporter = Exporter()
            var open by mutableStateOf(true)
            setContent { ViveNotesTheme { if (open) ExportPdfDialog(exporter, "page", "section", "Draft", "Work", { open = false }) } }
            waitUntil { exporter.requests.isNotEmpty() }
            onNodeWithTag(ExportPdfTags.EXPORT).assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(0, exporter.writes)
            onNodeWithTag(ExportPdfTags.DIALOG).assertExists()
            runOnIdle { exporter.destination = "/chosen.pdf" }
            onNodeWithTag(ExportPdfTags.EXPORT).performClick()
            waitUntil { !open && exporter.released > 0 }
            assertEquals(1, exporter.writes)
        }

    @Test
    fun shortWindowStacksScrollableOptionsAndShowsWriteAndSaveErrors() =
        runDesktopComposeUiTest(width = 650, height = 620) {
            val exporter = Exporter()
            setContent { ViveNotesTheme { ExportPdfDialog(exporter, "page", null, "Draft", "", {}) } }
            waitUntil { exporter.requests.isNotEmpty() }
            onNodeWithTag(ToolPaneTags.field("Range")).performClick()
            onNodeWithTag(ToolPaneTags.option("Range", "This section")).assertDoesNotExist()
            onNodeWithTag(ToolPaneTags.option("Range", "This page")).performKeyInput {
                keyDown(Key.Escape); keyUp(Key.Escape)
            }
            onNodeWithTag(ToolPaneTags.option("Range", "This page")).assertDoesNotExist()
            runOnIdle { exporter.destination = "/chosen.pdf"; exporter.failure = IllegalStateException("Disk full") }
            onNodeWithTag(ExportPdfTags.EXPORT).performClick()
            onNodeWithTag(ExportPdfTags.MESSAGE).assertTextContains("Disk full")
            onNodeWithTag(ExportPdfTags.EXPORT).assertIsEnabled()
        }

    @Test
    fun failedPreparationNeverPlansStaleStorageAndEscapeCloses() = runDesktopComposeUiTest {
        val exporter = Exporter()
        var closed = false
        setContent { ViveNotesTheme { ExportPdfDialog(exporter, "page", "section", "Draft", "Work", { closed = true },
            prepare = { throw PdfExportException("Changes could not be saved") }) } }
        onNodeWithTag(ExportPdfTags.MESSAGE).assertTextContains("Changes could not be saved")
        onNodeWithTag(ExportPdfTags.EXPORT).assertIsNotEnabled()
        assertTrue(exporter.requests.isEmpty())
        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        assertTrue(closed)
    }
}
