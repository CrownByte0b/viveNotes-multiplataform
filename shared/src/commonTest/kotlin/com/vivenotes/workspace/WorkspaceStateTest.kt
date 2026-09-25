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
    fun pointerCommandClearsToolsAndCanvasSelection() {
        val initial = WorkspaceState.demo()
        val text = initial.toggleTextTool().focusTextBox(initial.focusedTextOutline!!.id)
        val pointer = text.selectPointer()
        assertFalse(pointer.textToolArmed)
        assertFalse(pointer.objectLassoArmed)
        assertEquals(null, pointer.focusedTextOutlineId)

        val lasso = initial.toggleObjectLasso().copy(selectedObjectIds = setOf("shape"))
        assertEquals(emptySet(), lasso.selectPointer().selectedObjectIds)
        assertFalse(lasso.selectPointer().objectLassoArmed)
    }

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
    fun movingTextPlacementDiscardsUnwrittenBoxesButKeepsWrittenOnes() {
        val initial = WorkspaceState.demo().toggleTextTool()
        val first = initial.createTextBox(100f, 300f)
        val second = first.createTextBox(300f, 400f)
        assertEquals(1, second.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
            .count { it.blocks.all { block -> block.text.isBlank() } })
        assertTrue(second.selectedPage!!.document.outlines.none { it.id == first.focusedTextOutlineId })

        val written = second.editSelectedText("kept", TextSelection(4))
        val third = written.createTextBox(500f, 500f)
        assertTrue(third.selectedPage!!.document.outlines.any { it.id == second.focusedTextOutlineId })
        assertTrue(third.selectedPage!!.document.outlines.any { it.id == third.focusedTextOutlineId })
        val dismissed = third.selectPointer()
        assertTrue(dismissed.selectedPage!!.document.outlines.none { it.id == third.focusedTextOutlineId })
        assertTrue(dismissed.selectedPage!!.document.outlines.any { it.id == second.focusedTextOutlineId })
        assertEquals(emptyList(), dismissed.structuralRedo)

        val abandoned = second.createTextBox(700f, 600f).selectPointer()
        assertTrue(abandoned.selectedPage!!.document.outlines.none { it.id == second.focusedTextOutlineId })
        assertTrue(abandoned.structuralUndo.isEmpty())
        assertEquals(abandoned.selectedPage!!.document, abandoned.undoStructure().selectedPage!!.document)
    }

    @Test
    fun leavingPageDiscardsUnwrittenTextPlacement() {
        val initial = WorkspaceState.demo().toggleTextTool().createTextBox(300f, 350f)
        val id = initial.focusedTextOutlineId!!
        val returned = initial.selectPage("sequences").selectPage(initial.selectedPageId)

        assertTrue(returned.selectedPage!!.document.outlines.none { it.id == id })
    }

    @Test
    fun lassoSelectsWrittenTextBoxesAsCanvasObjects() {
        val first = WorkspaceState.demo().toggleTextTool().createTextBox(300f, 350f)
            .editSelectedText("one", TextSelection(3))
        val firstId = first.focusedTextOutlineId!!
        val second = first.createTextBox(500f, 420f).editSelectedText("two", TextSelection(3))
        val secondId = second.focusedTextOutlineId!!
        val selected = second.toggleObjectLasso().selectObjectsInRect(250f, 300f, 800f, 600f)

        assertEquals(setOf(firstId, secondId), selected.selectedTextOutlineIds)
        assertEquals(setOf(firstId, secondId), selected.copySelectedObjects().canvasClipboard.texts
            .map { it.id }.toSet())
        val moved = selected.moveSelectedObjects(20f, 30f)
        assertEquals(320f, moved.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
            .first { it.id == firstId }.x)
        assertTrue(moved.deleteSelectedObjects().selectedPage!!.document.outlines
            .none { it.id == firstId || it.id == secondId })
    }

    @Test
    fun mixedLassoSelectsTextAndMovablePrimeObjectsButSkipsLocks() {
        val initial = WorkspaceState.demo()
        val page = initial.selectedPage!!
        val text = Outline.Text.empty(y = 350f).copy(x = 300f, blocks = listOf(Block.of("note")))
        val movable = Outline.Shape(id = "movable", x = 450f, y = 350f)
        val locked = Outline.Image(id = "locked", x = 600f, y = 350f,
            width = 80f, height = 80f, attachmentId = "fixture", lockGroup = "group")
        val withObjects = initial.copy(notebooks = initial.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { candidate ->
                    if (candidate.id == page.id) candidate.copy(document = candidate.document.copy(
                        outlines = candidate.document.outlines + listOf(text, movable, locked)))
                    else candidate
                })
            })
        })

        val selected = withObjects.selectObjectsInRect(250f, 300f, 750f, 600f)
        assertEquals(setOf(text.id), selected.selectedTextOutlineIds)
        assertEquals(setOf(movable.id), selected.selectedObjectIds)
        val copied = selected.copySelectedObjects().canvasClipboard
        assertEquals(setOf(text.id), copied.texts.map { it.id }.toSet())
        assertEquals(setOf(movable.id), copied.objects.map { it.id }.toSet())
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
                        outlines = it.document.outlines.map { outline ->
                            if (outline is Outline.Text) outline.copy(x = 800f) else outline
                        } + listOf(first, second, third))) else it
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
