package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.*
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.ui.canvas.SelectionColorTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.math.roundToInt
import kotlin.test.*

/** The equation and shape object tools are icons: edit_square, line_style, a width slider and format_color_fill. */
@OptIn(ExperimentalTestApi::class)
class ObjectToolIconsTest {
    private val shape = Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
        segments = seedSegments(ShapeKind.Rectangle, 180f, 350f, 340f, 450f) { newId() }).withRecomputedBounds()
    private val equation = Outline.Equation("equation", 700f, 350f, 80f, 40f, "x^2")

    private fun workspace(): WorkspaceState {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.View)
        return base.updatePage(base.selectedPageId) {
            it.copy(document = PageDoc(style = PageStyle(hideTitle = true, ruleLines = RuleLines.None),
                outlines = listOf(shape, equation)))
        }
    }

    private fun WorkspaceState.shape() = selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().single()

    @Test
    fun theEquationIsEditedFromTheEditSquareIconRatherThanAWord() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace().selectObject(equation.id))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag("draw-equation-edit").assertContentDescriptionEquals("Edit equation")
            .assert(hasText("Edit", substring = true).not()).performClick()
        onNodeWithTag(EquationTags.Source).assertTextContains("x^2")
    }

    @Test
    fun theLineStyleIconOpensSolidDashedAndDottedWithTheCurrentOneSelected() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace().selectObject(shape.id))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(ShapeObjectTags.LineType).assertContentDescriptionEquals("Change line style")
            .assert(hasText(LineType.Solid.label).not()).performClick()
        LineType.entries.forEach { onNodeWithTag(ShapeObjectTags.lineType(it)).assertIsDisplayed() }
        onNodeWithTag(ShapeObjectTags.lineType(LineType.Solid)).assertIsSelected()
        onNodeWithTag(ShapeObjectTags.lineType(LineType.Dashed)).assertIsNotSelected().performClick()
        runOnIdle { assertEquals(LineType.Dashed, state.value.shape().lineType) }
        onNodeWithTag(ShapeObjectTags.lineType(LineType.Dashed)).assertDoesNotExist()
        onNodeWithTag(ShapeObjectTags.LineType).performClick()
        onNodeWithTag(ShapeObjectTags.lineType(LineType.Dashed)).assertIsSelected()
        onNodeWithTag(ShapeObjectTags.lineType(LineType.Dotted)).performClick()
        runOnIdle { assertEquals(LineType.Dotted, state.value.shape().lineType) }
    }

    @Test
    fun theBorderWidthIsASliderInsteadOfAList() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace().selectObject(shape.id))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        val initial = shape.borderWidth
        onNodeWithTag(ShapeObjectTags.Width).assertContentDescriptionEquals("Change border width").performClick()
        onAllNodesWithText("${ShapeToolSettings.MAX_BORDER_WIDTH} pt").assertCountEquals(0)
        onNodeWithTag("${ShapeObjectTags.Width}-slider").assertIsDisplayed()
        onNodeWithTag("${ShapeObjectTags.Width}-slider-value").assertTextEquals(initial.roundToInt().toString())
        onNodeWithTag("${ShapeObjectTags.Width}-slider-plus").performClick()
        runOnIdle { assertEquals(initial + 1f, state.value.shape().borderWidth) }
        onNodeWithTag("${ShapeObjectTags.Width}-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        runOnIdle { assertEquals(9f, state.value.shape().borderWidth) }
        onNodeWithTag("${ShapeObjectTags.Width}-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(ShapeToolSettings.MAX_BORDER_WIDTH.toFloat())
        }
        onNodeWithTag("${ShapeObjectTags.Width}-slider-plus").assertIsNotEnabled()
        runOnIdle { assertEquals(ShapeToolSettings.MAX_BORDER_WIDTH.toFloat(), state.value.shape().borderWidth) }
        onNodeWithTag("${ShapeObjectTags.Width}-slider").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("${ShapeObjectTags.Width}-slider").assertDoesNotExist()
    }

    @Test
    fun theFillIconPaintsItsBarInTheCurrentFill() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace().selectObject(shape.id))
        setContent { ViveNotesTheme(darkTheme = false) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        val fill = onNodeWithTag(ShapeObjectTags.Fill).assertContentDescriptionEquals("Change fill color")
            .assert(hasText("Fill").not())
        // The 18 dp icon is centred in the 32 dp button; its bar is the bottom sixth of the glyph.
        fun bar() = fill.captureToImage().let { image ->
            val scale = image.width / 32f
            image.toPixelMap()[(16 * scale).toInt(), ((7 + 18 * 880f / 960f) * scale).toInt()]
        }
        val empty = bar()
        assertTrue(empty.red < .8f || empty.green > .4f, "no fill leaves a faint bar, not a coloured one: $empty")
        fill.performClick()
        onNodeWithContentDescription("Red").performClick()
        runOnIdle { assertEquals(0xffe53935.toInt(), state.value.shape().fillArgb) }
        val red = bar()
        assertTrue(red.red > .75f && red.green < .4f && red.blue < .4f, "the bar shows the red fill: $red")
        fill.performClick()
        onNodeWithTag(SelectionColorTags.NoFill).performClick()
        runOnIdle { assertNull(state.value.shape().fillArgb) }
        assertEquals(empty, bar())
    }
}
