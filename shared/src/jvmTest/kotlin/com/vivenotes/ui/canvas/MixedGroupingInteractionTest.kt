package com.vivenotes.ui.canvas

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.selectionLine
import com.vivenotes.model.*
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MixedGroupingInteractionTest {
    private fun workspace(ink: Boolean): WorkspaceState {
        val state = WorkspaceState.demo()
        val page = if (ink) InkPageReader.read(state.selectedPageId,
            listOf(selectionLine("stroke", state.selectedPageId)), emptyList(), emptyList()) else null
        return state.updatePage(state.selectedPageId) { it.copy(ink = page, document = PageDoc(outlines = listOf(
            Outline.Shape("shape", x = 150f, y = 200f, width = 60f, height = 60f),
            newTable(2, 2, x = 280f, y = 200f, columnWidth = 60f, inkOnly = true).copy(id = "table"),
        ))) }.selectObjectsInRect(0f, 180f, 500f, 380f)
    }

    @Test
    fun tablesAndShapesCanGroupReselectDragAndUngroupThroughTheirToolkit() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val state = mutableStateOf(workspace(ink = false))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Group").performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { state.value.selectionGrouped }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Ungroup")
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(600f, 500f)) }
        onNodeWithTag(WorkspaceTestTags.primeObject("shape")).performMouseInput { click(center) }
        runOnIdle { assertEquals(setOf("shape", "table"), state.value.selectedObjectIds) }
        onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).performMouseInput {
            updatePointerTo(center); press(); moveBy(Offset(35f, 25f), delayMillis = 80); release()
        }
        runOnIdle {
            assertTrue(state.value.selectedPrimeObjects.all { it.y > 200f })
            assertEquals(2, state.value.structuralUndo.size)
        }
        onNodeWithTag(WorkspaceTestTags.InkGroup).performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { !state.value.selectionGrouped }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(600f, 500f)) }
        onNodeWithTag(WorkspaceTestTags.primeObject("shape")).performMouseInput { click(center) }
        runOnIdle { assertEquals(setOf("shape"), state.value.selectedObjectIds) }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertDoesNotExist()
    }

    @Test
    fun aMixedInkTableShapeGroupMovesTogetherAndUndoRedoRestoresGrouping() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val state = mutableStateOf(workspace(ink = true))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Group").performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { state.value.selectionGrouped }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(600f, 500f)) }
        onNodeWithTag(WorkspaceTestTags.primeObject("table")).performMouseInput { click(center) }
        runOnIdle {
            assertEquals(setOf("shape", "table"), state.value.selectedObjectIds)
            assertEquals(setOf("stroke"), state.value.selectedInk!!.targetIds)
        }
        onNodeWithTag(INK_SELECTION_FRAME_TAG).performMouseInput {
            updatePointerTo(center); press(); moveBy(Offset(30f, 25f), delayMillis = 80); release()
        }
        waitUntil(timeoutMillis = 5000) { state.value.structuralUndo.size == 2 }
        runOnIdle {
            assertTrue(state.value.selectedPrimeObjects.all { it.y > 200f })
            assertTrue(state.value.selectedInk!!.bounds.top > 200f)
        }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Ungroup").performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { !state.value.selectionGrouped }
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performMouseInput { click(center) }
        onNodeWithTag(WorkspaceTestTags.primeObject("table")).performMouseInput { click(center) }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Ungroup")
        onNodeWithTag(WorkspaceTestTags.StructuralRedo).performMouseInput { click(center) }
        onNodeWithTag(WorkspaceTestTags.primeObject("table")).performMouseInput { click(center) }
        runOnIdle { assertNull(state.value.selectedInk) }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertDoesNotExist()
    }

    @Test
    fun textOnlySelectionsHaveOneToolkitWithGroupCopyAndDelete() = runDesktopComposeUiTest(width = 1500, height = 900) {
        val base = workspace(ink = false)
        val state = mutableStateOf(base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(
            Outline.Text("one", x = 40f, y = 200f, width = 140f, blocks = listOf(Block.of("First"))),
            Outline.Text("two", x = 260f, y = 200f, width = 140f, blocks = listOf(Block.of("Second"))),
        ))) }.selectObjectsInRect(0f, 180f, 500f, 400f))
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onAllNodesWithTag(WorkspaceTestTags.ObjectCopy).assertCountEquals(1)
        onAllNodesWithTag(WorkspaceTestTags.ObjectDelete).assertCountEquals(1)
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Group").performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5000) { state.value.selectionGrouped }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Ungroup")
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(600f, 500f)) }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { click(Offset(10f, 10f)) }
        waitUntil(timeoutMillis = 5000) { state.value.selectedTextOutlineIds == setOf("one", "two") }
        onNodeWithTag(WorkspaceTestTags.InkGroup).assertTextEquals("Ungroup")
    }
}
