package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.vivenotes.math.MathEngine
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.ui.canvas.INK_LAYER_TAG
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DrawToolkitReferenceTest {
    @Test
    fun darkRibbonPopupsRenderTheCompleteAndroidPanels() = runDesktopComposeUiTest(width = 2400, height = 1600) {
        val state = mutableStateOf(WorkspaceState.demo().copy(shapeSettings = ShapeToolSettings(kind = ShapeKind.Cone)))
        setContent {
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    DrawRibbon(state.value, { state.value = it(state.value) }, canvasInk = Color.White)
                }
            }
        }
        for ((button, preview, name) in listOf(
            Triple(DrawRibbonTags.penKit(0), InkControlsTags.Preview, "pen"),
            Triple(TableTags.Kit, TableTags.Preview, "table"),
            Triple(DrawRibbonTags.ShapeKit, ShapeMenuTags.Preview, "shape"),
        )) {
            onNodeWithTag(button).performClick()
            val popup = onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(preview)))
            assertEquals(640f, popup.fetchSemanticsNode().boundsInRoot.width)
            onNodeWithTag(preview).assertIsDisplayed()
            save(onNode(isPopup() and hasAnyDescendant(hasTestTag(preview))), "android-popup-$name.png")
            onNode(isRoot() and hasAnyDescendant(hasTestTag(button)))
                .performMouseInput { click(centerRight - Offset(4f, 0f)) }
            onNodeWithTag(preview).assertDoesNotExist()
        }
    }

    @Test
    fun penMatchesTheReferenceWithASingleCircularPaletteAndCompactControls() = runDesktopComposeUiTest(width = 700, height = 1100) {
        val state = mutableStateOf(InkSettings())
        setContent {
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    PenKit(0, state.value, Color.White) { state.value = it }
                }
            }
        }
        val panel = onNodeWithTag("draw-toolkit-pen-1")
        assertEquals(640f, panel.fetchSemanticsNode().boundsInRoot.width)
        val preview = onNodeWithTag(InkControlsTags.Preview)
        assertEquals(152f, preview.fetchSemanticsNode().boundsInRoot.height)
        assertEquals(Color(0xFF383838), preview.captureToImage().toPixelMap()[288, 12])
        val fountain = onNodeWithTag(InkControlsTags.brush(InkBrush.Marker))
        fountain.assertIsSelected()
        assertEquals(112f, fountain.fetchSemanticsNode().boundsInRoot.height)
        assertEquals(Color(0xFF00478E), fountain.captureToImage().toPixelMap()[30, 30])
        assertEquals(116f, onNodeWithTag(InkControlsTags.line(LineType.Solid)).fetchSemanticsNode().boundsInRoot.width)
        assertEquals(144f, onNodeWithTag(InkControlsTags.Stabilization).fetchSemanticsNode().boundsInRoot.width)
        val swatches = (PenColors.map(InkControlsTags::color) + InkControlsTags.WheelButton).map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(swatches.all { it.top == swatches.first().top && it.width == 52f })
        onNodeWithTag(InkControlsTags.color(PenColors.first())).assertIsSelected()
        onNodeWithTag(InkControlsTags.Automatic).assertDoesNotExist()
        onNodeWithTag(InkControlsTags.CustomColor).assertDoesNotExist()
        onNodeWithText("Solid").assertDoesNotExist()
        save(panel, "android-reference-pen.png")
        onNodeWithTag(InkControlsTags.Stabilization).performClick()
        onNodeWithTag("${InkControlsTags.Stabilization}-option-0").assertTextEquals("Off").performClick()
        onNodeWithTag(InkControlsTags.Stabilization).assertTextEquals("0")
        runOnIdle { assertEquals(0, state.value.pen.stabilization) }
    }

    @Test
    fun tableMatchesTheReferenceAndItsCountAndFillPickersUpdateThePreview() = runDesktopComposeUiTest(width = 700, height = 1100) {
        val state = mutableStateOf(InkSettings())
        setContent {
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) { TableKit(state.value, Color.White) { state.value = it } }
            }
        }
        val preview = onNodeWithTag(TableTags.Preview)
        assertEquals(192f, preview.fetchSemanticsNode().boundsInRoot.height)
        val pixels = preview.captureToImage().toPixelMap()
        assertEquals(Color(0xFF383838), pixels[100, 100])
        assertTrue(pixels[100, 40].red > pixels[100, 100].red, "the header row is shaded")
        onNodeWithTag("draw-table-ink-only").assertIsOn()
        onNodeWithTag("draw-table-header-row").assertIsOn()
        onNodeWithTag("draw-table-header-column").assertIsOff()
        assertEquals(144f, onNodeWithTag(TableTags.Columns).fetchSemanticsNode().boundsInRoot.width)
        assertEquals(56f, onNodeWithTag(TableTags.Fill).fetchSemanticsNode().boundsInRoot.width)
        save(onNodeWithTag("draw-toolkit-table"), "android-reference-table.png")
        onNodeWithTag(TableTags.Columns).performClick()
        onNodeWithTag("${TableTags.Columns}-option-4").performClick()
        onNodeWithTag(TableTags.Rows).performClick()
        onNodeWithTag("${TableTags.Rows}-option-2").performClick()
        onNodeWithTag("draw-table-header-column").performClick()
        onNodeWithTag(TableTags.Fill).performClick()
        onNodeWithTag("draw-table-fill-custom").performScrollTo().performTextReplacement("#123456")
        onNodeWithTag("draw-table-fill-custom-apply").performClick()
        runOnIdle { assertEquals(0xFF123456.toInt(), state.value.table.fillArgb, "committing a custom fill") }
        onNodeWithTag(InkControlsTags.WheelDone).performClick()
        runOnIdle {
            assertEquals(4, state.value.table.columns)
            assertEquals(2, state.value.table.rows)
            assertTrue(state.value.table.headerColumn)
            assertEquals(0xFF123456.toInt(), state.value.table.fillArgb)
            assertEquals(PenColors.take(2), state.value.palette.take(2))
            assertEquals(0xFF123456.toInt(), state.value.palette[2])
        }
        onNodeWithTag(TableTags.Fill).performClick()
        onNodeWithTag("draw-table-fill-none").performClick()
        runOnIdle { assertNull(state.value.table.fillArgb) }
    }

    @Test
    fun shapeMatchesTheReferenceAndKeepsPagedSelectionLineStylesAndSharedFillColors() = runDesktopComposeUiTest(width = 700, height = 1100) {
        val shape = mutableStateOf(ShapeToolSettings(kind = ShapeKind.Cone))
        val palette = mutableStateOf(PenColors)
        setContent {
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    ShapeKit(shape.value, palette.value, Color.White, { palette.value = palette.value.withColorInFront(it) }) { shape.value = it }
                }
            }
        }
        val preview = onNodeWithTag(ShapeMenuTags.Preview)
        assertEquals(192f, preview.fetchSemanticsNode().boundsInRoot.height)
        onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Cone)).assertIsSelected()
        assertEquals(88f, onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Cone)).fetchSemanticsNode().boundsInRoot.width)
        assertEquals(6, onAllNodes(hasTestTag(ShapeMenuTags.Grid)).onFirst().fetchSemanticsNode().children.size)
        save(onNodeWithTag("draw-toolkit-shape"), "android-reference-shape.png")
        val image = preview.captureToImage().toPixelMap()
        val hidden = (180..395).count { x -> image[x, 108].red > .8f }
        assertTrue(hidden in 1..200, "the cone's hidden ellipse has separated dots")
        onNodeWithTag(ShapeMenuTags.Grid).performTouchInput { swipeRight() }
        onNodeWithTag("draw-shape-page-0").assertIsSelected()
        onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Rectangle)).performClick()
        onNodeWithTag(ShapeMenuTags.lineType(LineType.Dotted)).performClick()
        onNodeWithTag(ShapeMenuTags.Width).performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        onNodeWithTag(ShapeMenuTags.border(0xFF00C853.toInt())).performClick()
        onNodeWithTag(ShapeMenuTags.Fill).performClick()
        onNodeWithTag("draw-shape-custom-fill").performScrollTo().performTextReplacement("#123456")
        onNodeWithTag("draw-shape-custom-fill-apply").performClick()
        onNodeWithTag(InkControlsTags.WheelDone).performClick()
        runOnIdle {
            assertEquals(ShapeKind.Rectangle, shape.value.kind)
            assertEquals(LineType.Dotted, shape.value.lineType)
            assertEquals(5, shape.value.borderWidth)
            assertEquals(0xFF00C853.toInt(), shape.value.borderArgb)
            assertFalse(shape.value.colorFollowsTheme)
            assertEquals(0xFF123456.toInt(), shape.value.fillArgb)
            assertEquals(PenColors.take(2), palette.value.take(2))
            assertEquals(0xFF123456.toInt(), palette.value[2])
        }
        onNodeWithTag(ShapeMenuTags.Fill).performClick()
        onNodeWithTag(ShapeMenuTags.fill(null)).performClick()
        runOnIdle { assertNull(shape.value.fillArgb) }
    }

    @Test
    fun calculatorMatchesTheReferenceAndScratchActionsTrackItsContents() = runDesktopComposeUiTest(width = 1000, height = 1000) {
        var closed = false
        val math = object : DrawMath {
            override val engine: MathEngine get() = error("No calculation requested")
            override suspend fun readFormula(ink: InkPage): String = error("No recognition requested")
        }
        setContent {
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    FloatingCalculator(InkSettings(), 0, true, math, {}, { closed = true })
                }
            }
        }
        val window = onNodeWithTag(CalculatorTags.Window)
        assertEquals(760f, window.fetchSemanticsNode().boundsInRoot.width)
        assertEquals(640f, window.fetchSemanticsNode().boundsInRoot.height)
        assertEquals(400f, onNodeWithTag(CalculatorTags.Pad).fetchSemanticsNode().boundsInRoot.height)
        assertEquals(Color(0xFF1F1F1F), onNodeWithTag(CalculatorTags.Pad).captureToImage().toPixelMap()[100, 100])
        onNodeWithTag(CalculatorTags.Hint).assertIsDisplayed()
        onNodeWithTag(CalculatorTags.Clear).assertIsNotEnabled()
        onNodeWithTag(CalculatorTags.Undo).assertIsNotEnabled()
        onNodeWithTag(CalculatorTags.Equals).assertIsNotEnabled()
        onNodeWithText("Close").assertDoesNotExist()
        save(window, "android-reference-calculator.png")
        onNodeWithTag(CalculatorTags.Decimal).performClick().assertIsOn()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(40f, 80f)); press(); moveTo(Offset(400f, 80f), delayMillis = 80)
        }
        onNodeWithTag(CalculatorTags.Hint).assertDoesNotExist()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { release() }
        onNodeWithTag(CalculatorTags.Clear).assertIsEnabled()
        onNodeWithTag(CalculatorTags.Undo).assertIsEnabled().performClick()
        onNodeWithTag(CalculatorTags.Hint).assertIsDisplayed()
        onNodeWithTag(CalculatorTags.Equals).assertIsNotEnabled()
        onNodeWithTag(CalculatorTags.Decimal).assertIsOn()
        onNodeWithTag(CalculatorTags.Header).performMouseInput {
            updatePointerTo(Offset(50f, 40f)); press(); moveBy(Offset(-100f, -100f), delayMillis = 80); release()
        }
        assertTrue(window.fetchSemanticsNode().boundsInRoot.left >= 0f)
        onNodeWithTag(CalculatorTags.Close).performClick()
        runOnIdle { assertTrue(closed) }
    }

    @Test
    fun toolkitsRemainReachableInAShortDesktopWindowWithLargerFonts() = runDesktopComposeUiTest(width = 900, height = 400) {
        val state = mutableStateOf(WorkspaceState.demo())
        setContent {
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    DrawRibbon(state.value, { state.value = it(state.value) }, canvasInk = Color.White)
                }
            }
        }
        onNodeWithTag(TableTags.Kit).performClick()
        onNodeWithTag(TableTags.Fill).performScrollTo().assertIsDisplayed().performClick()
        onNodeWithTag("draw-table-fill-none").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithTag(TableTags.Fill).assertIsDisplayed()
    }

    private fun save(node: SemanticsNodeInteraction, name: String) {
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }
}
