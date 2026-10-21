package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.TableCell
import com.vivenotes.model.TableRow
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.ui.ribbon.draw.TableTags
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CanvasVirtualizationTest {
    private fun fixture(): WorkspaceState = WorkspaceState.demo().let { state ->
        state.updatePage(state.selectedPageId) { page ->
            page.copy(document = page.document.copy(outlines = listOf(
                Outline.Text("near", x = 20f, y = 130f, width = 500f, blocks = listOf(Block.of("Near"))),
                Outline.Text("far", x = 20f, y = 4000f, width = 500f, blocks = listOf(Block.of("Far"))),
                Outline.Table("far-table", x = 20f, y = 6000f, width = 500f, columns = listOf(500f),
                    rows = listOf(TableRow("row", cells = listOf(TableCell("cell", listOf(Block.of("Cell"))))))),
            )))
        }.clearCanvasFocus()
    }

    @Test fun removesMeasuredOffscreenEditorsAndTablesButKeepsExtentAndFocusedInput() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var state by mutableStateOf(fixture())
        setContent { ViveNotesTheme { WorkspaceScreen(state, { command -> state = command(state) }) } }
        waitForIdle()
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertExists()
        onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertDoesNotExist()
        onNodeWithTag(TableTags.cell("cell")).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.primeObject("far-table")).assertExists()
        val extent = onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value
        assertTrue(extent >= 6000f, "retained measurements keep the far table in the page extent: $extent")

        runOnIdle { state = state.focusTextBox("far") }
        onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertExists()
        runOnIdle { state = state.clearCanvasFocus() }
        onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertDoesNotExist()
        runOnIdle { state = state.focusTextBox("far") }
        onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").performTextInput("!")
        runOnIdle { assertEquals("!Far", state.richTextFor("far")!!.text) }
        runOnIdle { state = state.clearCanvasFocus() }
        // Typing brought the caret into view, so the now-visible editor remains composed.
        onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertExists()
        assertTrue(onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value >= 6000f)

        runOnIdle { state = state.focusTextBox("cell") }
        onNodeWithTag(WorkspaceTestTags.primeObject("far-table")).assertExists()
    }

    @Test fun changedOffscreenContentIsRemeasuredBeforeItCanBeCulledAgain() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var state by mutableStateOf(fixture())
        setContent { ViveNotesTheme { WorkspaceScreen(state, { command -> state = command(state) }) } }
        onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertDoesNotExist()
        runOnIdle {
            state = state.updatePage(state.selectedPageId) { page ->
                page.copy(document = page.document.copy(outlines = page.document.outlines.map {
                    if (it is Outline.Text && it.id == "far") it.copy(blocks = List(160) { Block.of("Expanded line") }) else it
                }))
            }
        }
        waitForIdle()
        onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertDoesNotExist()
        val extent = onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value
        assertTrue(extent > 7000f, "newly expanded offscreen text contributes its new measured height: $extent")
    }
}
