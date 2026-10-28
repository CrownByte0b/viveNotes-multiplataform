package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.newTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanvasExternalPasteTest {
    private val text = Outline.Text.empty().copy(blocks = listOf(Block.of("Copied text")))
    private val table = newTable(2, 2).copy(y = 120f)

    @Test
    fun insertsAndSelectsObjectsAtThePastePointInOneUndoStep() {
        val initial = WorkspaceState.demo().clearCanvasFocus()
        val pasted = initial.insertClipboardOutlines(initial.selectedPageId, listOf(text, table), 80f, 250f)
        val added = pasted.selectedPage!!.document.outlines.takeLast(2)
        assertEquals(80f, added.first().x)
        assertEquals(250f, added.first().y)
        assertEquals(370f, added.last().y)
        assertEquals(setOf(added.first().id), pasted.selectedTextOutlineIds)
        assertEquals(setOf(added.last().id), pasted.selectedObjectIds)
        assertEquals(initial.structuralUndo.size + 1, pasted.structuralUndo.size)
        assertEquals(initial.selectedPage!!.document, pasted.undoStructure().selectedPage!!.document)
        assertEquals(pasted.selectedPage!!.document, pasted.undoStructure().redoStructure().selectedPage!!.document)
        assertNull(pasted.focusedTextOutlineId)
    }

    @Test
    fun repeatedPastesGetFreshOutlineRowCellAndBlockIds() {
        val initial = WorkspaceState.demo()
        val once = initial.insertClipboardOutlines(initial.selectedPageId, listOf(text, table), 20f, 200f)
        val twice = once.insertClipboardOutlines(initial.selectedPageId, listOf(text, table), 60f, 300f)
        val added = twice.selectedPage!!.document.outlines.takeLast(4)
        assertEquals(4, added.map { it.id }.distinct().size)
        val tables = added.filterIsInstance<Outline.Table>()
        assertEquals(4, tables.flatMap { it.rows }.map { it.id }.distinct().size)
        assertEquals(8, tables.flatMap { it.cellIds() }.distinct().size)
        val blocks = tables.flatMap { it.rows }.flatMap { it.cells }.flatMap { it.blocks } +
            added.filterIsInstance<Outline.Text>().flatMap { it.blocks }
        assertEquals(blocks.size, blocks.map { it.id }.distinct().size)
    }

    @Test
    fun aChangedOrReadOnlyPageRejectsPendingPasteAndInvalidCoordinatesDoNothing() {
        val initial = WorkspaceState.demo()
        assertSame(initial, initial.insertClipboardOutlines("another-page", listOf(text), 20f, 200f))
        assertSame(initial, initial.insertClipboardOutlines(initial.selectedPageId, listOf(text), Float.NaN, 200f))
        assertSame(initial, initial.insertClipboardOutlines(initial.selectedPageId, emptyList(), 20f, 200f))
        val readOnly = initial.updatePage(initial.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        assertSame(readOnly, readOnly.insertClipboardOutlines(readOnly.selectedPageId, listOf(text), 20f, 200f))
    }

    @Test
    fun pasteDisarmsPlacementToolsAndKeepsTheTitleClear() {
        val initial = WorkspaceState.demo().toggleTextTool()
        val pasted = initial.insertClipboardOutlines(initial.selectedPageId, listOf(text), -20f, -100f)
        val added = pasted.selectedPage!!.document.outlines.last()
        assertEquals(0f, added.x)
        assertTrue(added.y >= pasted.selectedPage!!.document.style.titleFloor)
        assertFalse(pasted.textToolArmed)
    }
}
