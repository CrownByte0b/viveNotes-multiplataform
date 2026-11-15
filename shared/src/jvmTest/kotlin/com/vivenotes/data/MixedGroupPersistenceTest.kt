package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.selectionLine
import com.vivenotes.model.*
import com.vivenotes.workspace.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class MixedGroupPersistenceTest {
    @Test
    fun mixedGroupsReopenMoveCopyAndUngroupWithoutChangingOriginalStrokeBytes() = runBlocking {
        val file = Files.createTempFile("mixed-object-group", ".db").toFile()
        var database = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(database)
            val notebook = repository.createNotebook("Groups")
            val section = repository.createSection(notebook, "Mixed")
            val pageId = repository.createPage(section)
            val row = selectionLine("stroke", pageId)
            repository.applyInkEdit(pageId, InkEdit.AddStroke(row))
            val doc = PageDoc(outlines = listOf(Outline.Shape("shape", x = 150f, y = 200f, width = 40f, height = 40f),
                newTable(2, 2, x = 250f, y = 200f).copy(id = "table")))
            val state = WorkspaceState(listOf(NotebookSummary(notebook, "Groups", 0, listOf(
                SectionSummary(section, "Mixed", 0, listOf(PageSummary(pageId, "Page", "", "", doc, repository.loadInk(pageId))))
            ))), notebook, section, pageId)
            suspend fun save(next: WorkspaceState) {
                next.pendingInkEdits.forEach { repository.applyInkEdit(pageId, it.edit) }
                repository.saveDoc(pageId, next.selectedPage!!.document)
            }
            val grouped = state.selectObjectsInRect(0f, 180f, 600f, 400f).toggleSelectionGroup()
            save(grouped)
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            val reopenedDoc = (repository.loadDoc(pageId) as PageLoad.Loaded).doc
            val reopenedInk = repository.loadInk(pageId)
            val reopened = state.updatePage(pageId) { it.copy(document = reopenedDoc, ink = reopenedInk) }.selectObject("table")
            assertTrue(reopened.selectionGrouped)
            assertEquals(setOf("stroke"), reopened.selectedInk!!.targetIds)
            val moved = reopened.applyInkSelectionChange(reopened.selectedInk!!.move(20f, 30f)!!)
            save(moved)
            val copied = moved.copy(pendingInkEdits = emptyList()).copySelectedObjects().pasteCanvasAt(20f, 500f)
            save(copied)
            val ungrouped = copied.copy(pendingInkEdits = emptyList()).selectPointer().selectObject("table").toggleSelectionGroup()
            save(ungrouped)
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            val finalDoc = (repository.loadDoc(pageId) as PageLoad.Loaded).doc
            val finalInk = repository.loadInk(pageId)
            val final = state.updatePage(pageId) { it.copy(document = finalDoc, ink = finalInk) }
            val original = final.selectObject("table")
            assertFalse(original.selectionGrouped)
            assertNull(original.selectedInk)
            assertEquals(230f, original.selectedPrimeObjects.single().y)
            val pastedTable = final.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single { it.id != "table" }
            val pasted = final.selectObject(pastedTable.id)
            assertTrue(pasted.selectionGrouped)
            assertEquals(1, pasted.selectedInk!!.targetIds.size)
            assertFalse("stroke" in pasted.selectedInk!!.targetIds)
            assertContentEquals(row.points, repository.inkFor(pageId).single { it.id == "stroke" }.points)
        } finally { database.close(); file.delete() }
    }
}
