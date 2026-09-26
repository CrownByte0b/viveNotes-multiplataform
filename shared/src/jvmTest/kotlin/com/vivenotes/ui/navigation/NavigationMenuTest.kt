package com.vivenotes.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toPixelMap
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

/** Right-click menus on notebooks, sections and pages, and the dialogs behind them. */
@OptIn(ExperimentalTestApi::class)
class NavigationMenuTest {

    @Test
    fun notebookAndPageMenusWorkAtTheDefaultDisplayScale() = assertMenusAtScale(InterfaceSettings())

    @Test
    fun notebookAndPageMenusWorkAtTheMinimumScales() = assertMenusAtScale(
        InterfaceSettings(displayScale = 0.5f, uiScale = 0.5f, fontScale = 0.5f))

    @Test
    fun notebookAndPageMenusWorkWithEnlargedUiAndFonts() = assertMenusAtScale(
        InterfaceSettings(displayScale = 1.5f, uiScale = 1.5f, fontScale = 1.8f))

    @Test
    fun notebookAndPageMenusWorkAtTheMaximumDisplayScale() = assertMenusAtScale(
        InterfaceSettings(displayScale = 2.5f))

    private fun assertMenusAtScale(settings: InterfaceSettings) = runDesktopComposeUiTest(width = 3200, height = 1400) {
        var observed = WorkspaceState.demo()
        setWorkspace(settings) { observed = it }

        val notebook = onNodeWithTag(NavigationTestTags.notebook("calculus"))
        val clickX = notebook.fetchSemanticsNode().boundsInWindow.center.x
        notebook.performMouseInput { rightClick(center) }
        val menu = onNodeWithTag(NavigationTestTags.Rename)
        assertTrue(abs(menu.fetchSemanticsNode().boundsInWindow.left - clickX) < 30f)
        mainClock.advanceTimeBy(500)
        assertTrue(abs(menu.fetchSemanticsNode().boundsInWindow.left - clickX) < 30f)
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Analysis")
        onNodeWithTag(NavigationTestTags.ConfirmRename).performClick()
        runOnIdle { assertEquals("Analysis", observed.notebooks.first().name) }

        onNodeWithTag(WorkspaceTestTags.page("homework-1")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Exercises")
        onNodeWithTag(NavigationTestTags.ConfirmRename).performClick()
        runOnIdle { assertEquals("Exercises", observed.notebooks.first().sections.first().pages[1].title) }

        onNodeWithTag(WorkspaceTestTags.page("homework-1")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Delete).performClick()
        onNodeWithTag(NavigationTestTags.ConfirmDelete).performClick()
        runOnIdle { assertEquals(false, observed.notebooks.first().sections.first().pages.any { it.id == "homework-1" }) }

        onNodeWithTag(NavigationTestTags.notebook("calculus")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Delete).performClick()
        onNodeWithTag(NavigationTestTags.ConfirmDelete).performClick()
        runOnIdle { assertEquals("biology", observed.selectedNotebookId) }
    }

    @Test
    fun rightClickingASectionOpensItsMenuWithoutOpeningTheSection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace { observed = it }

            onNodeWithTag(WorkspaceTestTags.section("chapter-2")).performMouseInput { rightClick(center) }

            onNodeWithTag(NavigationTestTags.Rename).assertIsDisplayed().assertTextEquals("Rename section")
            onNodeWithTag(NavigationTestTags.Delete).assertIsDisplayed().assertTextEquals("Delete section")
            runOnIdle { assertEquals("chapter-1", observed.selectedSectionId) }

            // Escape closes it too, but the desktop window turns Escape into "back", outside this harness.
            onNodeWithText("Notebooks").performMouseInput { click(center) }
            onNodeWithTag(NavigationTestTags.Rename).assertDoesNotExist()
            runOnIdle { assertEquals("chapter-1", observed.selectedSectionId) }
        }

    @Test
    fun aSectionIsRenamedThroughTheDialog() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.section("chapter-2")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()

        onNodeWithTag(NavigationTestTags.Rename).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.Delete).assertDoesNotExist()
        onNodeWithText("Rename section").assertIsDisplayed()
        onNodeWithTag(NavigationTestTags.NameField).assertTextContains("Chapter 2")
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("   ")
        onNodeWithTag(NavigationTestTags.ConfirmRename).assertIsNotEnabled()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Series")
        onNodeWithTag(NavigationTestTags.ConfirmRename).performClick()

        onNodeWithTag(NavigationTestTags.NameField).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.section("chapter-2")).assertTextContains("Series")
        runOnIdle { assertEquals("Series", observed.notebooks.first().sections[1].name) }
    }

    @Test
    fun desktopDialogCoversTheWorkspaceAndEscapeClosesIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace()

        onNodeWithTag(NavigationTestTags.notebook("calculus")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()

        val backdrop = onNodeWithTag(NavigationTestTags.Backdrop).fetchSemanticsNode().boundsInRoot
        val dialog = onNodeWithTag(NavigationTestTags.Dialog).fetchSemanticsNode().boundsInRoot
        assertTrue(backdrop.width >= 1399f && backdrop.height >= 899f,
            "dialog backdrop should cover the workspace: $backdrop")
        assertTrue(dialog.width <= 440f && dialog.height < 300f,
            "navigation dialog should be compact on desktop: $dialog")
        onNodeWithTag(NavigationTestTags.NameField).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(NavigationTestTags.Dialog).assertDoesNotExist()
    }

    @Test
    fun enterRenamesAndCancelKeepsTheName() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.page("lecture-notes")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Week 1")
        onNodeWithTag(NavigationTestTags.NameField).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals("Week 1", observed.notebooks.first().sections.first().pages.first().title) }

        onNodeWithTag(NavigationTestTags.notebook("calculus")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.NameField).performTextReplacement("Something else")
        onNodeWithTag(NavigationTestTags.Cancel).performClick()
        runOnIdle { assertEquals("Calculus", observed.notebooks.first().name) }

        // A native popup must finish closing without leaving an input layer over the workspace.
        onNodeWithTag(WorkspaceTestTags.section("chapter-2")).performMouseInput { click(center) }
        runOnIdle { assertEquals("chapter-2", observed.selectedSectionId) }
    }

    @Test
    fun deletingAPageAsksFirstAndThenOpensItsNeighbour() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.page("homework-1")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Delete).performClick()
        onNodeWithText("Delete Homework 1?").assertIsDisplayed()
        val deletePixels = onNodeWithTag(NavigationTestTags.ConfirmDelete).captureToImage().toPixelMap()
        val destructive = deletePixels[8, deletePixels.height / 2]
        assertTrue(destructive.red > 0.6f && destructive.green < 0.25f && destructive.blue < 0.3f,
            "Delete action should use a solid destructive red: $destructive")
        onNodeWithTag(NavigationTestTags.Cancel).performClick()
        onNodeWithTag(WorkspaceTestTags.page("homework-1")).assertIsDisplayed()

        onNodeWithTag(WorkspaceTestTags.page("homework-1")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Delete).performClick()
        onNodeWithTag(NavigationTestTags.ConfirmDelete).performClick()

        onNodeWithTag(WorkspaceTestTags.page("homework-1")).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Lecture notes")
        runOnIdle { assertEquals("lecture-notes", observed.selectedPageId) }
    }

    @Test
    fun deletingTheOpenNotebookMovesToTheNextOne() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(NavigationTestTags.notebook("calculus")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Delete).performClick()
        onNodeWithText("This notebook, its sections and all of their pages will be deleted.").assertIsDisplayed()
        onNodeWithTag(NavigationTestTags.ConfirmDelete).performClick()

        onNodeWithTag(NavigationTestTags.notebook("calculus")).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Meiosis")
        runOnIdle { assertEquals("biology", observed.selectedNotebookId) }
    }

    private fun ComposeUiTest.setWorkspace(
        settings: InterfaceSettings = InterfaceSettings(displayScale = 1f),
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState.demo()) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                    interfaceSettings = settings)
            }
        }
    }
}
