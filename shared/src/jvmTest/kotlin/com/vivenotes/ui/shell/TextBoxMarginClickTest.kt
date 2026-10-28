package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The 8 dp margin round a text box's text is the box's: the page leaves presses on a box to the box,
 * so a click there edits it, as anywhere in an Android container does.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxMarginClickTest {

    /**
     * Regression: on a new page, a Text tool click just under the title, in the margin of the page's
     * empty text box, neither made a box nor edited that one.
     */
    @Test
    fun aClickInTheMarginOfANewPagesEmptyBoxEditsItWithTheTextToolArmed() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = page(PageDoc.empty()).toggleTextTool()
            val empty = observed.selectedPage!!.document.outlines.single().id
            setWorkspace(observed) { observed = it }

            onNodeWithTag(WorkspaceTestTags.textBox(empty)).performMouseInput { click(Offset(width / 2f, height - 3f)) }
            runOnIdle {
                assertEquals(empty, observed.focusedTextOutlineId)
                assertEquals(TextSelection(0), observed.editorSelection)
                assertEquals(1, observed.selectedPage!!.document.outlines.size)
            }
        }

    @Test
    fun aClickInAWrittenBoxsMarginPutsTheCaretOnTheNearestText() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(PageDoc(outlines = listOf(
            Outline.Text(BOX, x = 100f, y = 300f, blocks = listOf(Block.of("Hello world"))))))
        setWorkspace(observed) { observed = it }

        // Right of the first line's end, then left of its start, both on the line.
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { click(Offset(width - 3f, 18f)) }
        runOnIdle {
            assertEquals(BOX, observed.focusedTextOutlineId)
            assertEquals(TextSelection("Hello world".length), observed.editorSelection)
        }
        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { click(Offset(3f, 18f)) }
        runOnIdle { assertEquals(TextSelection(0), observed.editorSelection) }
    }

    @Test
    fun aToolThatActsOnPressesOverABoxKeepsItsClickOnTheMargin() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(PageDoc(outlines = listOf(
            Outline.Text(BOX, x = 100f, y = 300f, blocks = listOf(Block.of("Hello world")))))).toggleShapeTool()
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { click(Offset(width / 2f, height - 3f)) }
        runOnIdle {
            assertNull(observed.focusedTextOutlineId)
            assertNotEquals(1, observed.selectedPage!!.document.outlines.size, "the Shape tool's click placed no shape")
        }
    }

    private fun page(document: PageDoc): WorkspaceState {
        val demo = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
        return demo.updatePage(demo.selectedPageId) { it.copy(document = document) }.clearCanvasFocus()
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, onStateChange: (WorkspaceState) -> Unit) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
            }
        }
    }

    private companion object {
        const val BOX = "margin-box"
    }
}
