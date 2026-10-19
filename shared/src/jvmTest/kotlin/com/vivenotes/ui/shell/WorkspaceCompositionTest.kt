package com.vivenotes.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.canvas.TextMenuTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.focusBody
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WorkspaceCompositionTest {
    @Test
    fun extractedEditorCallbacksKeepExternalUpdatesAndEditsAcrossTabChanges() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val workspace = mutableStateOf(WorkspaceState.demo().focusBody())
            setContent {
                ViveNotesTheme {
                    WorkspaceScreen(workspace.value, { workspace.value = it(workspace.value) })
                }
            }
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("First edit")
            runOnIdle {
                workspace.value = workspace.value.updateSelectedPage(title = "Updated title")
                    .copy(storageError = "Storage temporarily unavailable")
            }
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("Second edit")
            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Draw)).performClick()
            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Document)).performClick()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertTextContains("Second edit")
            runOnIdle {
                assertEquals("Updated title", workspace.value.selectedPage!!.title)
                assertEquals("Storage temporarily unavailable", workspace.value.storageError)
                assertEquals("Second edit", workspace.value.selectedPage!!.body)
            }
        }

    @Test
    fun changingPagesDisposesTheOldTextMenuAndTargetsTheNewPage() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo()
            val workspace = mutableStateOf(initial)
            setContent {
                ViveNotesTheme {
                    WorkspaceScreen(workspace.value, { workspace.value = it(workspace.value) })
                }
            }
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(8f, 10f)) }
            onNodeWithTag(TextMenuTags.SelectAll).assertExists()
            runOnIdle { workspace.value = workspace.value.selectPage("lecture-notes") }
            onNodeWithTag(TextMenuTags.SelectAll).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick().performTextReplacement("New page edit")
            runOnIdle {
                assertEquals("lecture-notes", workspace.value.selectedPageId)
                assertEquals("New page edit", workspace.value.richText!!.text)
                val originalPage = workspace.value.notebooks.flatMap { it.sections }.flatMap { it.pages }
                    .first { it.id == initial.selectedPageId }
                assertEquals(initial.selectedPage!!.document, originalPage.document)
            }
        }
}
