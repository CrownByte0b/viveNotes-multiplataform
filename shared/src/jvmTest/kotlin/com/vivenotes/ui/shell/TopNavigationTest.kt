package com.vivenotes.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.search.SearchTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TopNavigationTest {
    @Test
    fun historyControlsAreOnTheLeftInWideWindows() = assertHeaderLayout(1400)

    @Test
    fun historyControlsStayOnTheLeftInCompactWindows() = assertHeaderLayout(720)

    private fun assertHeaderLayout(width: Int) = runDesktopComposeUiTest(width = width, height = 100) {
        setContent {
            ViveNotesTheme {
                TopNavigation(RibbonTab.Document, true, {}, {}, true, true, {}, {}, false,
                    onOpenAccount = {}, searchAvailable = true)
            }
        }
        val tags = listOf(WorkspaceTestTags.NavigationToggle, WorkspaceTestTags.StructuralUndo,
            WorkspaceTestTags.StructuralRedo, WorkspaceTestTags.ribbonTab(RibbonTab.File),
            SearchTags.Open, WorkspaceTestTags.Account)
        val bounds = tags.map { onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue(left.right <= right.left, "Header controls must follow navigation, undo, redo, tabs, search, account")
        }
        onNodeWithText("ViveNotes").assertDoesNotExist()
        onNodeWithText("Vive Notes").assertDoesNotExist()
    }

    @Test
    fun relocatedHistoryControlsRetainAvailabilityAndActions() =
        runDesktopComposeUiTest(width = 1000, height = 100) {
            val canUndo = mutableStateOf(true)
            val canRedo = mutableStateOf(false)
            var undoCount = 0
            var redoCount = 0
            setContent {
                ViveNotesTheme {
                    TopNavigation(RibbonTab.Document, true, {}, {}, canUndo.value, canRedo.value,
                        onUndo = {
                            undoCount++
                            canUndo.value = false
                            canRedo.value = true
                        },
                        onRedo = {
                            redoCount++
                            canUndo.value = true
                            canRedo.value = false
                        }, accountConnected = false, onOpenAccount = {})
                }
            }
            val undo = onNodeWithTag(WorkspaceTestTags.StructuralUndo)
            val redo = onNodeWithTag(WorkspaceTestTags.StructuralRedo)
            redo.assertIsNotEnabled()
            undo.assertIsEnabled().performClick()
            undo.assertIsNotEnabled()
            redo.assertIsEnabled().performClick()
            undo.assertIsEnabled()
            redo.assertIsNotEnabled()
            runOnIdle {
                assertEquals(1, undoCount)
                assertEquals(1, redoCount)
            }
        }
}
