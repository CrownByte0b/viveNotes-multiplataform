package com.vivenotes.ui.shell

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.*
import com.vivenotes.ui.canvas.*
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

/** Android PrimeObjectTest curve interactions through the complete desktop workspace. */
@OptIn(ExperimentalTestApi::class)
class CurveInteractionTest {
    private fun curve(): Outline.Shape {
        var next = 0
        return Outline.Shape(id = "curve", kind = ShapeKind.Curve,
            segments = seedSegments(ShapeKind.Curve, 60f, 180f, 300f, 320f) { "curve-${next++}" },
        ).withRecomputedBounds()
    }

    private fun ComposeUiTest.showCurve(
        original: Outline.Shape = curve(),
        lasso: Boolean = false,
        selected: Boolean = true,
    ): MutableState<WorkspaceState> {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        var initial = base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(original))) }
        if (lasso) initial = initial.toggleObjectLasso()
        if (selected) initial = if (lasso) initial.selectObjectsInLasso(listOf(
            InkSample(40f, 160f), InkSample(320f, 160f), InkSample(320f, 340f),
            InkSample(40f, 340f), InkSample(40f, 160f),
        )) else initial.selectObject(original.id)
        val state = mutableStateOf(initial)
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        return state
    }

    private fun shownCurve(state: MutableState<WorkspaceState>): Outline.Shape =
        state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().single()

    private fun ComposeUiTest.dragVertex(index: Int, dx: Float, dy: Float, touch: Boolean = false) {
        val node = onNodeWithTag(curveVertexTag("curve", index))
        if (touch) node.performTouchInput { down(center); moveBy(Offset(dx, dy), delayMillis = 80); up() }
        else node.performMouseInput {
            val start = center
            updatePointerTo(start); press(); moveTo(start + Offset(dx, dy), delayMillis = 80); release()
        }
    }

    @Test
    fun aCurvesToolkitAddsAVertexAndTheNewHandleDrags() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        val original = shownCurve(state)
        assertEquals(3, original.vertices().size)
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertIsDisplayed().performClick().assertIsSelected()
        assertEquals(3, shownCurve(state).vertices().size, "The toolkit must wait for a location")
        onNodeWithTag(curveInsertionTag(0)).assertIsDisplayed()
        onNodeWithTag(curveInsertionTag(1)).assertIsDisplayed().performTouchInput { click(center) }
        waitForIdle()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        val inserted = shownCurve(state)
        assertEquals(4, inserted.vertices().size)
        assertEquals(original.segments.first(), inserted.segments.first(), "the other span must remain untouched")
        val added = inserted.vertices()[2]
        assertEquals(240f, added.x, .01f)
        onNodeWithTag(ShapeObjectTags.Fill).assertDoesNotExist()
        dragVertex(2, 20f, 40f)
        val moved = shownCurve(state).vertices()[2]
        assertEquals(added.x + 20f, moved.x, 1f)
        assertEquals(added.y + 40f, moved.y, 1f)
        assertEquals(2, state.value.structuralUndo.size, "one insertion and one vertex drag")
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        assertEquals(inserted, shownCurve(state))
    }

    @Test
    fun aLassoSelectedCurveUsesVertexHandlesToo() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve(lasso = true)
        assertEquals(setOf("curve"), state.value.selectedObjectIds)
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).performClick()
        waitForIdle()
        dragVertex(2, 30f, 50f, touch = true)
        val peak = shownCurve(state).vertices()[2]
        assertEquals(210f, peak.x, 1f)
        assertEquals(230f, peak.y, 1f)
        assertEquals(4, shownCurve(state).vertices().size)
        assertEquals(2, state.value.structuralUndo.size)
        repeat(4) { onNodeWithTag(WorkspaceTestTags.objectCorner("curve", it)).assertDoesNotExist() }
    }

    @Test
    fun curveKeepAndKeepOffActionsHaveAccessibleLabelsAndTouchTargets() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        showCurve()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertIsDisplayed().assertContentDescriptionEquals("Add vertex")
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).assertIsDisplayed().assertContentDescriptionEquals("Remove vertex")
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        repeat(3) { onNodeWithTag(curveVertexTag("curve", it)).assertIsDisplayed() }
        repeat(4) { onNodeWithTag(WorkspaceTestTags.objectCorner("curve", it)).assertDoesNotExist() }
    }

    @Test
    fun removeVertexWaitsForAChosenPointAndKeepsTwoPointsEditable() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).performClick().assertIsSelected()
        assertEquals(3, shownCurve(state).vertices().size)
        repeat(3) { onNodeWithTag(curveRemovalTag(it)).assertIsDisplayed() }
        onNodeWithTag(curveRemovalTag(1)).performTouchInput { click(center) }
        waitForIdle()
        assertEquals(listOf(60f to 320f, 300f to 320f), shownCurve(state).vertices().map { it.x to it.y })
        onNodeWithTag(CURVE_REMOVAL_OVERLAY_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).performClick()
        waitForIdle()
        assertEquals(3, shownCurve(state).vertices().size)
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).assertIsDisplayed()
    }

    @Test
    fun aCurveThatBecomesTwoPointsCancelsPendingRemoval() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).performClick()
        onNodeWithTag(curveRemovalTag(1)).assertIsDisplayed()
        runOnIdle {
            val current = shownCurve(state)
            state.value = state.value.removeShapeVertex(current.id, current.segments[1].id, false)
        }
        waitForIdle()
        assertEquals(2, shownCurve(state).vertices().size)
        onNodeWithTag(CURVE_REMOVAL_OVERLAY_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertIsNotSelected()
    }

    @Test
    fun aLassoSelectedCurveCanRemoveAnEndpoint() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve(lasso = true)
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).performClick()
        onNodeWithTag(curveRemovalTag(2)).performTouchInput { click(center) }
        waitForIdle()
        assertEquals(listOf(60f to 320f, 180f to 180f), shownCurve(state).vertices().map { it.x to it.y })
    }

    @Test
    fun addAndRemoveVertexModesAreMutuallyExclusiveAndCanBeCancelled() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val original = curve()
        val state = showCurve(original)
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).assertIsDisplayed()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).performClick().assertIsSelected()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertIsNotSelected()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        onNodeWithTag(curveRemovalTag(1)).assertIsDisplayed()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).performClick()
        waitForIdle()
        onNodeWithTag(CURVE_REMOVAL_OVERLAY_TAG).assertDoesNotExist()
        assertEquals(original, shownCurve(state))
        assertTrue(state.value.structuralUndo.isEmpty())
    }

    @Test
    fun pressingAddVertexAgainCancelsWithoutEditingTheCurve() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val original = curve()
        val state = showCurve(original)
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).assertIsDisplayed()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        waitForIdle()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertIsNotSelected()
        assertEquals(original, shownCurve(state))
    }

    @Test
    fun tappingAwayCancelsGhostPointsWithoutOpeningText() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val original = curve()
        val state = showCurve(original)
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).performTouchInput { click(Offset(600f, 500f)) }
        waitForIdle()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        assertEquals(listOf(original), state.value.selectedPage!!.document.outlines)
        assertEquals(setOf(original.id), state.value.selectedObjectIds)
        assertNull(state.value.focusedTextOutlineId)
    }

    @Test
    fun ghostPointsAreRefreshedAfterEachChosenInsertion() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).performClick()
        waitForIdle()
        val afterFirst = shownCurve(state)
        assertEquals(4, afterFirst.vertices().size)
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).assertIsDisplayed()
        onNodeWithTag(curveInsertionTag(1)).assertIsDisplayed()
        onNodeWithTag(curveInsertionTag(2)).assertIsDisplayed().performClick()
        waitForIdle()
        val result = shownCurve(state)
        assertEquals(5, result.vertices().size)
        assertEquals(afterFirst.segments.take(2), result.segments.take(2))
    }

    @Test
    fun pickingAnotherToolCancelsPendingVertexInsertion() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).assertIsDisplayed()
        onNodeWithTag(DrawRibbonTags.PenTool).performClick()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        assertEquals(3, shownCurve(state).vertices().size)
    }

    @Test
    fun switchingPagesDiscardsPendingVertexInsertion() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).assertIsDisplayed()
        val pageId = state.value.selectedPageId
        runOnIdle { state.value = state.value.selectPage("homework-1") }
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        runOnIdle { state.value = state.value.selectPage(pageId).selectObject("curve") }
        onNodeWithTag(curveInsertionTag(0)).assertDoesNotExist()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertIsNotSelected()
        assertEquals(3, shownCurve(state).vertices().size)
    }

    @Test
    fun stretchingBothCurveEndsKeepsTheBendRounded() = stretchBothEnds(lasso = false)

    @Test
    fun stretchingBothLassoSelectedCurveEndsKeepsTheBendRounded() = stretchBothEnds(lasso = true)

    private fun stretchBothEnds(lasso: Boolean) = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve(lasso = lasso)
        dragVertex(0, -20f, 260f, touch = lasso)
        dragVertex(2, 60f, 260f, touch = lasso)
        val shape = shownCurve(state)
        val vertices = shape.vertices()
        assertEquals(3, vertices.size)
        assertEquals(180f, vertices[1].x, 1f)
        assertEquals(180f, vertices[1].y, 1f)
        assertEquals(40f, vertices.first().x, 1f)
        assertEquals(580f, vertices.first().y, 1f)
        assertEquals(360f, vertices.last().x, 1f)
        assertEquals(580f, vertices.last().y, 1f)
        shape.segments.forEach { span ->
            val c = span.cubic!!
            val midY = (span.y1 + 3f * c.y1 + 3f * c.y2 + span.y2) / 8f
            assertTrue(midY < 300f, "Stretched curve flattened into the triangle's side")
        }
        assertEquals(2, state.value.structuralUndo.size)
    }

    @Test
    fun aVertexDragPreviewsBeforeReleaseAndCommitsOneUndoStep() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val original = curve()
        val state = showCurve(original)
        onNodeWithTag(curveVertexTag("curve", 0)).performMouseInput {
            val start = center
            updatePointerTo(start); press(); moveTo(start + Offset(20f, 80f), delayMillis = 80)
        }
        runOnIdle {
            assertEquals(original, shownCurve(state), "unfinished vertex motion must stay a preview")
            assertTrue(state.value.structuralUndo.isEmpty())
        }
        onNodeWithTag(curveVertexTag("curve", 0)).performMouseInput { release() }
        assertEquals(1, state.value.structuralUndo.size)
        assertEquals(80f, shownCurve(state).vertices()[0].x, 1f)
        assertEquals(400f, shownCurve(state).vertices()[0].y, 1f)
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        assertEquals(original, shownCurve(state))
    }

    @Test
    fun separateFramesKeepVertexDragCoordinatesStableAndCommitOnlyOnRelease() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val original = curve()
        val state = showCurve(original)
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        val canvasOrigin = canvas.fetchSemanticsNode().boundsInRoot.topLeft
        val handleOrigin = onNodeWithTag(curveVertexTag("curve", 0)).fetchSemanticsNode().boundsInRoot.center
        val start = handleOrigin - canvasOrigin
        canvas.performMouseInput { updatePointerTo(start); press() }
        for (step in 1..3) {
            val delta = Offset(step * 20f, step * 30f)
            canvas.performMouseInput { moveTo(start + delta, delayMillis = 40) }
            runOnIdle {
                assertEquals(original, shownCurve(state), "motion in frame $step must stay a preview")
                assertTrue(state.value.structuralUndo.isEmpty())
            }
            val moved = onNodeWithTag(curveVertexTag("curve", 0)).fetchSemanticsNode().boundsInRoot.center
            assertEquals(handleOrigin.x + delta.x, moved.x, 1f)
            assertEquals(handleOrigin.y + delta.y, moved.y, 1f)
        }
        canvas.performMouseInput { release() }
        val vertex = shownCurve(state).vertices()[0]
        assertEquals(120f, vertex.x, 1f)
        assertEquals(410f, vertex.y, 1f)
        assertEquals(1, state.value.structuralUndo.size)
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        assertEquals(original, shownCurve(state))
    }

    @Test
    fun aSecondaryMouseDragDoesNotMoveACurveVertexOrAddHistory() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val original = curve()
        val state = showCurve(original)
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        val start = onNodeWithTag(curveVertexTag("curve", 0)).fetchSemanticsNode().boundsInRoot.center -
            canvas.fetchSemanticsNode().boundsInRoot.topLeft
        canvas.performMouseInput {
            updatePointerTo(start); press(MouseButton.Secondary)
            moveTo(start + Offset(50f, 60f), delayMillis = 80)
            release(MouseButton.Secondary)
        }
        waitForIdle()
        assertEquals(original, shownCurve(state))
        assertTrue(state.value.structuralUndo.isEmpty())
    }

    @Test
    fun aToolChangeCancelsAnUnfinishedVertexDragWithoutHistory() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val original = curve()
        val state = showCurve(original)
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        val start = onNodeWithTag(curveVertexTag("curve", 0)).fetchSemanticsNode().boundsInRoot.center -
            canvas.fetchSemanticsNode().boundsInRoot.topLeft
        canvas.performTouchInput { down(start); moveTo(start + Offset(50f, 60f), delayMillis = 80) }
        runOnIdle { assertEquals(original, shownCurve(state)); assertTrue(state.value.structuralUndo.isEmpty()) }
        // Desktop's SkikoInputDispatcher.enqueueCancel is a stub: cancel() only clears injector
        // state. Switching tools disposes the handles and actually cancels their pointer handler.
        // Invoke the action directly: performClick injects another touch into the unfinished drag.
        onNodeWithTag(DrawRibbonTags.PenTool).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        runOnIdle { assertEquals(InkTool.Pen, state.value.inkTool) }
        repeat(3) { onNodeWithTag(curveVertexTag("curve", it)).assertDoesNotExist() }
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertDoesNotExist()
        canvas.performTouchInput { cancel() }
        waitForIdle()
        assertEquals(original, shownCurve(state))
        assertTrue(state.value.structuralUndo.isEmpty())
        runOnIdle { state.value = state.value.selectPointer().selectObject("curve") }
        val restored = onNodeWithTag(curveVertexTag("curve", 0)).fetchSemanticsNode().boundsInRoot.center -
            canvas.fetchSemanticsNode().boundsInRoot.topLeft
        assertEquals(start.x, restored.x, 1f)
        assertEquals(start.y, restored.y, 1f)
        assertEquals(original, shownCurve(state))
        assertTrue(state.value.structuralUndo.isEmpty())
    }

    @Test
    fun aLockedCurveHasNoVertexActionsOrHandles() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        showCurve(curve().copy(lockGroup = "locked"))
        onNodeWithTag(WorkspaceTestTags.ObjectToolkit).assertIsDisplayed()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).assertDoesNotExist()
        repeat(3) { onNodeWithTag(curveVertexTag("curve", it)).assertDoesNotExist() }
    }

    @Test
    fun lockingACurveCancelsItsPendingVertexTargets() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).performClick()
        onNodeWithTag(curveInsertionTag(0)).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.ObjectLock).performClick()
        onNodeWithTag(CURVE_INSERTION_OVERLAY_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertDoesNotExist()
        assertNotNull(shownCurve(state).lockGroup)
    }

    @Test
    fun mixedSelectionsKeepTheirSharedFrameAndHideSingleCurveActions() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve()
        runOnIdle {
            val rectangle = Outline.Shape(id = "rectangle", kind = ShapeKind.Rectangle,
                segments = seedSegments(ShapeKind.Rectangle, 400f, 200f, 500f, 320f) { "rectangle-edge" })
                .withRecomputedBounds()
            state.value = state.value.updatePage(state.value.selectedPageId) {
                it.copy(document = it.document.copy(outlines = it.document.outlines + rectangle))
            }.selectObjectsInRect(40f, 160f, 520f, 340f)
        }
        onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).assertIsDisplayed()
        repeat(4) { onNodeWithTag(WorkspaceTestTags.groupCorner(it)).assertIsDisplayed() }
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertDoesNotExist()
        onNodeWithTag(OBJECT_REMOVE_VERTEX_TAG).assertDoesNotExist()
        repeat(3) { onNodeWithTag(curveVertexTag("curve", it)).assertDoesNotExist() }
    }

    @Test
    fun theBasicShapePickerCreatesACurveWithThreeEditableVertices() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = showCurve(selected = false)
        runOnIdle { state.value = state.value.updatePage(state.value.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList())) } }
        onNodeWithTag(DrawRibbonTags.ShapeKit).performClick()
        onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Curve)).assertIsDisplayed().performClick().assertIsSelected()
        onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Curve)).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput {
            updatePointerTo(Offset(60f, 180f)); press(); moveTo(Offset(300f, 320f), delayMillis = 80); release()
        }
        val shape = shownCurve(state)
        assertEquals(ShapeKind.Curve, shape.kind)
        assertEquals(3, shape.vertices().size)
        assertNull(shape.fillArgb)
        onNodeWithTag(OBJECT_ADD_VERTEX_TAG).assertIsDisplayed()
    }
}
