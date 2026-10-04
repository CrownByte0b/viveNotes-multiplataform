package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.width
import com.vivenotes.model.Outline
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android's touch on the page: pinch, pan and fling, and a finger that draws only when allowed. */
@OptIn(ExperimentalTestApi::class)
class CanvasTouchTest {

    @Test
    fun twoFingersZoomAboutThePointBetweenThem() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var zoom = 1f
        setCanvas(withShape(), onView = { zoom = it.zoom })
        val before = bounds(WorkspaceTestTags.primeObject(SHAPE))
        val centre = before.centre() - canvasOrigin()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            pinch(centre - Offset(60f, 0f), centre - Offset(120f, 0f),
                centre + Offset(60f, 0f), centre + Offset(120f, 0f), durationMillis = 400)
        }

        runOnIdle { assertTrue(zoom in 1.6f..2.05f, "the spread doubled the zoom: $zoom") }
        val after = bounds(WorkspaceTestTags.primeObject(SHAPE))
        assertEquals(zoom, after.width.value / before.width.value, 0.02f)
        // The page point that was between the fingers is still between them.
        assertEquals(before.centre().x, after.centre().x, 4f)
        assertEquals(before.centre().y, after.centre().y, 4f)
    }

    @Test
    fun twoFingersMovedTogetherPanThePageWithoutZooming() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var zoom = 1f
        setCanvas(withShape(), onView = { zoom = it.zoom })
        val before = bounds(WorkspaceTestTags.primeObject(SHAPE))

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            down(0, Offset(400f, 500f))
            down(1, Offset(500f, 500f))
            // Both fingers in each report, as a touch screen's frame carries them.
            repeat(10) {
                updatePointerBy(0, Offset(0f, -12f))
                updatePointerBy(1, Offset(0f, -12f))
                move(delayMillis = 16)
            }
            up(0)
            up(1)
        }

        runOnIdle { assertEquals(1f, zoom) }
        val moved = before.top.value - bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value
        assertTrue(moved in 90f..125f, "the page followed both fingers up: $moved")
    }

    @Test
    fun aFingerOnTheEmptyPageMovesItAndLetsItGlide() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = withShape()
        setCanvas(observed, onState = { observed = it })
        val start = bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value

        // Slowly, coming to rest before lifting: the page moves exactly with the finger.
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            down(Offset(700f, 600f))
            repeat(10) { moveBy(Offset(0f, -10f), delayMillis = 30) }
            advanceEventTime(300)
            up()
        }
        // A scroll: the page starts once the finger has passed the slop, and follows from there.
        val dragged = start - bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value
        assertEquals(100f - touchSlop, dragged, 3f)
        runOnIdle {
            assertTrue(observed.selectedObjectIds.isEmpty(), "a finger draws no marquee")
        }

        // Quickly: it keeps going after the finger leaves.
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            swipe(Offset(700f, 600f), Offset(700f, 500f), durationMillis = 100)
        }
        val flung = start - dragged - bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value
        assertTrue(flung > 140f, "a fling glides past the finger's own 100 px: $flung")
    }

    @Test
    fun withAPenArmedAFingerMovesThePageAndLeavesNoInk() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = withShape().toggleInkTool(InkTool.Pen)
        setCanvas(observed, onState = { observed = it })
        val start = bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            down(Offset(700f, 600f))
            repeat(8) { moveBy(Offset(0f, -10f), delayMillis = 30) }
            advanceEventTime(300)
            up()
        }

        runOnIdle { assertTrue(observed.pendingInkEdits.isEmpty(), "the finger wrote nothing") }
        // Under a tool the page follows the finger from its first move, as Android's `panPage` does.
        assertEquals(80f, start - bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value, 1f)
        // The mouse still writes with the pen.
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
            moveTo(Offset(600f, 500f)); press(); moveTo(Offset(700f, 520f)); release()
        }
        runOnIdle { assertEquals(1, observed.pendingInkEdits.size) }
    }

    @Test
    fun withAPenArmedTwoQuickFingerTapsOfferPaste() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val copied = withShape().selectObject(SHAPE).copySelectedObjects()
        setCanvas(copied.toggleInkTool(InkTool.Pen))
        onNodeWithTag(WorkspaceTestTags.CanvasPaste).assertDoesNotExist()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
            click(Offset(700f, 600f))
            advanceEventTime(120)
            click(Offset(702f, 601f))
        }

        onNodeWithTag(WorkspaceTestTags.CanvasPaste).assertExists()
    }

    @Test
    fun whenFingersMayDrawAFingerWritesAndASecondFingerTurnsItIntoAPinch() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = withShape().toggleInkTool(InkTool.Pen)
            var zoom = 1f
            setCanvas(observed, input = InputSettings(drawWithFinger = true),
                onState = { observed = it }, onView = { zoom = it.zoom })
            val start = bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
                down(Offset(600f, 600f))
                repeat(8) { moveBy(Offset(12f, -6f), delayMillis = 20) }
                up()
            }
            runOnIdle { assertEquals(1, observed.pendingInkEdits.size, "the finger wrote a stroke") }
            assertEquals(start, bounds(WorkspaceTestTags.primeObject(SHAPE)).top.value, 0.5f)

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
                down(0, Offset(600f, 600f))
                repeat(4) { moveBy(0, Offset(8f, 0f), delayMillis = 20) }
                down(1, Offset(760f, 600f))
                repeat(10) {
                    moveBy(0, Offset(-10f, 0f), delayMillis = 16)
                    moveBy(1, Offset(10f, 0f), delayMillis = 0)
                }
                up(0)
                up(1)
            }
            runOnIdle {
                assertEquals(1, observed.pendingInkEdits.size, "the stroke a pinch took over was dropped")
                assertTrue(zoom > 1.1f, "the two fingers zoomed: $zoom")
            }
        }

    @Test
    fun theObjectLassoFollowsTheFingerSetting() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = withShape().copy(activeTab = RibbonTab.Draw)
        var input by mutableStateOf(InputSettings())
        setContent {
            var state by remember { mutableStateOf(observed) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); observed = state },
                    inputSettings = input)
            }
        }
        onNodeWithTag(DrawRibbonTags.ObjectLasso).performClick()
        val shape = bounds(WorkspaceTestTags.primeObject(SHAPE))
        val from = Offset(shape.left.value - 40f, shape.top.value - 30f) - canvasOrigin()
        val to = Offset(shape.right.value + 40f, shape.bottom.value + 30f) - canvasOrigin()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput { swipe(from, to, durationMillis = 400) }
        runOnIdle { assertTrue(observed.selectedObjectIds.isEmpty(), "a finger moved the page instead") }

        input = InputSettings(drawWithFinger = true)
        val moved = bounds(WorkspaceTestTags.primeObject(SHAPE))
        val from2 = Offset(moved.left.value - 40f, moved.top.value - 30f) - canvasOrigin()
        val to2 = Offset(moved.right.value + 40f, moved.bottom.value + 30f) - canvasOrigin()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput { swipe(from2, to2, durationMillis = 400) }
        runOnIdle { assertEquals(setOf(SHAPE), observed.selectedObjectIds) }
    }

    private var touchSlop = 0f

    private fun ComposeUiTest.setCanvas(
        initial: WorkspaceState,
        input: InputSettings = InputSettings(),
        onState: (WorkspaceState) -> Unit = {},
        onView: (ViewSettings) -> Unit = {},
    ) {
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            var state by remember { mutableStateOf(initial) }
            var view by remember { mutableStateOf(ViewSettings()) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onState(state) },
                    viewSettings = view, onViewSettingsChange = { view = it; onView(it) },
                    inputSettings = input)
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getBoundsInRoot()

    private fun ComposeUiTest.canvasOrigin(): Offset = bounds(WorkspaceTestTags.PageCanvas).let {
        Offset(it.left.value, it.top.value)
    }

    private fun DpRect.centre() = Offset((left.value + right.value) / 2, (top.value + bottom.value) / 2)

    private fun withShape(): WorkspaceState {
        val base = WorkspaceState.demo()
        val id = base.selectedPageId
        val shape = Outline.Shape(id = SHAPE, x = 300f, y = 350f)
        return base.copy(notebooks = base.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { page ->
                    if (page.id == id) page.copy(document = page.document.copy(
                        outlines = page.document.outlines + shape)) else page
                })
            })
        })
    }

    private companion object {
        const val SHAPE = "touch-shape"
    }
}
