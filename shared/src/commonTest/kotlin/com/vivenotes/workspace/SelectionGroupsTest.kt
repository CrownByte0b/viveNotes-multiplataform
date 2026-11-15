package com.vivenotes.workspace

import com.vivenotes.model.*
import com.vivenotes.model.ink.InkSample
import kotlin.test.*

class SelectionGroupsTest {
    private fun workspace(): WorkspaceState {
        val state = WorkspaceState.demo()
        val objects = listOf(
            Outline.Shape("shape", x = 20f, y = 200f, width = 40f, height = 40f),
            newTable(2, 2, x = 100f, y = 200f).copy(id = "table"),
            Outline.Text("text", x = 400f, y = 200f, width = 120f, blocks = listOf(Block.of("Notes"))),
            Outline.Image("image", x = 550f, y = 200f, width = 50f, height = 50f, attachmentId = "picture"),
            Outline.Equation("equation", x = 650f, y = 200f, width = 60f, height = 40f, latex = "x"),
            Outline.Graph("graph", x = 750f, y = 200f, width = 100f, height = 100f, expression = "x"),
        )
        return state.updatePage(state.selectedPageId) { it.copy(document = PageDoc(outlines = objects)) }
    }

    @Test
    fun everyObjectKindGroupsMovesAndUngroupsInSingleHistorySteps() {
        val original = workspace()
        val selected = original.selectObjectsInRect(0f, 180f, 900f, 450f)
        assertTrue(selected.canGroupSelection)
        val grouped = selected.toggleSelectionGroup()
        assertTrue(grouped.selectionGrouped)
        val group = assertNotNull(grouped.selectedPage!!.document.outlines.first().groupId)
        assertTrue(grouped.selectedPage!!.document.outlines.all { it.groupId == group && it.lockGroup == null })
        assertEquals(1, grouped.structuralUndo.size)
        assertEquals(original.selectedPage!!.document, grouped.undoStructure().selectedPage!!.document)
        assertEquals(grouped.selectedPage!!.document, grouped.undoStructure().redoStructure().selectedPage!!.document)
        for (member in grouped.selectedPage!!.document.outlines) {
            val held = grouped.selectPointer().selectObject(member.id)
            assertEquals(5, held.selectedObjectIds.size)
            assertEquals(setOf("text"), held.selectedTextOutlineIds)
            val moved = held.moveSelectedObjects(25f, 30f)
            moved.selectedPage!!.document.outlines.zip(original.selectedPage!!.document.outlines).forEach { (after, before) ->
                assertEquals(before.x + 25f, after.x)
                assertEquals(before.y + 30f, after.y)
            }
        }
        val ungrouped = grouped.toggleSelectionGroup()
        assertFalse(ungrouped.selectionGrouped)
        assertTrue(ungrouped.selectedPage!!.document.outlines.all { it.groupId == null })
        assertEquals(setOf("shape"), ungrouped.selectPointer().selectObject("shape").selectedObjectIds)
        assertEquals(2, ungrouped.structuralUndo.size)
    }

    @Test
    fun aSmallRectOrLassoExpandsTheGroupOutsideItsBounds() {
        val grouped = workspace().selectObjectsInRect(0f, 180f, 900f, 450f).toggleSelectionGroup().selectPointer()
        val rectangle = grouped.selectObjectsInRect(15f, 195f, 65f, 245f)
        val lasso = grouped.selectObjectsInLasso(listOf(InkSample(15f, 195f), InkSample(65f, 195f),
            InkSample(65f, 245f), InkSample(15f, 245f), InkSample(15f, 195f)))
        assertEquals(5, rectangle.selectedObjectIds.size)
        assertEquals(rectangle.selectedObjectIds, lasso.selectedObjectIds)
        assertEquals(setOf("text"), lasso.selectedTextOutlineIds)
    }

    @Test
    fun copiesUseNewGroupIdsForEachPasteAndPreserveSeparateGroups() {
        val first = workspace().selectObjectsInRect(0f, 180f, 380f, 450f).toggleSelectionGroup()
        val second = first.selectPointer().copy(selectedObjectIds = setOf("image", "equation", "graph"),
            selectedTextOutlineIds = setOf("text")).toggleSelectionGroup()
        val source = second.selectedPage!!.document
        val copied = second.selectObjectsInRect(0f, 180f, 900f, 450f).copySelectedObjects()
        val pasted = copied.pasteCanvasAt(20f, 500f)
        val additions = pasted.selectedPage!!.document.outlines.drop(source.outlines.size)
        val originalGroups = source.outlines.map { it.groupId }.toSet()
        assertEquals(2, additions.map { it.groupId }.distinct().size)
        assertTrue(additions.all { it.groupId != null && it.groupId !in originalGroups })
        val again = pasted.pasteCanvasAt(20f, 900f).selectedPage!!.document.outlines.takeLast(6)
        assertTrue(again.none { it.groupId in additions.map { it.groupId } })
    }

    @Test
    fun groupingDoesNotChangeLocksAndRejectsMissingIdsSingleObjectsAndReadOnlyPages() {
        val single = workspace().selectObject("shape")
        assertFalse(single.canGroupSelection)
        assertSame(single, single.toggleSelectionGroup())
        val missing = single.copy(selectedObjectIds = setOf("shape", "missing"))
        assertFalse(missing.canGroupSelection)
        assertSame(missing, missing.toggleSelectionGroup())
        val locked = workspace().selectObjectsInRect(0f, 180f, 380f, 450f).toggleObjectLock()
        val grouped = locked.toggleSelectionGroup()
        assertTrue(grouped.selectionGrouped)
        assertEquals(locked.selectedPrimeObjects.map { it.lockGroup }, grouped.selectedPrimeObjects.map { it.lockGroup })
        assertEquals(grouped.selectedPage!!.document, grouped.moveSelectedObjects(20f, 30f).selectedPage!!.document)
        val unreadable = locked.updatePage(locked.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        assertSame(unreadable, unreadable.toggleSelectionGroup())
    }

    @Test
    fun preparedGroupingRejectsStaleSelectionsAndPreservesUnrelatedState() {
        val selected = workspace().selectObjectsInRect(0f, 180f, 380f, 450f)
        val prepared = selected.toggleSelectionGroup()
        val changed = selected.selectPointer()
        assertSame(changed, changed.applyPreparedSelectionGrouping(selected, prepared))
        val navigation = selected.copy(navigationVisible = false)
        val applied = navigation.applyPreparedSelectionGrouping(selected, prepared)
        assertTrue(applied.selectionGrouped)
        assertFalse(applied.navigationVisible)
        val edited = selected.editOutlines { it.dropLast(1) }
        assertSame(edited, edited.applyPreparedSelectionGrouping(selected, prepared))
    }

    @Test
    fun jsonAndCborKeepGroupsAndOldDocumentsKeepTheirExactEncoding() {
        val doc = workspace().selectObjectsInRect(0f, 180f, 900f, 450f).toggleSelectionGroup().selectedPage!!.document
        assertEquals(doc, JsonDocumentCodec.decode(JsonDocumentCodec.encode(doc)))
        assertEquals(doc, CborDocumentCodec.decode(CborDocumentCodec.encode(doc)))
        val old = """{"outlines":[{"t":"shape","id":"shape"}]}"""
        assertEquals(old, JsonDocumentCodec.encodeToString(JsonDocumentCodec.decodeFromString(old)))
    }
}
