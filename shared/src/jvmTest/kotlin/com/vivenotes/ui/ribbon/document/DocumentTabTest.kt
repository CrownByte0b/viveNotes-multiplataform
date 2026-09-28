package com.vivenotes.ui.ribbon.document

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.Run
import com.vivenotes.model.RuleLines
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.focusBody
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Document tab's list, indent, link and picture commands, driven through the real editor. */
@OptIn(ExperimentalTestApi::class)
class DocumentTabTest {

    private val document = WorkspaceState.demo().copy(activeTab = RibbonTab.Document).focusBody()
    private val accent = Color(0xFF4CAF50)

    @Test
    fun bulletedListDrawsItsMarkerInTheParagraphMargin() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = document
        setWorkspace(document) { observed = it }
        assertFalse(editorImage().hasColorIn(accent, xs = 0 until 24), "a plain paragraph has no marker")

        onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Bullet)).performScrollTo().performClick()

        runOnIdle { assertEquals(BlockType.Bullet, observed.richText!!.currentBlock.type) }
        assertTrue(editorImage().hasColorIn(accent, xs = 0 until 24), "the bullet was not drawn")
        assertTrue(firstInkColumn(editorImage()) >= 20, "the item's text must start after its marker")

        onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Bullet)).performClick()
        runOnIdle { assertEquals(BlockType.Paragraph, observed.richText!!.currentBlock.type) }
        assertFalse(editorImage().hasColorIn(accent, xs = 0 until 24), "choosing Bulleted list again ends the list")
    }

    @Test
    fun numberedListDrawsItsOrdinalAndEnterContinuesTheList() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = document
        setWorkspace(document) { observed = it }

        onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Numbered)).performScrollTo().performClick()
        val image = editorImage()
        assertTrue(image.hasInkIn(xs = 4 until 20), "the ordinal was not drawn in the margin")
        assertFalse(image.hasColorIn(accent, xs = 0 until 24), "a numbered item has no bullet")

        val text = observed.richText!!.text
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(text.indexOf('\n')))
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            val blocks = observed.richText!!.blocks
            assertEquals(BlockType.Numbered, blocks[1].type, "Enter in a list makes another item")
            assertTrue(blocks[1].runs.isEmpty())
        }
    }

    @Test
    fun indentButtonsAndTabMoveTheParagraph() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = document
        setWorkspace(document) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick().performTextInputSelection(TextRange(3))
        val before = firstInkColumn(editorImage())

        onNodeWithTag(DocumentRibbonTags.IncreaseIndent).performScrollTo().performClick()
        runOnIdle { assertEquals(1, observed.richText!!.currentBlock.indent) }
        val after = firstInkColumn(editorImage())
        assertTrue(after - before >= 20, "an indent step moves the text right: $before -> $after")

        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
            .performKeyInput { pressKey(Key.Tab) }
        runOnIdle { assertEquals(2, observed.richText!!.currentBlock.indent) }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        onNodeWithTag(DocumentRibbonTags.DecreaseIndent).performClick()
        runOnIdle {
            assertEquals(0, observed.richText!!.currentBlock.indent)
            assertFalse('\t' in observed.richText!!.text, "Tab indents rather than typing a tab")
        }
    }

    @Test
    fun clickingAToDoBoxTicksItAndClickingTheTextDoesNot() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = document
        setWorkspace(document) { observed = it }
        onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Todo)).performScrollTo().performClick()
        val text = observed.richText!!.text

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { click(Offset(120f, 10f)) }
        runOnIdle { assertEquals(false, observed.richText!!.blocks.first().checked) }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { click(Offset(11f, 10f)) }
        runOnIdle {
            assertEquals(true, observed.richText!!.blocks.first().checked)
            assertEquals(text, observed.richText!!.text)
        }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { click(Offset(11f, 10f)) }
        runOnIdle { assertEquals(false, observed.richText!!.blocks.first().checked) }
    }

    @Test
    fun linkPanelLinksTheSelectionMadeInTheEditor() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = document
        setWorkspace(document) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.Link).performScrollTo().performClick()
        onNodeWithTag(DocumentRibbonTags.LinkPanel).assertIsDisplayed()
        onNodeWithText("Insert link").assertExists()
        onNodeWithTag(DocumentRibbonTags.LinkText).assertTextContains("Review")
        onNodeWithTag(DocumentRibbonTags.LinkAddress).assertIsFocused()
        onNodeWithTag(DocumentRibbonTags.LinkSubmit).assertIsNotEnabled()
        onNodeWithTag(DocumentRibbonTags.LinkAddress).performTextReplacement("javascript:alert(1)")
        onNodeWithTag(DocumentRibbonTags.LinkSubmit).assertIsNotEnabled()
        onNodeWithTag(DocumentRibbonTags.LinkAddress).performTextReplacement("example.com/notes")
        onNodeWithTag(DocumentRibbonTags.LinkSubmit).assertIsEnabled().performClick()

        onNodeWithTag(DocumentRibbonTags.LinkPanel).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
        runOnIdle {
            assertEquals(Run("Review", setOf(Mark.Link("https://example.com/notes"))),
                observed.richText!!.blocks.first().runs.first())
        }
    }

    @Test
    fun linkPanelFieldsAcceptClicksAndTyping() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = document
        setWorkspace(document) { observed = it }
        onNodeWithTag(DocumentRibbonTags.Link).performScrollTo().performClick()
        val backdrop = onNodeWithTag("document-link-backdrop").fetchSemanticsNode().boundsInRoot
        assertTrue(backdrop.width >= 1399f && backdrop.height >= 899f,
            "The link form must share the workspace window: $backdrop")

        onNodeWithTag(DocumentRibbonTags.LinkText).performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.LinkText).assertIsFocused().performTextInput("Vive")
        onNodeWithTag(DocumentRibbonTags.LinkAddress).performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.LinkAddress).assertIsFocused().performTextInput("example.com")
        onNodeWithTag(DocumentRibbonTags.LinkSubmit).performClick()

        runOnIdle {
            assertTrue(observed.richText!!.blocks.first().runs.any {
                it.text == "Vive" && Mark.Link("https://example.com") in it.marks
            })
        }
    }

    @Test
    fun linkPanelEditsTheLinkAtTheCaretAndCancelChangesNothing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val linked = document.selectText(TextSelection(0, 6)).insertLink("Review", "https://example.com")
            .selectText(TextSelection(3))
        var observed = linked
        setWorkspace(linked) { observed = it }

        onNodeWithTag(DocumentRibbonTags.Link).performScrollTo().performClick()
        onNodeWithText("Edit link").assertExists()
        onNodeWithTag(DocumentRibbonTags.LinkAddress).assertTextContains("https://example.com")
        onNodeWithTag(DocumentRibbonTags.LinkCancel).performClick()
        runOnIdle { assertEquals(linked.selectedPage!!.document, observed.selectedPage!!.document) }

        onNodeWithTag(DocumentRibbonTags.Link).performClick()
        onNodeWithTag(DocumentRibbonTags.LinkText).performTextReplacement("Read this")
        onNodeWithTag(DocumentRibbonTags.LinkAddress).performTextReplacement("example.org")
        onNodeWithTag(DocumentRibbonTags.LinkAddress).performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertTrue(observed.selectedPage!!.body.startsWith("Read this limits"))
            assertEquals(Run("Read this", setOf(Mark.Link("https://example.org"))),
                observed.richText!!.blocks.first().runs.first())
        }
    }

    @Test
    fun ctrlClickOpensALinkAndAPlainClickEditsIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val opened = mutableListOf<String>()
        val linked = document.selectText(TextSelection(0, 6)).insertLink("Review", "https://example.com")
        var observed = linked
        setWorkspace(linked, uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }) { observed = it }

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { click(Offset(20f, 10f)) }
        runOnIdle { assertEquals(emptyList(), opened) }
        onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { click(Offset(20f, 10f)) }
        onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        runOnIdle { assertEquals(listOf("https://example.com"), opened) }
        onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { click(Offset(200f, 10f)) }
        onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        runOnIdle { assertEquals(1, opened.size, "only a linked character opens anything") }
    }

    @Test
    fun pictureButtonInsertsTheChosenPictureAndDrawsIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val id = "c".repeat(64)
        val library = FakePictures(ImportedPicture(id, 40, 20), mapOf(id to redPng(40, 20)))
        // A bright, unruled page, so the paper and a plate behind a picture cannot be confused.
        val paper = 0xFFFFF3C4.toInt()
        val initial = document.updatePage(document.selectedPageId) { page ->
            page.copy(document = page.document.copy(style = page.document.style.copy(
                backgroundArgb = paper, ruleLines = RuleLines.None)))
        }
        var observed = initial
        setWorkspace(initial, pictures = library) { observed = it }

        onNodeWithTag(DocumentRibbonTags.Picture).performScrollTo().assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 5_000) { observed.selectedPage!!.document.outlines.any { it is Outline.Image } }
        val image = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().single()
        assertEquals(id, image.attachmentId)
        assertEquals(Outline.Image.DEFAULT_WIDTH to Outline.Image.DEFAULT_WIDTH / 2, image.width to image.height)

        val frame = onNodeWithTag(WorkspaceTestTags.primeObject(image.id))
        waitUntil(timeoutMillis = 5_000) {
            val pixels = frame.captureToImage().toPixelMap()
            pixels[pixels.width / 4, pixels.height / 2].isCloseTo(Color.Red)
        }
        // The transparent half shows the page under it, as on Android, not a plate behind the picture.
        val pixels = frame.captureToImage().toPixelMap()
        val through = pixels[pixels.width * 3 / 4, pixels.height / 2]
        assertTrue(through.isCloseTo(Color(paper)), "expected the paper through the picture, got $through")
        assertEquals(1, library.chosen)
    }

    @Test
    fun aPictureWhoseFileIsMissingSaysSo() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val id = "d".repeat(64)
        val library = FakePictures(ImportedPicture(id, 10, 10), emptyMap())
        val withPicture = document.insertPicture(document.selectedPageId, ImportedPicture(id, 10, 10), 0f, 0f)
        setWorkspace(withPicture, pictures = library)

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Error: dddddddddddd… not found").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun cancellingThePictureChoiceInsertsNothing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val library = FakePictures(null, emptyMap())
        var observed = document
        setWorkspace(document, pictures = library) { observed = it }

        onNodeWithTag(DocumentRibbonTags.Picture).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { library.chosen == 1 }
        runOnIdle { assertEquals(document.selectedPage!!.document, observed.selectedPage!!.document) }
    }

    private class FakePictures(private val next: ImportedPicture?, private val files: Map<String, ByteArray>) : PictureLibrary {
        var chosen = 0
        override suspend fun choose(): ImportedPicture? = next.also { chosen++ }
        override suspend fun bytes(attachmentId: String): ByteArray? = files[attachmentId]
    }

    /** Red on the left half; the right half is transparent. */
    private fun redPng(width: Int, height: Int): ByteArray = Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.clear(org.jetbrains.skia.Color.TRANSPARENT)
        surface.canvas.drawRect(Rect.makeWH(width / 2f, height.toFloat()),
            Paint().apply { color = org.jetbrains.skia.Color.RED })
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun ComposeUiTest.editorImage(): PixelMap {
        waitForIdle()
        return onNodeWithTag(WorkspaceTestTags.BodyEditor).captureToImage().toPixelMap()
    }

    /** The first column, in the first line of text, holding light ink on the dark test page. */
    private fun firstInkColumn(image: PixelMap): Int =
        (0 until image.width).first { x -> (2 until 20).any { y -> image[x, y].luminance() > 0.5f } }

    private fun PixelMap.hasInkIn(xs: IntRange): Boolean =
        xs.any { x -> (0 until minOf(24, height)).any { y -> this[x, y].luminance() > 0.5f } }

    private fun PixelMap.hasColorIn(color: Color, xs: IntRange): Boolean =
        xs.any { x -> (0 until minOf(24, height)).any { y -> this[x, y].isCloseTo(color) } }

    private fun Color.isCloseTo(other: Color): Boolean =
        abs(red - other.red) < 0.08f && abs(green - other.green) < 0.08f && abs(blue - other.blue) < 0.08f

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState,
        pictures: PictureLibrary? = null,
        uriHandler: UriHandler? = null,
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                val screen = @Composable {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                        pictures = pictures)
                }
                if (uriHandler == null) screen() else CompositionLocalProvider(LocalUriHandler provides uriHandler) { screen() }
            }
        }
    }
}
