package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Outline
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CanvasLassoWorkerUiTest {
    @Test
    fun aLargePageLassoSelectsAndThenMovesItsExactResult() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val target = Outline.Shape("target", x = 300f, y = 350f, width = 80f, height = 80f)
        val base = WorkspaceState.demo()
        var state by mutableStateOf(base.updatePage(base.selectedPageId) { page ->
            page.copy(document = page.document.copy(outlines = listOf(target) + List(128) {
                Outline.Shape("far-$it", x = 2000f + it * 20f, y = 350f, width = 10f, height = 10f)
            }))
        }.clearCanvasFocus().copy(activeTab = RibbonTab.Draw).toggleObjectLasso())
        setContent { ViveNotesTheme { WorkspaceScreen(state, { command -> state = command(state) }) } }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
            moveTo(Offset(250f, 320f)); press()
            moveTo(Offset(450f, 320f)); moveTo(Offset(450f, 450f))
            moveTo(Offset(250f, 450f)); moveTo(Offset(250f, 320f)); release()
        }
        waitUntil(timeoutMillis = 5_000) { state.selectedObjectIds == setOf(target.id) }
        runOnIdle { assertEquals(setOf(target.id), state.selectedObjectIds) }
        onNodeWithTag(WorkspaceTestTags.primeObject(target.id)).performMouseInput {
            moveTo(Offset(30f, 30f)); press(); moveTo(Offset(70f, 60f)); release()
        }
        runOnIdle {
            assertTrue(state.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>()
                .first { it.id == target.id }.x > target.x)
        }
    }
}
