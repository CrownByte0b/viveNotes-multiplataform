package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Run
import com.vivenotes.model.newTable
import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceProjectionTest {
    private fun state(doc: PageDoc): WorkspaceState = WorkspaceState.demo().let { base ->
        base.updatePage(base.selectedPageId) { it.copy(document = doc) }
    }

    @Test fun buffersResolveTextAndCellContentWithFocusedSelectionAndTypingMarks() {
        var next = 0
        val table = newTable(3, 2, x = 40f, y = 80f, newId = { "cell-${next++}" }).let { table ->
            table.copy(rows = table.rows.map { row -> row.copy(cells = row.cells.map { cell ->
                cell.copy(blocks = listOf(Block("block-${cell.id}", runs = listOf(Run("value")))))
            }) })
        }
        val text = Outline.Text("text", blocks = listOf(Block("text-block", runs = listOf(Run("plain")))))
        val doc = PageDoc(outlines = listOf(table, text))
        val encoded = JsonDocumentCodec.encodeToString(doc)
        val focusedId = table.rows.last().cells.last().id
        val base = state(doc).copy(focusedTextOutlineId = focusedId,
            editorSelection = TextSelection(1, 4), typingMarks = setOf(Mark.Bold))
        assertEquals("plain", base.richTextFor("text")!!.text)
        assertEquals(TextSelection(0), base.richTextFor("text")!!.selection)
        assertTrue(base.richTextFor("text")!!.typingMarks.isEmpty())
        assertEquals(TextSelection(1, 4), base.richTextFor(focusedId)!!.selection)
        assertEquals(setOf(Mark.Bold), base.richTextFor(focusedId)!!.typingMarks)
        assertEquals(table.x + table.columns.take(2).sum(), base.focusedTextOutline!!.x)
        assertEquals(table.y + table.rows.first().minHeight, base.focusedTextOutline!!.y)
        assertNull(base.richTextFor("absent"))
        assertEquals(encoded, JsonDocumentCodec.encodeToString(doc))

        val locked = state(doc.copy(outlines = listOf(table.copy(lockGroup = "locked"), text)))
        assertNull(locked.richTextFor(focusedId))
        assertNull(state(doc.copy(outlines = listOf(table.copy(inkOnly = true), text))).richTextFor(focusedId))
        assertEquals("plain", locked.richTextFor("text")!!.text)
    }

    @Test fun documentLookupPreservesFirstTextPriorityAndChangesWithNewDocument() {
        var next = 0
        val table = newTable(1, 1, newId = { "id-${next++}" })
        val shared = table.rows.single().cells.single().id
        val first = Outline.Text(shared, blocks = listOf(Block("a", runs = listOf(Run("first")))))
        val last = first.copy(blocks = listOf(Block("b", runs = listOf(Run("last")))))
        val initial = state(PageDoc(outlines = listOf(table, first, last)))
        assertEquals("first", initial.richTextFor(shared)!!.text)
        val replaced = initial.updatePage(initial.selectedPageId) { page ->
            page.copy(document = page.document.copy(outlines = listOf(last)))
        }
        assertEquals("last", replaced.richTextFor(shared)!!.text)
        assertEquals("first", initial.richTextFor(shared)!!.text)
    }

    @Test fun pageEditsRetainUnrelatedBranchesAndMissingOrUnchangedEditsRetainState() {
        val initial = WorkspaceState.demo()
        val selected = assertNotNull(initial.selectedSection)
        val changed = initial.updatePage(initial.selectedPageId) { it.copy(title = "changed") }
        assertEquals("changed", changed.selectedPage!!.title)
        assertSame(initial, initial.updatePage("absent") { error("missing page transformed") })
        assertSame(initial, initial.updatePage(initial.selectedPageId) { it })
        initial.notebooks.zip(changed.notebooks).forEach { (beforeNotebook, afterNotebook) ->
            if (beforeNotebook.sections.none { it.id == selected.id }) assertSame(beforeNotebook, afterNotebook)
            beforeNotebook.sections.zip(afterNotebook.sections).forEach { (beforeSection, afterSection) ->
                if (beforeSection.id != selected.id) assertSame(beforeSection, afterSection)
                beforeSection.pages.zip(afterSection.pages).forEach { (before, after) ->
                    if (before.id != initial.selectedPageId) assertSame(before, after)
                }
            }
        }
        assertEquals(initial.selectedPage!!.title, initial.copy(selectedPageId = initial.selectedPageId).selectedPage!!.title)
    }

    @Test fun geometryEditsKeepPreviewIdentityAndTextEditsRefreshPreview() {
        val doc = PageDoc(outlines = listOf(Outline.Text("text", x = 30f, y = 100f,
            blocks = listOf(Block("paragraph", runs = listOf(Run("original")))))))
        val initial = state(doc).updatePage(WorkspaceState.demo().selectedPageId) { it.copy(preview = "original".toCharArray().concatToString()) }
        val moved = initial.moveTextBox("text", 5f, 10f, recordHistory = false)
        assertSame(initial.selectedPage!!.preview, moved.selectedPage!!.preview)
        val resized = moved.resizeTextBox("text", width = 300f, recordHistory = false)
        assertSame(initial.selectedPage!!.preview, resized.selectedPage!!.preview)
        val edited = resized.focusTextBox("text").selectText(TextSelection(0, 8)).replaceSelectedText("replacement")
        assertEquals("replacement", edited.selectedPage!!.preview)
    }
}
