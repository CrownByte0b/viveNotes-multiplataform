package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bar along a text box's top that a mouse grabs to move the box. */
@OptIn(ExperimentalTestApi::class)
class TextBoxMoveBarTest {
    private val box = Outline.Text(id = BOX, x = 300f, y = 400f, width = 400f, blocks = listOf(Block.of("Move me")))

    @Test
    fun theBarComesWithTheMouseAndGoesWithIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(page(listOf(box))) { }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertDoesNotExist()

        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertIsDisplayed()

        // Up onto the bar itself, which keeps it up although the mouse has left the box's body.
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertIsDisplayed()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveTo(Offset(40f, height - 40f)) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertDoesNotExist()
    }

    @Test
    fun theBarSitsOnTheBoxWithoutMovingItsText() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(page(listOf(box))) { }
        val before = onNodeWithTag(WorkspaceTestTags.textBox(BOX)).getUnclippedBoundsInRoot()

        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }

        val bar = onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).getUnclippedBoundsInRoot()
        assertEquals(before, onNodeWithTag(WorkspaceTestTags.textBox(BOX)).getUnclippedBoundsInRoot())
        assertEquals(before.top + 1.dp, bar.bottom, "the bar's bottom border lies on the box's top border")
        assertEquals(before.left, bar.left)
        assertEquals(before.right, bar.right)
        assertEquals(16.dp, bar.bottom - bar.top)
    }

    @Test
    fun draggingTheBarOfABoxNobodyIsEditingMovesItAsOneUndoStepWithoutFocusingIt() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = page(listOf(box))
            var observed = initial
            setWorkspace(initial) { observed = it }
            dragBar(Offset(60f, 40f))
            runOnIdle {
                val moved = observed.selectedPage!!.document.outlines.single() as Outline.Text
                // Short of the mouse by the drag's slop, as every drag on the page is.
                assertEquals(box.x + 60f, moved.x, SLOP)
                assertEquals(box.y + 40f, moved.y, SLOP)
                assertNull(observed.focusedTextOutlineId)
                assertEquals(initial.structuralUndo.size + 1, observed.structuralUndo.size)
                val undone = observed.undoStructure().selectedPage!!.document.outlines.single() as Outline.Text
                assertEquals(box.x, undone.x)
            }
            // Still under the mouse, so it stays up, ready for the next drag.
            onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertIsDisplayed()
        }

    /** A press near the bar's edge leaves its 16 dp before the drag has begun: it must not vanish. */
    @Test
    fun aDragThatLeavesTheThinBarBeforeTheSlopStillMovesTheBox() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(listOf(box))
        setWorkspace(observed) { observed = it }
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performMouseInput {
            moveTo(Offset(100f, 1f))
            press()
            // Upward, off the bar and onto the bare page, in steps too small to begin a drag at first.
            for (step in 1..30) moveBy(Offset(0f, -4f))
            release()
        }
        runOnIdle {
            val moved = observed.selectedPage!!.document.outlines.single() as Outline.Text
            assertTrue(moved.y < box.y - 60f, "the box followed the mouse: ${moved.y}")
            assertTrue(observed.selectedTextOutlineIds.isEmpty(), "the page drew no marquee")
        }
    }

    @Test
    fun aClickOnTheBarNeitherEditsNorSelectsNorLeavesTheBoxBeingEdited() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val other = Outline.Text(id = "other", x = 300f, y = 700f, width = 400f, blocks = listOf(Block.of("Other")))
            var observed = page(listOf(box, other)).focusTextBox(other.id)
            val initial = observed
            setWorkspace(observed) { observed = it }

            onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
            onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performMouseInput { click(center) }

            runOnIdle {
                assertEquals(other.id, observed.focusedTextOutlineId)
                assertEquals(initial.selectedPage!!.document, observed.selectedPage!!.document)
                assertTrue(observed.selectedTextOutlineIds.isEmpty())
            }
        }

    /** The strip above a box is bare page until the bar is up: it must not swallow a click there. */
    @Test
    fun aClickWhereTheBarWouldBeIsOnThePageWhileTheBarIsDown() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(listOf(box)).toggleTextTool()
        setWorkspace(observed) { observed = it }
        val boxBounds = onNodeWithTag(WorkspaceTestTags.textBox(BOX)).getUnclippedBoundsInRoot()
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas).getUnclippedBoundsInRoot()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
            click(Offset((boxBounds.left - canvas.left).toPx() + 100f, (boxBounds.top - canvas.top).toPx() - 8f))
        }
        runOnIdle { assertEquals(2, observed.selectedPage!!.document.outlines.size, "the Text tool placed a box") }
    }

    @Test
    fun anArmedToolTakesTheStripAboveABoxInsteadOfTheBar() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(page(listOf(box)).toggleShapeTool()) { }
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertDoesNotExist()
    }

    /** Boxes stacked closely: the upper box comes later in the list, so it is drawn over the lower's bar. */
    @Test
    fun theBarStaysGrabbableOverTheBoxAboveIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val above = Outline.Text(id = "above", x = 300f, y = 300f, width = 400f, minHeight = 95f, blocks = listOf(Block.of("Above")))
        var observed = page(listOf(box, above))
        setWorkspace(observed) { observed = it }
        dragBar(Offset(50f, 0f))
        runOnIdle {
            val outlines = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
            assertEquals(box.x + 50f, outlines.first { it.id == BOX }.x, SLOP)
            assertEquals(above.x, outlines.first { it.id == above.id }.x)
            assertNull(observed.focusedTextOutlineId, "the press went to the bar, not the text beneath it")
        }
    }

    /** At the page's very top the bar lies on the box, and must still be what the mouse grabs. */
    @Test
    fun aBoxAtThePagesTopKeepsAGrabbableBarOverItsOwnText() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val top = box.copy(y = 0f)
        var observed = page(listOf(top))
        setWorkspace(observed) { observed = it }
        dragBar(Offset(40f, 30f))
        runOnIdle {
            val moved = observed.selectedPage!!.document.outlines.single() as Outline.Text
            assertEquals(top.x + 40f, moved.x, SLOP)
            assertEquals(30f, moved.y, SLOP)
            assertNull(observed.focusedTextOutlineId)
        }
    }

    /** The mouse is far past the viewport; the box it holds must not be culled out from under it. */
    @Test
    fun aBoxDraggedOutOfTheWindowStaysInTheMousesHold() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(listOf(box))
        setWorkspace(observed) { observed = it }
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 6000f))
        }
        runOnIdle { assertTrue((observed.selectedPage!!.document.outlines.single() as Outline.Text).y > 6000f) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertExists()
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performMouseInput { moveBy(Offset(0f, 100f)) }
        runOnIdle { assertTrue((observed.selectedPage!!.document.outlines.single() as Outline.Text).y > 6100f) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performMouseInput { release() }
    }

    /** The page finds the box under the mouse by geometry, which each of these changes. */
    @Test
    fun theBarComesUpAfterTheDisplayScaleChanges() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val scale = mutableStateOf(1f)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale.value)) {
                var state by remember { mutableStateOf(page(listOf(box))) }
                ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state = state, onStateChange = { state = it(state) }) }
            }
        }
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveTo(Offset(40f, height - 40f)) }

        runOnIdle { scale.value = 0.75f }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertIsDisplayed()
    }

    @Test
    fun theBarComesUpAndMovesTheBoxUnderTheMouseWhenZoomed() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(listOf(box))
        setContent {
            var state by remember { mutableStateOf(observed) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); observed = state },
                    viewSettings = ViewSettings(zoom = 1.5f))
            }
        }
        dragBar(Offset(90f, 60f))
        runOnIdle {
            val moved = observed.selectedPage!!.document.outlines.single() as Outline.Text
            // 1.5 pixels to the page's dp, so the box keeps under the mouse.
            assertEquals(box.x + 60f, moved.x, SLOP)
            assertEquals(box.y + 40f, moved.y, SLOP)
        }
    }

    @Test
    fun theBarFollowsTheBoxUnderTheMouseWhenThePageScrolls() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val tall = Outline.Text(id = "tall", x = 300f, y = 2400f, width = 400f, blocks = listOf(Block.of("Far below")))
        setWorkspace(page(listOf(box, tall))) { }
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertIsDisplayed()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { scroll(600f) }
        waitForIdle()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveBy(Offset(1f, 0f)) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertDoesNotExist()
    }

    @Test
    fun aFingerMovesTheBoxBeingEditedByItsBar() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(listOf(box)).focusTextBox(BOX)
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performTouchInput {
            swipe(center, center + Offset(80f, 60f), durationMillis = 300)
        }
        runOnIdle {
            val moved = observed.selectedPage!!.document.outlines.single() as Outline.Text
            assertTrue(moved.x > box.x + 30f, "the box followed the finger: ${moved.x}")
            assertTrue(moved.y > box.y + 20f, "the box followed the finger: ${moved.y}")
        }
    }

    @Test
    fun aFingerNeverRevealsTheBarOnABoxNobodyIsEditing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(page(listOf(box))) { }
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performTouchInput { down(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performTouchInput { cancel() }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).assertDoesNotExist()
    }

    private fun ComposeUiTest.dragBar(by: Offset) {
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { moveTo(center) }
        onNodeWithTag(WorkspaceTestTags.textMoveBar(BOX)).performMouseInput {
            moveTo(center)
            press()
            moveBy(by)
            release()
        }
    }

    private fun page(outlines: List<Outline>): WorkspaceState {
        val demo = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
        return demo.updatePage(demo.selectedPageId) { it.copy(document = PageDoc(outlines = outlines)) }.clearCanvasFocus()
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, onStateChange: (WorkspaceState) -> Unit) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
            }
        }
    }

    private companion object {
        const val BOX = "bar-box"
        const val SLOP = 20f
    }
}
