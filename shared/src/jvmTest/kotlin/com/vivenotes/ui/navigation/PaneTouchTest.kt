package com.vivenotes.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.ribbon.settings.HardwareTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's pane gestures: swipe a pane away, long-press a row for its menu. */
@OptIn(ExperimentalTestApi::class)
class PaneTouchTest {

    @Test
    fun aFingerSwipesTheNotebookPaneAwayButAMouseDragDoesNot() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.NotebookPane).performMouseInput {
            moveTo(centerRight - Offset(10f, 0f)); press(); moveTo(centerLeft + Offset(10f, 0f)); release(); exit()
        }
        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertExists()

        onNodeWithTag(WorkspaceTestTags.NotebookPane).performTouchInput { swipeLeft() }
        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.PagePane).assertExists()
        runOnIdle { assertEquals(false to true, observed.navigationVisible to observed.pageListVisible) }
    }

    @Test
    fun swipingThePageListAwayHidesBothAndTheNavigationButtonBringsThemBack() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace(WorkspaceState.demo())

            onNodeWithTag(WorkspaceTestTags.PagePane).performTouchInput { swipeLeft() }
            onNodeWithTag(WorkspaceTestTags.PagePane).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()

            onNodeWithTag(WorkspaceTestTags.NavigationToggle).performClick()
            onNodeWithTag(WorkspaceTestTags.PagePane).assertExists()
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertExists()
        }

    @Test
    fun holdingAFingerOnAPageOpensItsMenuAndATapOpensThePage() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(observed) { observed = it }
        val other = observed.selectedSection!!.pages.first { it.id != observed.selectedPageId }.id

        onNodeWithTag(WorkspaceTestTags.page(other)).performTouchInput { click() }
        onNodeWithTag(NavigationTestTags.Rename).assertDoesNotExist()
        runOnIdle { assertEquals(other, observed.selectedPageId) }

        onNodeWithTag(WorkspaceTestTags.page(observed.selectedSection!!.pages.first().id)).performTouchInput { longClick() }
        onNodeWithTag(NavigationTestTags.Rename).assertExists()
        onNodeWithTag(NavigationTestTags.Delete).assertExists()
        // The hold opened the menu; it did not also open the page.
        runOnIdle { assertEquals(other, observed.selectedPageId) }
    }

    @Test
    fun aFingerSwipesADockedPaneAwayToTheRight() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings))
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()

        onNodeWithTag(ToolPaneTags.Pane).performTouchInput { swipeRight() }
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, onStateChange: (WorkspaceState) -> Unit = {}) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
            }
        }
    }
}
