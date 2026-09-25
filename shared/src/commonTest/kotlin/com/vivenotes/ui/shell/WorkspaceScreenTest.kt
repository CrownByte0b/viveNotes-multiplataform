package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class WorkspaceScreenTest {

    @Test
    fun largeWindowShowsThreePaneWorkspace() = runComposeUiTest {
        setWorkspace(width = 1400.dp)

        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.PagePane).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Homework 1")
    }

    @Test
    fun compactWindowKeepsCanvasAndCollapsesNavigationPanes() = runComposeUiTest {
        setWorkspace(width = 700.dp)

        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.PagePane).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).assertIsDisplayed()
    }

    @Test
    fun selectingSectionOpensItsFirstPage() = runComposeUiTest {
        var observed = WorkspaceState.demo()
        setWorkspace(width = 1400.dp) { observed = it }

        onNodeWithTag(WorkspaceTestTags.section("chapter-2")).performClick()

        onNodeWithTag(WorkspaceTestTags.page("sequences")).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Sequences")
        runOnIdle {
            assertEquals("chapter-2", observed.selectedSectionId)
            assertEquals("sequences", observed.selectedPageId)
        }
    }

    @Test
    fun ribbonTabChangesVisibleCommands() = runComposeUiTest {
        var observed = WorkspaceState.demo()
        setWorkspace(width = 1400.dp) { observed = it }

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Draw)).performClick()

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Draw)).assertIsSelected()
        onNodeWithText("Pen").assertIsDisplayed()
        runOnIdle { assertEquals(RibbonTab.Draw, observed.activeTab) }
    }

    @Test
    fun addPageAndEditPlaceholderContent() = runComposeUiTest {
        var observed = WorkspaceState.demo()
        setWorkspace(width = 1400.dp) { observed = it }

        onNodeWithTag(WorkspaceTestTags.AddPage).performClick()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).performTextReplacement("Project plan")
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("First milestone")

        onNodeWithTag(WorkspaceTestTags.page("chapter-1-draft-3")).assertExists()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Project plan")
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertTextContains("First milestone")
        runOnIdle {
            assertEquals("Project plan", observed.selectedPage?.title)
            assertEquals("First milestone", observed.selectedPage?.body)
            assertEquals("First milestone", observed.selectedPage?.preview)
        }
    }

    @Test
    fun navigationToggleUpdatesVisibilityState() = runComposeUiTest {
        var observed = WorkspaceState.demo()
        setWorkspace(width = 1400.dp) { observed = it }

        onNodeWithTag(WorkspaceTestTags.NavigationToggle).performClick()

        runOnIdle { assertFalse(observed.navigationVisible) }
    }

    private fun ComposeUiTest.setWorkspace(
        width: Dp,
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState.demo()) }
            ViveNotesTheme(darkTheme = true) {
                Box(
                    Modifier
                        .requiredSize(width, 900.dp)
                        .wrapContentSize(Alignment.TopStart),
                ) {
                    WorkspaceScreen(
                        state = state,
                        onStateChange = {
                            state = it
                            onStateChange(it)
                        },
                    )
                }
            }
        }
    }
}
