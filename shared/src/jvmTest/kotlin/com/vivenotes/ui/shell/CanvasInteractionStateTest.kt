package com.vivenotes.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import com.vivenotes.model.PageSpace
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.Ruler
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CanvasInteractionStateTest {
    @Test
    fun unrelatedWorkspaceUpdatesPreserveTheCanvasInteractionValues() = runDesktopComposeUiTest {
        val workspace = mutableStateOf(WorkspaceState.demo())
        lateinit var interaction: CanvasInteractionState
        setContent { interaction = rememberCanvasInteractionState(workspace.value.selectedPage, workspace.value, 1f) }
        val editedTitle = TextFieldValue("Draft title", TextRange(3))
        runOnIdle {
            interaction.viewportPx = IntSize(900, 700)
            interaction.pastePoint = Offset(20f, 100f)
            interaction.inkDragOffset = Offset(8f, 4f)
            interaction.inkMoveBusy = true
            interaction.inkToolkitBoundsInView = Rect(1f, 2f, 30f, 40f)
            interaction.titleValue = editedTitle
            interaction.panActive = true
            interaction.reachedX = 4
            workspace.value = workspace.value.copy(activeTab = RibbonTab.Draw)
        }
        runOnIdle {
            assertEquals(IntSize(900, 700), interaction.viewportPx)
            assertEquals(Offset(20f, 100f), interaction.pastePoint)
            assertEquals(Offset(8f, 4f), interaction.inkDragOffset)
            assertTrue(interaction.inkMoveBusy)
            assertEquals(Rect(1f, 2f, 30f, 40f), interaction.inkToolkitBoundsInView)
            assertEquals(editedTitle, interaction.titleValue)
            assertTrue(interaction.panActive)
            assertEquals(4, interaction.reachedX)
        }
    }

    @Test
    fun zoomRulerAndPageChangesKeepTheirIndependentResetKeys() = runDesktopComposeUiTest {
        val workspace = mutableStateOf(WorkspaceState.demo())
        val zoom = mutableStateOf(1f)
        lateinit var interaction: CanvasInteractionState
        setContent { interaction = rememberCanvasInteractionState(workspace.value.selectedPage, workspace.value, zoom.value) }
        val ruler = Ruler(10f, 20f, 0f, workspace.value.inkSettings.ruler.kind, 200f)
        runOnIdle {
            interaction.viewportPx = IntSize(900, 700)
            interaction.lassoPath = listOf(Offset(10f, 20f))
            interaction.inkDragOffset = Offset(8f, 4f)
            interaction.spaceDraft = SpaceCut(PageSpace.Axis.Vertical, 10f, 20f)
            interaction.pastePoint = Offset(20f, 100f)
            interaction.rulerPlacement = ruler
            interaction.panActive = true
            interaction.reachedY = 5
            zoom.value = 2f
        }
        runOnIdle {
            assertTrue(interaction.lassoPath.isEmpty())
            assertEquals(Offset.Zero, interaction.inkDragOffset)
            assertNull(interaction.spaceDraft)
            assertEquals(Offset(20f, 100f), interaction.pastePoint)
            assertEquals(ruler, interaction.rulerPlacement)
            assertTrue(interaction.panActive)
            assertEquals(5, interaction.reachedY)
            workspace.value = workspace.value.copy(rulerVisible = !workspace.value.rulerVisible)
        }
        runOnIdle {
            assertNull(interaction.rulerPlacement)
            assertEquals(Offset(20f, 100f), interaction.pastePoint)
            workspace.value = workspace.value.selectPage("lecture-notes")
        }
        runOnIdle {
            assertEquals(IntSize(900, 700), interaction.viewportPx)
            assertNull(interaction.pastePoint)
            assertEquals(false, interaction.panActive)
            assertEquals(0, interaction.reachedY)
            assertEquals(workspace.value.selectedPage!!.title, interaction.titleValue.text)
        }
    }
}
