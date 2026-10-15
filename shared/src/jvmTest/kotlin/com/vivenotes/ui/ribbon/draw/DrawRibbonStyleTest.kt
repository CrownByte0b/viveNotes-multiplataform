package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.components.HoverTooltipDelayMillis
import com.vivenotes.ui.ribbon.RibbonStyle
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DrawRibbonStyleTest {
    @Test
    fun lassoIsIconOnlyAndKeepsItsAccessibleNameTooltipAndToggle() = runDesktopComposeUiTest(width = 1200, height = 700) {
        val state = mutableStateOf(WorkspaceState.demo())
        setContent { ViveNotesTheme { DrawRibbon(state.value, { state.value = it(state.value) }) } }
        onNodeWithText("Lasso").assertDoesNotExist()
        val lasso = onNodeWithTag(DrawRibbonTags.ObjectLasso)
            .assertContentDescriptionEquals("Lasso").assertIsEnabled().assertIsNotSelected()
        val bounds = lasso.getUnclippedBoundsInRoot()
        assertEquals(40f, (bounds.right - bounds.left).value, .1f)
        lasso.performClick().assertIsSelected()
        runOnIdle { assertTrue(state.value.objectLassoArmed) }
        lasso.performKeyInput { pressKey(Key.Enter) }
        lasso.assertIsNotSelected()
        runOnIdle { assertFalse(state.value.objectLassoArmed) }

        mainClock.autoAdvance = false
        lasso.performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(HoverTooltipDelayMillis + 20)
        onNodeWithText("Lasso").assertIsDisplayed()
    }

    @Test
    fun selectIsRemovedAndDropdownsShareCompactToolControlsAndHover() = runDesktopComposeUiTest(width = 1200, height = 700) {
        val state = mutableStateOf(WorkspaceState.demo())
        setContent { ViveNotesTheme { DrawRibbon(state.value, { state.value = it(state.value) }) } }
        onNodeWithContentDescription("Select").assertDoesNotExist()
        onNodeWithTag("workspace-pointer-tool").assertDoesNotExist()
        val tools = (0..2).map { DrawRibbonTags.pen(it) to DrawRibbonTags.penKit(it) } + listOf(
            DrawRibbonTags.HighlighterTool to DrawRibbonTags.HighlighterKit,
            DrawRibbonTags.EraserTool to DrawRibbonTags.EraserModePicker,
            ShapeMenuTags.ShapeTool to DrawRibbonTags.ShapeKit,
            TableTags.Tool to TableTags.Kit,
            EquationTags.Tool to EquationTags.Kit,
            DrawRibbonTags.RulerTool to DrawRibbonTags.RulerKit,
        )
        for ((tool, menu) in tools) {
            val control = onNodeWithTag(DrawRibbonTags.control(tool))
            val bounds = control.getUnclippedBoundsInRoot()
            val body = onNodeWithTag(tool).assertIsEnabled().getUnclippedBoundsInRoot()
            val arrow = onNodeWithTag(menu).assertIsEnabled()
            arrow.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            val arrowBounds = arrow.getUnclippedBoundsInRoot()
            assertEquals(54f, (bounds.right - bounds.left).value, .1f)
            assertEquals(46f, (bounds.bottom - bounds.top).value, .1f)
            assertEquals(bounds.left, body.left)
            assertEquals(body.right, arrowBounds.left)
            assertEquals(bounds.right, arrowBounds.right)
            arrow.performMouseInput { moveTo(center) }
            val pixels = control.captureToImage().toPixelMap()
            assertEquals(RibbonStyle.hover, pixels[4, 4], "hover covers the tool body")
            assertEquals(RibbonStyle.hover, pixels[pixels.width - 4, 4], "hover covers the inset chevron")
        }
    }

    @Test
    fun penAndHighlighterTipsShowTheirIndependentColorsAndFollowCanvasInk() = runDesktopComposeUiTest(width = 1200, height = 700) {
        val state = mutableStateOf(WorkspaceState.demo())
        val canvasInk = mutableStateOf(Color.Black)
        setContent { ViveNotesTheme { DrawRibbon(state.value, { state.value = it(state.value) }, canvasInk = canvasInk.value) } }
        fun assertTip(tag: String, color: Color) {
            onNodeWithTag("$tag-color", useUnmergedTree = true).assertDoesNotExist()
            val pixels = onNodeWithTag(DrawRibbonTags.icon(tag), useUnmergedTree = true).captureToImage().toPixelMap()
            assertEquals(color, pixels[pixels.width / 2, pixels.height / 2], "$tag tip color")
            assertTrue((pixels.height * 3 / 4 until pixels.height * 9 / 10).any { y ->
                val base = pixels[pixels.width / 2, y]
                base.red > .85f && base.green > .85f && base.blue > .85f
            }, "$tag keeps its neutral base")
        }
        assertTip(DrawRibbonTags.pen(0), Color.Black)
        assertTip(DrawRibbonTags.pen(1), Color(state.value.inkSettings.pen2.colorArgb))
        assertTip(DrawRibbonTags.pen(2), Color(state.value.inkSettings.pen3.colorArgb))
        assertTip(DrawRibbonTags.HighlighterTool, Color(state.value.inkSettings.highlighter.colorArgb).copy(alpha = 1f))
        runOnIdle { canvasInk.value = Color.White }
        assertTip(DrawRibbonTags.pen(0), Color.White)
        assertTip(DrawRibbonTags.pen(1), Color(state.value.inkSettings.pen2.colorArgb))
        onNodeWithTag(DrawRibbonTags.penKit(2)).performClick()
        onNodeWithTag(InkControlsTags.color(0xffe53935.toInt())).performClick()
        onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        assertTip(DrawRibbonTags.pen(2), Color(0xffe53935))
        assertTip(DrawRibbonTags.pen(0), Color.White)
        onNodeWithTag(DrawRibbonTags.HighlighterKit).performClick()
        onNodeWithTag(InkControlsTags.color(HighlighterColors[2])).performClick()
        onNodeWithTag(InkControlsTags.Preview).performKeyInput { pressKey(Key.Escape) }
        assertTip(DrawRibbonTags.HighlighterTool, Color(HighlighterColors[2]).copy(alpha = 1f))
        assertTip(DrawRibbonTags.pen(2), Color(0xffe53935))
    }

    @Test
    fun joinedControlsKeepKeyboardSelectionAndMenuAccess() = runDesktopComposeUiTest(width = 1200, height = 700) {
        val state = mutableStateOf(WorkspaceState.demo())
        setContent { ViveNotesTheme { DrawRibbon(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(DrawRibbonTags.pen(1)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag(DrawRibbonTags.pen(1)).performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(DrawRibbonTags.pen(1)).assertIsSelected()
        onNodeWithTag(InkControlsTags.Preview).assertDoesNotExist()
        onNodeWithTag(DrawRibbonTags.penKit(1)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag(DrawRibbonTags.penKit(1)).performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(InkControlsTags.Preview).assertIsDisplayed()
        runOnIdle { assertEquals(1, state.value.activePenIndex); assertEquals(InkTool.Pen, state.value.inkTool) }
    }

    @Test
    fun unavailablePageDisablesBothPartsOfTheJoinedControls() = runDesktopComposeUiTest(width = 1200, height = 700) {
        val initial = WorkspaceState.demo()
        val state = initial.updatePage(initial.selectedPageId) { it.copy(inkReady = false) }
        var changes = 0
        setContent { ViveNotesTheme { DrawRibbon(state, { changes++ }) } }
        for ((tool, menu) in listOf(DrawRibbonTags.PenTool to DrawRibbonTags.penKit(0),
            DrawRibbonTags.HighlighterTool to DrawRibbonTags.HighlighterKit,
            DrawRibbonTags.EraserTool to DrawRibbonTags.EraserModePicker)) {
            onNodeWithTag(tool).assertIsNotEnabled().performClick()
            onNodeWithTag(menu).assertIsNotEnabled().performClick()
        }
        onNodeWithTag(DrawRibbonTags.ObjectLasso).assertIsNotEnabled().performClick()
        onNodeWithTag(InkControlsTags.Preview).assertDoesNotExist()
        runOnIdle { assertEquals(0, changes) }
    }
}
