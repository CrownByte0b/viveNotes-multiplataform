package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.*
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Outline
import com.vivenotes.model.Block
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkSelectionInteractionTest {
    private fun workspace(): WorkspaceState {
        val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        val ink = InkPageReader.read(initial.selectedPageId, listOf(selectionLine(pageId = initial.selectedPageId)), emptyList(), emptyList())
        return initial.updatePage(initial.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList()), ink = ink) }
    }

    @Test
    fun clickSelectsInkAndToolbarDeleteAndUndoRedoKeepWorking() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val state = mutableStateOf(workspace())
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(INK_LAYER_TAG).performMouseInput { click(Offset(40f, 200f)) }
        onNodeWithTag(INK_SELECTION_FRAME_TAG).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.ObjectDelete).performClick()
        runOnIdle {
            assertNull(state.value.selectedInk)
            assertTrue(state.value.selectedPage!!.ink!!.desktopGeometry().isEmpty)
            assertEquals(1, state.value.pendingInkEdits.size)
        }
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        runOnIdle { assertEquals(1, state.value.selectedPage!!.ink!!.desktopGeometry().projections.size) }
        onNodeWithTag(WorkspaceTestTags.StructuralRedo).performClick()
        runOnIdle { assertTrue(state.value.selectedPage!!.ink!!.desktopGeometry().isEmpty) }
    }

    @Test
    fun marqueeIncludesInkAndDraggingItsFrameCommitsOneMoveWithUndo() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val state = mutableStateOf(workspace())
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(DrawRibbonTags.ObjectLasso).performClick()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(0f, 180f)); press(); moveTo(Offset(120f, 230f), delayMillis = 80); release()
        }
        onNodeWithTag(INK_SELECTION_FRAME_TAG).assertIsDisplayed()
        val before = state.value.selectedInk!!.bounds
        onNodeWithTag(INK_SELECTION_FRAME_TAG).performMouseInput {
            updatePointerTo(center); press(); moveBy(Offset(40f, 30f), delayMillis = 80); release()
        }
        waitUntil(timeoutMillis = 5000) { state.value.pendingInkEdits.isNotEmpty() }
        runOnIdle {
            assertEquals(1, state.value.structuralUndo.size)
            assertEquals(1, state.value.pendingInkEdits.size)
            val row = (state.value.pendingInkEdits.single().edit as InkEdit.Move).row
            assertEquals(40f, row.dxDp, 0.1f); assertEquals(30f, row.dyDp, 0.1f)
            assertEquals(before.left + 40f, state.value.selectedInk!!.bounds.left, 0.1f)
        }
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        runOnIdle { assertEquals(before.left, state.value.selectedPage!!.ink!!.desktopGeometry().projections.single().pageBounds!!.left, 0.1f) }
    }

    @Test
    fun directClickAndFirstDragSelectAndMoveInkAtScaledDensityAndZoom() {
        for ((density, zoom) in listOf(1f to 1f, 1.5f to 1.25f)) runDesktopComposeUiTest(width = 1700, height = 1100) {
            val state = mutableStateOf(workspace())
            setContent { CompositionLocalProvider(LocalDensity provides Density(density)) {
                ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }, viewSettings = ViewSettings(zoom = zoom)) }
            } }
            onNodeWithTag(INK_LAYER_TAG).performMouseInput {
                updatePointerTo(Offset(40f * density * zoom, 200f * density * zoom)); press()
                moveTo(Offset(80f * density * zoom, 230f * density * zoom), delayMillis = 80); release()
            }
            waitUntil(timeoutMillis = 5000) { state.value.pendingInkEdits.isNotEmpty() }
            onNodeWithTag(INK_SELECTION_FRAME_TAG).assertIsDisplayed()
            runOnIdle {
                val row = (state.value.pendingInkEdits.single().edit as InkEdit.Move).row
                assertEquals(40f, row.dxDp, 0.1f); assertEquals(30f, row.dyDp, 0.1f)
            }
            onRoot().performKeyInput { pressKey(Key.Delete) }
            runOnIdle { assertTrue(state.value.selectedPage!!.ink!!.desktopGeometry().isEmpty) }
        }
    }

    @Test
    fun mixedInkAndTextUseOneFrameWithoutTextOnlyDragOrResizeControls() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val initial = workspace()
        val text = Outline.Text(id = "mixed-text", x = 150f, y = 190f, width = 150f, blocks = listOf(Block.of("Selected text")))
        val state = mutableStateOf(initial.updatePage(initial.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(text))) })
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(DrawRibbonTags.ObjectLasso).performClick()
        onNodeWithTag(INK_LAYER_TAG).performMouseInput {
            updatePointerTo(Offset(0f, 170f)); press(); moveTo(Offset(320f, 360f), delayMillis = 80); release()
        }
        onNodeWithTag(INK_SELECTION_FRAME_TAG).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.textGrip(text.id)).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.textResizeHandle(text.id)).assertDoesNotExist()
        onNodeWithTag(INK_SELECTION_FRAME_TAG).performMouseInput {
            updatePointerTo(center); press(); moveBy(Offset(20f, 15f), delayMillis = 40)
        }
        runOnIdle { assertTrue(state.value.pendingInkEdits.isEmpty(), "held movement is a preview") }
        onNodeWithTag(INK_SELECTION_FRAME_TAG).performMouseInput { moveBy(Offset(20f, 15f), delayMillis = 40); release() }
        waitUntil(timeoutMillis = 5000) { state.value.pendingInkEdits.isNotEmpty() }
        runOnIdle {
            val move = (state.value.pendingInkEdits.single().edit as InkEdit.Move).row
            assertEquals(40f, move.dxDp, 0.5f)
            assertEquals(30f, move.dyDp, 0.5f)
            assertEquals(text.x + move.dxDp, state.value.selectedPage!!.document.outlines.single().x)
            assertEquals(text.y + move.dyDp, state.value.selectedPage!!.document.outlines.single().y)
            assertEquals(1, state.value.structuralUndo.size)
        }
    }

    @Test
    fun movingPreviewHidesOnlyTheSelectedPieceAndPreservesItsSiblingPixels() = runDesktopComposeUiTest(width = 160, height = 260) {
        val (_, page) = splitSelectionPage()
        val selected = page.geometry!!.selectInRect(page, 0f, 180f, 55f, 220f)!!
        setContent {
            Box(Modifier.size(160.dp, 260.dp).background(Color.White).testTag("preview-frame")) {
                InkLayer(page, DpSize(160.dp, 260.dp), Color.Black, selection = selected, selectionOffset = Offset(0f, 30f))
            }
        }
        val pixels = onNodeWithTag("preview-frame").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[30, 200])
        assertEquals(Color(0xffe01b24), pixels[30, 230])
        assertEquals(Color(0xffe01b24), pixels[90, 200])
        assertEquals(Color.White, pixels[90, 230])
    }
}
