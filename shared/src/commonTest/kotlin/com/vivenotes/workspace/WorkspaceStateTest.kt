package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeSegment
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class WorkspaceStateTest {

    @Test
    fun selectingSectionMovesSelectionToItsFirstPage() {
        val selected = WorkspaceState.demo().selectSection("chapter-2")

        assertEquals("calculus", selected.selectedNotebookId)
        assertEquals("chapter-2", selected.selectedSectionId)
        assertEquals("sequences", selected.selectedPageId)
    }

    @Test
    fun selectingPageFindsItsOwningNotebookAndSection() {
        val selected = WorkspaceState.demo().selectPage("meiosis")

        assertEquals("biology", selected.selectedNotebookId)
        assertEquals("cell-biology", selected.selectedSectionId)
        assertEquals("meiosis", selected.selectedPageId)
    }

    @Test
    fun editingPageDoesNotMutateOtherPages() {
        val initial = WorkspaceState.demo()
        val lectureBefore = initial.selectPage("lecture-notes").selectedPage

        val edited = initial.updateSelectedPage(
            title = "Finished homework",
            body = "First line\nSecond line",
        )

        assertEquals("Finished homework", edited.selectedPage?.title)
        assertEquals("First line", edited.selectedPage?.preview)
        assertEquals(lectureBefore, edited.selectPage("lecture-notes").selectedPage)
        assertNotEquals(initial.selectedPage, edited.selectedPage)
    }

    @Test
    fun addPageAppendsAndSelectsDraft() {
        val initial = WorkspaceState.demo()
        val withDraft = initial.addPage()

        assertEquals(initial.selectedSection!!.pages.size + 1, withDraft.selectedSection!!.pages.size)
        assertEquals("Untitled page", withDraft.selectedPage?.title)
        assertEquals(withDraft.selectedSection?.pages?.last()?.id, withDraft.selectedPageId)
    }

    @Test
    fun formattingTheSelectedPagePreservesOtherTextOutlines() {
        val initial = WorkspaceState.demo()
        val page = requireNotNull(initial.selectedPage)
        val extra = Outline.Text(id = "other", blocks = listOf(Block.of("keep me")))
        val withExtra = initial.copy(notebooks = initial.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { candidate ->
                    if (candidate.id == page.id) candidate.copy(
                        document = candidate.document.copy(outlines = candidate.document.outlines + extra),
                    ) else candidate
                })
            })
        })

        val formatted = withExtra.selectText(TextSelection(0, 6)).toggleSelectedMark(Mark.Bold)

        assertEquals(extra, formatted.selectedPage?.document?.outlines?.last())
        assertEquals(Mark.Bold, formatted.richText?.blocks?.first()?.runs?.first()?.marks?.single())
    }

    @Test
    fun textToolCreatesOnlyOnCanvasAndFocusedOutlineGetsFormatting() {
        val initial = WorkspaceState.demo()
        val firstId = initial.focusedTextOutline!!.id
        assertEquals(initial, initial.createTextBox(20f, 150f))
        val armed = initial.toggleTextTool()
        assertEquals(armed, armed.createTextBox(20f, 50f))
        val created = armed.createTextBox(30f, 150f)
        val second = created.focusedTextOutline!!
        assertNotEquals(firstId, second.id)
        assertEquals(30f, second.x)
        assertEquals(150f, second.y)
        val edited = created.editSelectedText("second", TextSelection(6))
            .selectText(TextSelection(0, 6)).toggleSelectedMark(Mark.Bold)
        assertEquals("second", edited.richText?.text)
        assertTrue(Mark.Bold in edited.focusedTextOutline!!.blocks.first().runs.first().marks)
        assertFalse(Mark.Bold in (edited.selectedPage!!.document.outlines.first() as Outline.Text)
            .blocks.first().runs.first().marks)
        assertFalse(edited.toggleTextTool().textToolArmed)
    }

    @Test
    fun textBoxClipboardPreservesStyledBlocksAndMintsNewIds() {
        val initial = WorkspaceState.demo().selectText(TextSelection(0, 6))
            .toggleSelectedMark(Mark.Bold)
        val source = initial.focusedTextOutline!!
        val copied = initial.copyTextBox(source.id)
        val pasted = copied.pasteCanvasAt(300f, 200f)
        val duplicate = pasted.focusedTextOutline!!
        assertEquals(300f, duplicate.x)
        assertEquals(200f, duplicate.y)
        assertNotEquals(source.id, duplicate.id)
        assertNotEquals(source.blocks.first().id, duplicate.blocks.first().id)
        assertEquals(source.blocks.first().runs, duplicate.blocks.first().runs)
        assertEquals(source.id, copied.canvasClipboard.texts.single().id)
        val document = pasted.selectedPage!!.document
        assertEquals(document, JsonDocumentCodec.decode(JsonDocumentCodec.encode(document)))
    }

    @Test
    fun textBoxBoundsDeleteLastAndUndoRedoAreStructural() {
        val initial = WorkspaceState.demo()
        val id = initial.focusedTextOutline!!.id
        val moved = initial.moveTextBox(id, -500f, -500f)
            .resizeTextBox(id, width = 10f, minHeight = 5000f)
        assertEquals(0f, moved.focusedTextOutline!!.x)
        assertEquals(0f, moved.focusedTextOutline!!.y)
        assertEquals(120f, moved.focusedTextOutline!!.width)
        assertEquals(4000f, moved.focusedTextOutline!!.minHeight)
        val deleted = moved.deleteTextBox(id)
        assertTrue(deleted.selectedPage!!.document.outlines.isEmpty())
        assertEquals(null, deleted.richText)
        assertEquals(moved.selectedPage!!.document, deleted.undoStructure().selectedPage!!.document)
        assertEquals(deleted.selectedPage!!.document, deleted.undoStructure().redoStructure().selectedPage!!.document)
    }

    @Test
    fun structuralUndoKeepsTypingMadeAfterABoxMove() {
        val initial = WorkspaceState.demo()
        val id = initial.focusedTextOutline!!.id
        val moved = initial.moveTextBox(id, 80f, 0f)
        val typed = moved.selectText(TextSelection(moved.richText!!.text.length))
            .replaceSelectedText(" added")
        val undone = typed.undoStructure()
        assertEquals(initial.focusedTextOutline!!.x, undone.focusedTextOutline!!.x)
        assertTrue(undone.richText!!.text.endsWith(" added"))
        val redone = undone.redoStructure()
        assertEquals(80f, redone.focusedTextOutline!!.x)
        assertTrue(redone.richText!!.text.endsWith(" added"))
    }

    @Test
    fun primeLockWideningLassoAndClipboard() {
        val initial = WorkspaceState.demo()
        val page = initial.selectedPage!!
        val first = Outline.Shape(id = "shape-a", x = 10f, y = 200f,
            segments = listOf(ShapeSegment("segment-a", 10f, 200f, 130f, 280f)))
        val second = Outline.Image(id = "image-b", x = 200f, y = 200f,
            width = 80f, height = 80f, attachmentId = "fixture")
        val third = Outline.Equation(id = "equation-c", x = 350f, y = 200f)
        val withObjects = initial.copy(notebooks = initial.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map {
                    if (it.id == page.id) it.copy(document = it.document.copy(
                        outlines = it.document.outlines + listOf(first, second, third))) else it
                })
            })
        })
        val locked = withObjects.selectObjectsInRect(0f, 180f, 300f, 310f).toggleObjectLock()
        assertTrue(locked.selectedObjectsLocked)
        assertEquals(setOf("shape-a", "image-b"), locked.selectObject("shape-a").selectedObjectIds)
        assertEquals(locked.selectedPage!!.document, locked.moveSelectedObjects(10f, 10f).selectedPage!!.document)
        assertEquals(setOf("equation-c"), locked.selectObjectsInRect(0f, 180f, 500f, 310f).selectedObjectIds)
        val selected = locked.selectObject("shape-a").copySelectedObjects()
        assertEquals(2, selected.canvasClipboard.objects.size)
        val pasted = selected.pasteCanvasAt(500f, 400f)
        assertEquals(2, pasted.selectedObjectIds.size)
        assertTrue(pasted.selectedPage!!.document.outlines.filter { it.id in pasted.selectedObjectIds }
            .all { it.lockGroup == null })
        assertNotEquals("segment-a", pasted.selectedPage!!.document.outlines
            .filterIsInstance<Outline.Shape>().last().segments.single().id)
        val recolored = selected.colorSelectedObjects(0xFFE53935.toInt())
        assertEquals(0xFFE53935.toInt(), (recolored.selectedPage!!.document.outlines
            .first { it.id == "shape-a" } as Outline.Shape).borderArgb)
        assertEquals(locked.selectedPage!!.document,
            locked.selectObject("shape-a").toggleObjectLock().undoStructure().selectedPage!!.document)
        val moved = withObjects.selectObject("shape-a").moveSelectedObjects(30f, 20f)
        assertEquals(40f, (moved.selectedPage!!.document.outlines.first {
            it.id == "shape-a" } as Outline.Shape).x)
        val resized = withObjects.selectObject("shape-a")
            .resizeSelectedObjects(anchorX = 10f, anchorY = 200f, scaleX = 2f, scaleY = 2f)
        assertEquals(240f, (resized.selectedPage!!.document.outlines.first {
            it.id == "shape-a" } as Outline.Shape).width)
    }
}
