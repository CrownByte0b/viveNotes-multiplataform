package com.vivenotes.ui.ribbon.view

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's `PaperSizePanelTest`. The pane is a form, and a form's job is to commit exactly what
 * was typed and nothing else: "" and "99" are states a number field passes through, and neither is
 * a page.
 */
@OptIn(ExperimentalTestApi::class)
class PaperSizePaneTest {

    private var size: PaperSize? = null
    private var orientation: Orientation? = null
    private var custom: PaperDimensions? = null
    private var margins: PrintMargins? = null
    private var closed = false

    private fun ComposeUiTest.setPane(style: PageStyle = PageStyle(), enabled: Boolean = true) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                PaperSizePane(
                    style = style,
                    enabled = enabled,
                    actions = ViewActions(
                        setRuleLines = {}, setPageColor = {}, setHideTitle = {},
                        setPaperSize = { size = it },
                        setOrientation = { orientation = it },
                        setCustomPaper = { custom = it },
                        setMargins = { margins = it },
                        setZoom = {}, zoomIn = {}, zoomOut = {}, zoomToPageWidth = {},
                        setTabsLayout = {}, setCanvasDark = {}, togglePaperSizePane = {},
                    ),
                    onClose = { closed = true },
                )
            }
        }
    }

    private fun ComposeUiTest.field(name: String) = onNodeWithTag(ToolPaneTags.field(name))

    private fun ComposeUiTest.type(name: String, text: String) {
        field(name).performTextClearance()
        field(name).performTextInput(text)
    }

    @Test
    fun theSizeFieldOffersEverySheetIncludingCustomAndInfinite() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane()
        field("Size").performClick()
        PaperSize.entries.forEach { onNodeWithTag(ToolPaneTags.option("Size", paperSizeLabel(it))).assertExists() }
        onNodeWithTag(ToolPaneTags.option("Size", "A4")).performClick()
        assertEquals(PaperSize.A4, size)
    }

    @Test
    fun orientationTurnsTheSheetWithoutResizingIt() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.A4))
        field("Orientation").performClick()
        onNodeWithText("Landscape").performClick()
        assertEquals(Orientation.Landscape, orientation)
        assertNull(size, "turning the page must not resize it")
    }

    /** An infinite page has no orientation to turn — the canvas grows whichever way you write. */
    @Test
    fun anInfinitePageCannotBeTurned() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.Auto))
        field("Orientation").assertIsNotEnabled()
        field("Orientation").performClick()
        onNodeWithText("Landscape").assertDoesNotExist()
        assertNull(orientation)
    }

    /** A named size still shows its dimensions: "B5" means nothing without them. */
    @Test
    fun aNamedSizeShowsItsDimensionsWithoutOfferingToEditThem() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.A4))
        onNodeWithText("8.27").assertIsDisplayed()
        onNodeWithText("11.69").assertIsDisplayed()
        field("Width").assertIsNotEnabled()
        field("Height").assertIsNotEnabled()
    }

    @Test
    fun aCustomWidthIsCommittedAsTyped() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.Custom, customPaper = PaperDimensions(8.5f, 11f)))
        field("Width").assertIsEnabled()
        type("Width", "6")
        assertEquals(PaperDimensions(6f, 11f), custom)
    }

    @Test
    fun anEmptyFieldIsNotAPageSize() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.Custom, customPaper = PaperDimensions(8.5f, 11f)))
        field("Width").performTextClearance()
        assertNull(custom, "an empty field was committed as a width")
    }

    @Test
    fun aSizeOutsideWhatAPageCanBeIsRefused() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.Custom, customPaper = PaperDimensions(8.5f, 11f)))
        type("Width", "500")
        assertNull(custom, "a 500 inch page was accepted")
    }

    @Test
    fun eachMarginCommitsToItsOwnEdge() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.A4))
        type("Top", "0.5")
        assertEquals(PrintMargins(topInches = 0.5f), margins)
        type("Right", "0.25")
        assertEquals(PrintMargins(rightInches = 0.25f), margins)
    }

    @Test
    fun aMarginWiderThanTheSheetIsRefused() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(PageStyle(paper = PaperSize.A4))
        type("Left", "99")
        assertNull(margins, "a margin wider than the sheet was accepted")
    }

    @Test
    fun withNoPageOpenNothingCanBeChanged() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane(enabled = false)
        listOf("Size", "Orientation", "Width", "Height", "Top", "Bottom", "Left", "Right")
            .forEach { field(it).assertIsNotEnabled() }
    }

    @Test
    fun thePaneClosesFromItsOwnButton() = runDesktopComposeUiTest(width = 600, height = 900) {
        setPane()
        onNodeWithTag(ToolPaneTags.Close).performClick()
        assertTrue(closed)
    }
}
