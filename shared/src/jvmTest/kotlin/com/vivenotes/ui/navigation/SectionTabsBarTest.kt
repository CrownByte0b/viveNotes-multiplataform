package com.vivenotes.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The horizontal Tabs Layout: the notebook pane's choices as a chooser and a strip of section tabs. */
@OptIn(ExperimentalTestApi::class)
class SectionTabsBarTest {

    @Test
    fun theChooserOpensAnotherNotebookAtItsFirstSection() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(NavigationTestTags.NotebookChooser).assertTextContains("Calculus")

        onNodeWithTag(NavigationTestTags.NotebookChooser).performClick()
        onNodeWithTag(NavigationTestTags.notebookChoice("biology")).performClick()
        runOnIdle {
            assertEquals("biology", observed.selectedNotebookId)
            assertEquals("cell-biology", observed.selectedSectionId)
        }
        onNodeWithTag(NavigationTestTags.NotebookChooser).assertTextContains("Biology")
        onNodeWithTag(NavigationTestTags.sectionTab("cell-biology")).assertIsSelected()
        onNodeWithTag(NavigationTestTags.sectionTab("chapter-1")).assertDoesNotExist()
    }

    @Test
    fun aSectionTabHasTheSectionMenu() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(NavigationTestTags.sectionTab("chapter-2")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Series")
        onNodeWithTag(NavigationTestTags.ConfirmRename).performClick()
        runOnIdle { assertEquals("Series", observed.notebooks.first().sections[1].name) }
        onNodeWithTag(NavigationTestTags.sectionTab("chapter-2")).assertTextContains("Series")
    }

    /** The header's navigation toggle hides the strip the way it hides the notebook pane. */
    @Test
    fun theNavigationToggleHidesTheStrip() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        onNodeWithTag(NavigationTestTags.SectionTabs).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.NavigationToggle).performClick()
        onNodeWithTag(NavigationTestTags.SectionTabs).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.PagePane).assertIsDisplayed()
    }

    private fun ComposeUiTest.setWorkspace(onStateChange: (WorkspaceState) -> Unit = {}) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState.demo()) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                    viewSettings = ViewSettings(tabsLayout = TabsLayout.Horizontal))
            }
        }
    }
}
