package com.vivenotes.ui.canvas

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.ink.*
import com.vivenotes.model.*
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.ui.components.LocalPopupLayerDensity
import com.vivenotes.ui.ribbon.document.PictureObjectTags
import com.vivenotes.ui.ribbon.draw.ShapeObjectTags
import com.vivenotes.ui.ribbon.view.ViewRibbonTags
import com.vivenotes.ui.shell.*
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class SelectionColorPickerTest {
    private val shape = Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
        segments = seedSegments(ShapeKind.Rectangle, 180f, 350f, 340f, 450f) { newId() }).withRecomputedBounds()
    private val table = newTable(1, 1, x = 380f, y = 350f, inkOnly = true)
    private val equation = Outline.Equation("equation", 700f, 350f, 80f, 40f, "x^2")

    private fun workspace(): WorkspaceState {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.View)
        val ink = InkPageReader.read(base.selectedPageId, listOf(selectionLine(pageId = base.selectedPageId)), emptyList(), emptyList())
        return base.updatePage(base.selectedPageId) {
            it.copy(document = PageDoc(style = PageStyle(hideTitle = true, ruleLines = RuleLines.None),
                outlines = listOf(shape, table, equation)), ink = ink)
        }
    }

    @Test
    fun everyObjectUsesTheSameUpwardPaletteCustomFieldAndCompactToolbar() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val base = workspace()
        val state = mutableStateOf(base.selectObject(shape.id))
        setContent { ViveNotesTheme(darkTheme = false) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        val selections = listOf(base.selectObject(shape.id), base.selectObject(table.id), base.selectObject(equation.id),
            base.selectInkAt(40f, 200f))
        for ((index, selection) in selections.withIndex()) {
            runOnIdle { state.value = selection }
            onNodeWithTag(WorkspaceTestTags.ObjectToolkit).assertHeightIsEqualTo(36.dp)
            val anchor = onNodeWithTag(WorkspaceTestTags.ObjectColor).assertHeightIsEqualTo(32.dp)
            anchor.performMouseInput { click(center) }
            val menu = onNodeWithTag(SelectionColorTags.Menu).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue(menu.bottom <= anchor.fetchSemanticsNode().boundsInWindow.top, "palette must stay above object $index")
            if (index == 0) {
                onNodeWithTag(WorkspaceTestTags.ObjectToolkit).saveImage("selection-toolkit-compact.png")
                onNodeWithTag(SelectionColorTags.Menu).saveImage("selection-color-palette.png")
            }
            val white = onNodeWithContentDescription("White").fetchSemanticsNode().boundsInRoot
            val black = onNodeWithContentDescription("Black").fetchSemanticsNode().boundsInRoot
            assertTrue(white.left < black.left && white.top == black.top)
            onNodeWithTag(SelectionColorTags.Custom).assertDoesNotExist()
            onNodeWithTag(SelectionColorTags.Rainbow).performMouseInput { click(center) }
            onNodeWithTag(SelectionColorTags.Custom).performScrollTo().performMouseInput { click(center) }
            onNodeWithTag(SelectionColorTags.Custom).typeCharacters("#345678")
            onNodeWithTag("${SelectionColorTags.Custom}-apply").performMouseInput { click(center) }
            waitUntil(timeoutMillis = 5000) { state.value.structuralUndo.size == 1 }
            onNodeWithTag(SelectionColorTags.Menu).assertDoesNotExist()
            runOnIdle {
                val page = state.value.selectedPage!!
                when (index) {
                    0 -> assertEquals(0xff345678.toInt(), (page.document.outlines.first() as Outline.Shape).borderArgb)
                    1 -> assertEquals(0xff345678.toInt(), page.document.outlines.filterIsInstance<Outline.Table>().single().borderArgb)
                    2 -> assertEquals(0xff345678.toInt(), page.document.outlines.filterIsInstance<Outline.Equation>().single().colorArgb)
                    3 -> assertEquals(0xff345678.toInt(), page.ink!!.strokes.single().colorArgb)
                }
            }
        }
    }

    @Test
    fun oneChoiceAppliesAutomaticColorToAMixedSelectionAndFollowsBackgroundSwitches() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val base = workspace()
        val state = mutableStateOf(base.selectObjectsInRect(0f, 180f, 900f, 600f))
        setContent { ViveNotesTheme(darkTheme = false) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(WorkspaceTestTags.ObjectColor).performClick()
        onNodeWithContentDescription("Black").performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { state.value.structuralUndo.size == 1 }
        val colored = state.value.selectedPage!!.document
        runOnIdle {
            assertTrue(state.value.selectedPage!!.ink!!.strokes.single().colorFollowsTheme == true)
            assertTrue((colored.outlines.first() as Outline.Shape).borderFollowsTheme == true)
            assertTrue(colored.outlines.filterIsInstance<Outline.Table>().single().borderFollowsTheme == true)
            assertNull(colored.outlines.filterIsInstance<Outline.Equation>().single().colorArgb)
        }
        onNodeWithTag(ViewRibbonTags.SwitchBackground).performClick()
        val swatch = onNodeWithTag(WorkspaceTestTags.ObjectColor).captureToImage().toPixelMap()
        assertEquals(Color(0xffe6e6e6), swatch[swatch.width / 2, swatch.height / 2])
        runOnIdle { assertEquals(colored, state.value.selectedPage!!.document); assertEquals(1, state.value.structuralUndo.size) }
        onNodeWithTag(WorkspaceTestTags.ObjectColor).performClick()
        onNodeWithContentDescription("White").performClick()
        waitUntil(timeoutMillis = 5000) { state.value.structuralUndo.size == 2 }
        onNodeWithTag(WorkspaceTestTags.ObjectColor).performClick()
        onNodeWithContentDescription("Black").performClick()
        waitUntil(timeoutMillis = 5000) { state.value.structuralUndo.size == 3 }
        runOnIdle {
            assertFalse(state.value.selectedPage!!.ink!!.strokes.single().colorFollowsTheme!!)
            assertFalse((state.value.selectedPage!!.document.outlines.first() as Outline.Shape).borderFollowsTheme!!)
            state.value = state.value.undoStructure()
            assertTrue(state.value.selectedPage!!.ink!!.strokes.single().colorFollowsTheme!!)
            assertTrue((state.value.selectedPage!!.document.outlines.first() as Outline.Shape).borderFollowsTheme!!)
            state.value = state.value.redoStructure()
            assertFalse(state.value.selectedPage!!.ink!!.strokes.single().colorFollowsTheme!!)
        }
    }

    @Test
    fun paletteKeepsItsUpwardPlacementAtScaledUiDensitiesAndCanvasZoom() {
        for ((density, zoom) in listOf(.75f to .5f, 1.5f to 1.25f)) runDesktopComposeUiTest(width = 1800, height = 1200) {
            val state = mutableStateOf(workspace().selectObject(shape.id))
            setContent { CompositionLocalProvider(LocalDensity provides Density(density), LocalPopupLayerDensity provides Density(1f)) {
                ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, viewSettings = ViewSettings(zoom = zoom)) }
            } }
            val anchor = onNodeWithTag(WorkspaceTestTags.ObjectColor)
            anchor.performMouseInput { click(center) }
            val menu = onNodeWithTag(SelectionColorTags.Menu).fetchSemanticsNode().boundsInWindow
            assertTrue(menu.bottom <= anchor.fetchSemanticsNode().boundsInWindow.top)
            onNodeWithContentDescription("Red").performMouseInput { click(center) }
            runOnIdle { assertEquals(0xffe53935.toInt(), (state.value.selectedPage!!.document.outlines.first() as Outline.Shape).borderArgb) }
            anchor.performClick()
            onNodeWithTag(SelectionColorTags.Menu).performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
            onNodeWithContentDescription("White").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(SelectionColorTags.Menu).assertDoesNotExist()
        }
    }

    @Test
    fun shapeFillUsesTheSharedPaletteAndRetainsNoFillAndExplicitWhite() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace().selectObject(shape.id))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(ShapeObjectTags.Fill).performClick()
        onNodeWithTag(SelectionColorTags.Custom).assertDoesNotExist()
        onNodeWithTag(SelectionColorTags.Rainbow).assertExists()
        onNodeWithContentDescription("White").performClick()
        runOnIdle { assertEquals(-1, (state.value.selectedPage!!.document.outlines.first() as Outline.Shape).fillArgb) }
        onNodeWithTag(ShapeObjectTags.Fill).performClick()
        onNodeWithText("No fill").performClick()
        runOnIdle { assertNull((state.value.selectedPage!!.document.outlines.first() as Outline.Shape).fillArgb) }
    }

    /** Android `ShapeToolTest.aPictureBackgroundIsTheSameControlInItsOwnWords`, on the shared palette. */
    @Test
    fun aPictureBackgroundIsTheSameControlInItsOwnWords() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val picture = Outline.Image(id = "picture", x = 180f, y = 520f, width = 120f, height = 80f, attachmentId = "fixture")
        val second = Outline.Image(id = "second", x = 380f, y = 520f, width = 120f, height = 80f, attachmentId = "fixture")
        val base = workspace().let { state -> state.updatePage(state.selectedPageId) {
            it.copy(document = it.document.copy(outlines = it.document.outlines + listOf(picture, second)))
        } }
        val state = mutableStateOf(base.selectObject(picture.id))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        fun background(id: String) = (state.value.selectedPage!!.document.outlines.first { it.id == id } as Outline.Image).backgroundArgb
        val blue = 0xff2962ff.toInt()

        // A picture has no colour of its own to change: no base swatch, a background control instead.
        onNodeWithTag(WorkspaceTestTags.ObjectColor).assertDoesNotExist()
        onNodeWithContentDescription("Change background color").assertHeightIsEqualTo(32.dp).performClick()
        val menu = onNodeWithTag(SelectionColorTags.Menu).fetchSemanticsNode().boundsInWindow
        assertTrue(menu.bottom <= onNodeWithTag(PictureObjectTags.Background).fetchSemanticsNode().boundsInWindow.top)
        onNodeWithContentDescription("Blue").performClick()
        runOnIdle { assertEquals(blue, background(picture.id)); assertNull(background(second.id)) }

        onNodeWithTag(PictureObjectTags.Background).performClick()
        onNodeWithTag(SelectionColorTags.color(blue)).assertIsSelected()
        onNodeWithTag(SelectionColorTags.NoFill).assertTextEquals("No background")
        onNodeWithTag(WorkspaceTestTags.ObjectToolkit).saveImage("picture-background-toolkit.png")
        onNodeWithTag(SelectionColorTags.Menu).saveImage("picture-background-palette.png")
        onNodeWithText("No background").performClick()
        runOnIdle { assertNull(background(picture.id)) }

        // Several pictures share the one control; held with anything else there is none.
        runOnIdle { state.value = base.selectObjectsInRect(170f, 510f, 510f, 610f) }
        onNodeWithTag(PictureObjectTags.Background).performClick()
        onNodeWithContentDescription("Red").performClick()
        runOnIdle { assertEquals(0xffe53935.toInt(), background(picture.id)); assertEquals(0xffe53935.toInt(), background(second.id)) }
        runOnIdle { state.value = base.selectObjectsInRect(170f, 340f, 510f, 610f) }
        onNodeWithTag(WorkspaceTestTags.ObjectColor).assertExists()
        onNodeWithTag(PictureObjectTags.Background).assertDoesNotExist()
    }

    @Test
    fun paletteScrollsAboveASelectionNearTheTopOfTheCanvas() = runDesktopComposeUiTest(width = 1400, height = 600) {
        val base = workspace()
        val atTop = shape.translated(0f, 5f - shape.y)
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(atTop))) }
            .selectObject(atTop.id))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        val button = onNodeWithTag(WorkspaceTestTags.ObjectColor)
        button.performClick()
        val menu = onNodeWithTag(SelectionColorTags.Menu).fetchSemanticsNode().boundsInWindow
        assertTrue(menu.top >= 0 && menu.bottom <= button.fetchSemanticsNode().boundsInWindow.top)
        onNodeWithTag(SelectionColorTags.Rainbow).performClick()
        onNodeWithTag(SelectionColorTags.Custom).performScrollTo().performTextReplacement("#123456")
        onNodeWithTag("${SelectionColorTags.Custom}-apply").performScrollTo().performClick()
        runOnIdle { assertEquals(0xff123456.toInt(), (state.value.selectedPage!!.document.outlines.single() as Outline.Shape).borderArgb) }
    }

    private fun SemanticsNodeInteraction.saveImage(name: String) {
        Image.makeFromBitmap(captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                File("build/test-artifacts").apply { mkdirs() }.resolve(name).writeBytes(data.bytes)
            }
        }
    }

    private fun SemanticsNodeInteraction.typeCharacters(value: String) {
        value.forEach { char ->
            performKeyPress(KeyEvent(Key.Unknown, KeyEventType.KeyDown, codePoint = char.code))
            val typed = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED,
                System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
            performKeyPress(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = typed))
            performKeyPress(KeyEvent(Key.Unknown, KeyEventType.KeyUp, codePoint = char.code))
        }
    }
}
