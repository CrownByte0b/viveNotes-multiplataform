package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.*
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.ribbon.settings.InterfaceTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DrawPlacementInteractionTest {
    private fun workspace(): WorkspaceState {
        val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        return initial.updatePage(initial.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList())) }
    }

    @Test
    fun defaultInkTablePlacesAndItsGridCanBeEditedWithoutFreezingTheWorkspace() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace())
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(TableTags.Tool).performClick()
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(100f, 200f)) }
        val table = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        assertTrue(table.inkOnly)
        assertTrue(table.rows.flatMap { it.cells }.all { it.blocks.isEmpty() })
        onNodeWithTag(WorkspaceTestTags.primeObject(table.id)).assertIsDisplayed()
        table.rows.flatMap { it.cells }.forEach { onNodeWithTag(TableTags.cell(it.id)).assertDoesNotExist() }

        onNodeWithTag(TableTags.RowMenu).performClick()
        onNodeWithTag(TableTags.RowBelow).performClick()
        onNodeWithTag(TableTags.ColumnMenu).performClick()
        onNodeWithTag(TableTags.ColumnRight).performClick()
        onNodeWithTag(TableTags.ColumnRight).assertDoesNotExist()
        runOnIdle {
            val edited = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
            assertEquals(table.rowCount + 1, edited.rowCount)
            assertEquals(table.columnCount + 1, edited.columnCount)
            assertTrue(edited.rows.flatMap { it.cells }.all { it.blocks.isEmpty() })
        }
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        runOnIdle {
            val undone = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
            assertEquals(table.columnCount, undone.columnCount)
            assertEquals(table.rowCount + 1, undone.rowCount)
        }
        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Settings)).performClick()
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.Dialog).assertIsDisplayed()
        onNodeWithTag(InterfaceTags.Cancel).performClick()
        runOnIdle { assertEquals(RibbonTab.Settings, state.value.activeTab) }
    }

    @Test
    fun tableKitPlacesARealGridAndTypedCellsUseDocumentFormattingAndTabNavigation() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace())
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(TableTags.Kit).performClick()
        onNodeWithTag("draw-table-ink-only").performClick()
        onNodeWithTag(TableTags.Columns).performClick()
        onNodeWithTag("${TableTags.Columns}-option-4").performClick()
        onNodeWithTag("draw-table-ink-only").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(100f, 200f)) }
        val table = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        assertFalse(table.inkOnly); assertEquals(4, table.columnCount)
        onNodeWithTag(TableTags.cell(table.cellAt(0, 0)!!.id)).performTextInput("Cell text")
        runOnIdle {
            val saved = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
            assertEquals("Cell text", saved.cellAt(0, 0)!!.plainText)
        }
        onNodeWithTag(TableTags.cell(table.cellAt(0, 0)!!.id)).performTextInputSelection(androidx.compose.ui.text.TextRange(0, 4))
        runOnIdle {
            assertEquals(table.cellAt(0, 0)!!.id, state.value.focusedTextOutlineId)
            assertEquals(com.vivenotes.richtext.TextSelection(0, 4), state.value.editorSelection, "cell selection before tab change")
        }
        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Document)).performClick()
        runOnIdle {
            assertEquals(table.cellAt(0, 0)!!.id, state.value.focusedTextOutlineId)
            assertEquals(com.vivenotes.richtext.TextSelection(0, 4), state.value.editorSelection, "cell selection after tab change")
        }
        onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold)).performClick()
        runOnIdle {
            assertTrue(state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single().cellAt(0, 0)!!.blocks
                .flatMap { it.runs }.any { Mark.Bold in it.marks }, "selection=${state.value.editorSelection}; typing=${state.value.typingMarks}; blocks=${state.value.richText?.blocks}")
        }
        onNodeWithTag(TableTags.cell(table.cellAt(0, 0)!!.id)).performKeyInput { pressKey(Key.Tab) }
        runOnIdle { assertEquals(table.cellAt(0, 1)!!.id, state.value.focusedTextOutlineId) }
        runOnIdle { state.value = state.value.selectObject(table.id) }
        onNodeWithTag(TableTags.RowMenu).performClick()
        onNodeWithTag(TableTags.RowBelow).performClick()
        runOnIdle { assertEquals(4, state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single().rowCount) }
    }

    @Test
    fun equationPreviewArmsTheMeasuredObjectAndEditUpdatesTheSavedLatex() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val state = mutableStateOf(workspace())
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag(EquationTags.Kit).performClick()
        onNodeWithTag(EquationTags.Source).performTextReplacement("x^2 + 1")
        onNodeWithTag(EquationTags.Preview).assertExists()
        onNodeWithTag(EquationTags.Submit).assertIsEnabled().performClick()
        runOnIdle { assertEquals(DrawPlacementTool.Equation, state.value.drawPlacement); assertTrue(state.value.pendingEquation!!.width > 8f) }
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput { click(Offset(250f, 300f)) }
        val equation = state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>().single()
        assertEquals("x^2 + 1", equation.latex)
        onNodeWithTag(WorkspaceTestTags.primeObject(equation.id)).assertIsDisplayed()
        onNodeWithTag("draw-equation-edit").performClick()
        onNodeWithTag(EquationTags.Source).performTextReplacement("y^3")
        onNodeWithTag(EquationTags.Submit).performClick()
        runOnIdle { assertEquals("y^3", state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>().single().latex) }
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        runOnIdle { assertEquals(equation, state.value.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>().single()) }
    }

    @Test
    fun insertSpaceDragMovesPageContentAndUndoRestoresItInOneStep() = runDesktopComposeUiTest(width = 1500, height = 1000) {
        val initial = workspace()
        val state = mutableStateOf(initial.updatePage(initial.selectedPageId) {
            it.copy(document = PageDoc(outlines = listOf(Outline.Text("after", 300f, 260f, blocks = listOf(Block.of("Below the cut"))))))
        })
        setContent { ViveNotesTheme { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        onNodeWithTag("draw-insert-space").performClick()
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).performMouseInput {
            updatePointerTo(Offset(500f, 200f)); press(); moveTo(Offset(500f, 260f), delayMillis = 80); release()
        }
        waitUntil(timeoutMillis = 5000) { state.value.structuralUndo.isNotEmpty() }
        runOnIdle { assertEquals(320f, state.value.selectedPage!!.document.outlines.single().y); assertEquals(1, state.value.structuralUndo.size) }
        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        runOnIdle { assertEquals(260f, state.value.selectedPage!!.document.outlines.single().y) }
    }
}
