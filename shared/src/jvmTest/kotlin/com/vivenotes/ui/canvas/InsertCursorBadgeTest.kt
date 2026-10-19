package com.vivenotes.ui.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.DrawPlacementTool
import com.vivenotes.workspace.PendingDrawEquation
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The badge beside the mouse pointer that shows what a click on the page will insert. */
@OptIn(ExperimentalTestApi::class)
class InsertCursorBadgeTest {
    private val textBadge = InsertBadgeTags.badge(PendingInsert.TextBox)
    private var state by mutableStateOf(WorkspaceState.demo())

    @Test
    fun theArmedToolRidesBesideTheMouse() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(WorkspaceState.demo().toggleTextTool())
        onNodeWithTag(textBadge).assertDoesNotExist()

        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        val bounds = canvas.getUnclippedBoundsInRoot()
        canvas.performMouseInput { moveTo(Offset(300f, 400f)) }
        onNodeWithTag(textBadge).assertIsDisplayed().getUnclippedBoundsInRoot().let {
            assertEquals(bounds.left.value + 316f, it.left.value, 1f, "the badge is not right of the pointer")
            assertEquals(bounds.top.value + 416f, it.top.value, 1f, "the badge is not below the pointer")
            assertEquals(20f, (it.right - it.left).value, 0.5f)
        }
        canvas.performMouseInput { moveTo(Offset(520f, 250f)) }
        onNodeWithTag(textBadge).getUnclippedBoundsInRoot().let {
            assertEquals(bounds.left.value + 536f, it.left.value, 1f, "the badge did not follow the pointer")
            assertEquals(bounds.top.value + 266f, it.top.value, 1f)
        }

        // Off the page, over the page list beside it.
        canvas.performMouseInput { moveTo(Offset(-40f, 300f)) }
        onNodeWithTag(textBadge).assertDoesNotExist()
    }

    @Test
    fun theBadgeShowsWhatTheToolInserts() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(WorkspaceState.demo().toggleShapeTool().setShapeSettings(ShapeToolSettings(kind = ShapeKind.Ellipse)))
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveTo(Offset(300f, 400f)) }
        assertGlyphDrawn(InsertBadgeTags.badge(PendingInsert.Shape(ShapeKind.Ellipse)))

        runOnIdle { state = state.selectDrawPlacement(DrawPlacementTool.Table) }
        assertGlyphDrawn(InsertBadgeTags.badge(PendingInsert.Table))
        onNodeWithTag(InsertBadgeTags.badge(PendingInsert.Shape(ShapeKind.Ellipse))).assertDoesNotExist()

        runOnIdle { state = state.armEquation(PendingDrawEquation("x^2", 40f, 20f)) }
        assertGlyphDrawn(InsertBadgeTags.badge(PendingInsert.Equation))

        runOnIdle { state = state.toggleTextTool() }
        assertGlyphDrawn(textBadge)

        // The ordinary pointer inserts nothing: Esc puts the tool down, and its badge goes with it.
        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        runOnIdle { assertTrue(!state.textToolArmed) }
        onNodeWithTag(textBadge).assertDoesNotExist()
    }

    /** The badge covers no part of the page: a click with it showing still places the text box. */
    @Test
    fun clicksReachThePageBeneathTheBadge() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(WorkspaceState.demo().toggleTextTool())
        fun textBoxes() = state.selectedPage!!.document.outlines.count { it is Outline.Text }
        val before = runOnIdle { textBoxes() }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
            moveTo(Offset(700f, 600f))
            click(Offset(700f, 600f))
        }
        runOnIdle { assertEquals(before + 1, textBoxes()) }
    }

    /** At the canvas's right and bottom edges the badge turns back inside rather than being cut off. */
    @Test
    fun theBadgeStaysOnTheCanvasAtItsEdges() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(WorkspaceState.demo().toggleTextTool())
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        val bounds = canvas.getUnclippedBoundsInRoot()
        val corner = Offset((bounds.right - bounds.left).value - 4f, (bounds.bottom - bounds.top).value - 4f)
        canvas.performMouseInput { moveTo(corner) }
        onNodeWithTag(textBadge).assertIsDisplayed().getUnclippedBoundsInRoot().let {
            assertTrue(it.right <= bounds.right && it.bottom <= bounds.bottom, "badge $it leaves the canvas $bounds")
            assertEquals(bounds.left.value + corner.x - 24f, it.left.value, 1f, "the badge is not left of the pointer")
            assertEquals(bounds.top.value + corner.y - 24f, it.top.value, 1f, "the badge is not above the pointer")
        }
    }

    /** A finger has no hover: nothing follows it. */
    @Test
    fun aFingerGetsNoBadge() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(WorkspaceState.demo().toggleTextTool())
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        canvas.performTouchInput { down(Offset(300f, 400f)); moveBy(Offset(4f, 0f)) }
        onNodeWithTag(textBadge).assertDoesNotExist()
        canvas.performTouchInput { up() }
        onNodeWithTag(textBadge).assertDoesNotExist()
    }

    /** The glyph is there to be seen: light ink on the badge's dark ground. */
    private fun ComposeUiTest.assertGlyphDrawn(tag: String) {
        val pixels = onNodeWithTag(tag).assertIsDisplayed().captureToImage().toPixelMap()
        var light = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val pixel = pixels[x, y]
            if (pixel.red > .7f && pixel.green > .7f && pixel.blue > .7f) light++
        }
        assertTrue(light >= 6, "the $tag badge draws no glyph ($light light pixels)")
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState) {
        state = initial
        setContent {
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state) })
            }
        }
    }
}
