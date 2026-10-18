package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.ink.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class InkToolkitPersistenceTest {
    @Test
    fun automaticRecolourSurvivesReopenAndUndoPreservesExplicitWhiteAndOriginalInputs() = runBlocking {
        val file = Files.createTempFile("automatic-selection-color", ".db").toFile()
        var database = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(database)
            val notebook = repository.createNotebook("Colors")
            val section = repository.createSection(notebook, "Ink")
            val pageId = repository.createPage(section)
            val original = selectionLine(pageId = pageId).copy(colorArgb = -1, colorFollowsTheme = false)
            repository.applyInkEdit(pageId, InkEdit.AddStroke(original))
            val page = repository.loadInk(pageId)
            val change = page.geometry!!.selectAt(page, 40f, 200f)!!.recolor(0xff000000.toInt(), true)!!
            change.edits.forEach { repository.applyInkEdit(pageId, it) }
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            val reopened = repository.loadInk(pageId)
            assertTrue(reopened.strokes.single().colorFollowsTheme!!)
            assertEquals(0xffe6e6e6.toInt(), automaticColorOr(reopened.strokes.single().colorArgb,
                reopened.strokes.single().colorFollowsTheme, 0xffe6e6e6.toInt()))
            change.undo.forEach { repository.applyInkEdit(pageId, it) }
            val restored = repository.inkFor(pageId).single()
            assertEquals(-1, restored.colorArgb)
            assertFalse(restored.colorFollowsTheme!!)
            assertContentEquals(original.points, restored.points)
            change.redo.forEach { repository.applyInkEdit(pageId, it) }
            assertTrue(repository.loadInk(pageId).strokes.single().colorFollowsTheme!!)
        } finally { database.close(); file.delete() }
    }

    @Test
    fun groupsReopenAsOneMovableObjectAndUndoRestoresPreviousMembershipAndOriginalBytes() = runBlocking {
        val file = Files.createTempFile("ink-group", ".db").toFile()
        var database = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(database)
            val notebook = repository.createNotebook("Grouping")
            val section = repository.createSection(notebook, "Ink")
            val pageId = repository.createPage(section)
            val rows = listOf(selectionLine("one", pageId), selectionLine("two", pageId, 240f), selectionLine("outside", pageId, 400f))
            rows.forEach { repository.applyInkEdit(pageId, InkEdit.AddStroke(it)) }
            val page = repository.loadInk(pageId)
            val change = page.geometry!!.selectInRect(page, 0f, 180f, 120f, 260f)!!.group()!!
            change.edits.forEach { repository.applyInkEdit(pageId, it) }
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            val reopened = repository.loadInk(pageId)
            val selected = reopened.geometry!!.selectAt(reopened, 40f, 200f)!!
            assertEquals(setOf("one", "two"), selected.targetIds)
            assertTrue(selected.isGrouped)
            val moved = selected.move(50f, 30f)!!
            moved.edits.forEach { repository.applyInkEdit(pageId, it) }
            assertEquals(moved.after.desktopGeometry().projections.map { it.pageBounds },
                repository.loadInk(pageId).desktopGeometry().projections.map { it.pageBounds })
            moved.undo.forEach { repository.applyInkEdit(pageId, it) }
            change.undo.forEach { repository.applyInkEdit(pageId, it) }
            val ungrouped = repository.loadInk(pageId)
            assertEquals(setOf("one"), ungrouped.geometry!!.selectAt(ungrouped, 40f, 200f)!!.targetIds)
            repository.inkFor(pageId).forEach { row ->
                assertNull(row.groupId)
                assertContentEquals(rows.single { it.id == row.id }.points, row.points)
            }
        } finally { database.close(); file.delete() }
    }

    @Test
    fun groupingFragmentsReopensWithoutDuplicatingTheirUnselectedSiblingAndIsAtomic() = runBlocking {
        val file = Files.createTempFile("ink-fragment-group", ".db").toFile()
        var database = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(database)
            val notebook = repository.createNotebook("Fragments")
            val section = repository.createSection(notebook, "Ink")
            val pageId = repository.createPage(section)
            val original = selectionLine(pageId = pageId)
            repository.applyInkEdit(pageId, InkEdit.AddStroke(original))
            repository.applyInkEdit(pageId, splitSelectionPage(pageId).first)
            repository.applyInkEdit(pageId, InkEdit.AddStroke(selectionLine("second", pageId, 240f, endX = 45f)))
            val page = repository.loadInk(pageId)
            val change = page.geometry!!.selectInRect(page, 0f, 180f, 55f, 260f)!!.group()!!
            assertTrue(change.edits.single() is InkEdit.Batch)
            change.edits.forEach { repository.applyInkEdit(pageId, it) }
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            val reopened = repository.loadInk(pageId)
            assertEquals(change.after.desktopGeometry().projections.map { it.pageBounds }, reopened.desktopGeometry().projections.map { it.pageBounds })
            assertEquals(2, reopened.geometry!!.selectAt(reopened, 30f, 200f)!!.targetIds.size)
            val sibling = assertNotNull(reopened.geometry!!.selectAt(reopened, 90f, 200f),
                reopened.desktopGeometry().projections.joinToString { "${it.id}: ${it.pageBounds}, ${it.groupId}" })
            assertEquals(setOf("source"), sibling.targetIds)
            assertContentEquals(original.points, repository.inkFor(pageId).single { it.id == original.id }.points)
            change.undo.forEach { repository.applyInkEdit(pageId, it) }
            assertEquals(page.desktopGeometry().projections.map { it.pageBounds }, repository.loadInk(pageId).desktopGeometry().projections.map { it.pageBounds })
            change.redo.forEach { repository.applyInkEdit(pageId, it) }
            assertEquals(reopened.desktopGeometry().projections.map { it.pageBounds }, repository.loadInk(pageId).desktopGeometry().projections.map { it.pageBounds })
        } finally { database.close(); file.delete() }
    }

    @Test
    fun groupingRejectsForeignTargetsAndRollsBackTheWholeBatch() = runBlocking {
        val database = NotesDatabase.inMemory()
        try {
            val repository = NotesRepository(database)
            val notebook = repository.createNotebook("Group validation")
            val section = repository.createSection(notebook, "Ink")
            val page = repository.createPage(section)
            val other = repository.createPage(section)
            repository.applyInkEdit(page, InkEdit.AddStroke(selectionLine("local", page)))
            repository.applyInkEdit(other, InkEdit.AddStroke(selectionLine("foreign", other)))
            assertFailsWith<IllegalArgumentException> {
                repository.applyInkEdit(page, InkEdit.Batch(listOf(
                    InkEdit.SetStrokeGroups(mapOf("local" to "group")),
                    InkEdit.SetStrokeGroups(mapOf("foreign" to "group")))))
            }
            assertNull(repository.inkFor(page).single().groupId)
            assertNull(repository.inkFor(other).single().groupId)
        } finally { database.close() }
    }

    @Test
    fun recolourAndCopiedMovedFragmentsSurviveReopenAndUndoWithoutRestoringErasedInk() = runBlocking {
        val file = Files.createTempFile("ink-toolkit", ".db").toFile()
        var database = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(database)
            val notebook = repository.createNotebook("Ink")
            val section = repository.createSection(notebook, "Clipboard")
            val sourcePage = repository.createPage(section)
            val targetPage = repository.createPage(section)
            val original = selectionLine(pageId = sourcePage).copy(colorFollowsTheme = true)
            repository.applyInkEdit(sourcePage, InkEdit.AddStroke(original))
            val (cut, _) = splitSelectionPage(sourcePage)
            repository.applyInkEdit(sourcePage, cut)
            val page = repository.loadInk(sourcePage)
            val selection = page.geometry!!.selectInRect(page, 0f, 180f, 55f, 220f)!!
            val move = selection.move(20f, 40f)!!
            move.edits.forEach { repository.applyInkEdit(sourcePage, it) }
            val color = move.selection!!.recolor(0xff3b82f6.toInt())!!
            color.edits.forEach { repository.applyInkEdit(sourcePage, it) }
            val clipboard = color.selection!!.copy()!!
            val pasted = clipboard.paste(repository.loadInk(targetPage),
                300f - clipboard.bounds.left, 500f - clipboard.bounds.top)!!
            pasted.edits.forEach { repository.applyInkEdit(targetPage, it) }
            val expected = pasted.after.desktopGeometry().projections.single().pageBounds
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            val reopened = repository.loadInk(targetPage).desktopGeometry().projections.single()
            assertEquals(expected, reopened.pageBounds)
            assertEquals(0xff3b82f6.toInt(), reopened.stroke.brush.colorIntArgb)
            assertEquals(false, reopened.colorFollowsTheme)
            assertTrue(reopened.pageBounds!!.right < 360f)
            assertContentEquals(original.points, repository.inkFor(sourcePage).single().points)
            assertContentEquals(cut.row.points, repository.partialErasesFor(sourcePage).single().erase.points)
            pasted.undo.forEach { repository.applyInkEdit(targetPage, it) }
            assertTrue(repository.loadInk(targetPage).desktopGeometry().isEmpty)
            pasted.redo.forEach { repository.applyInkEdit(targetPage, it) }
            assertEquals(expected, repository.loadInk(targetPage).desktopGeometry().projections.single().pageBounds)
            color.undo.forEach { repository.applyInkEdit(sourcePage, it) }
            val restored = repository.inkFor(sourcePage).single()
            assertEquals(original.colorArgb, restored.colorArgb)
            assertEquals(true, restored.colorFollowsTheme)
            assertContentEquals(original.points, restored.points)
        } finally { database.close(); file.delete() }
    }

    @Test
    fun colourChangesRejectForeignPageTargetsBeforeMutatingAnyRow() = runBlocking {
        val database = NotesDatabase.inMemory()
        try {
            val repository = NotesRepository(database)
            val notebook = repository.createNotebook("Validation")
            val section = repository.createSection(notebook, "Colour")
            val page = repository.createPage(section)
            val other = repository.createPage(section)
            val local = selectionLine("local", page)
            val foreign = selectionLine("foreign", other)
            repository.applyInkEdit(page, InkEdit.AddStroke(local))
            repository.applyInkEdit(other, InkEdit.AddStroke(foreign))
            assertFailsWith<IllegalArgumentException> {
                repository.applyInkEdit(page, InkEdit.SetStrokeColors(mapOf(
                    local.id to StrokeColor(0xff3b82f6.toInt(), false),
                    foreign.id to StrokeColor(0xff3b82f6.toInt(), false))))
            }
            assertEquals(local.colorArgb, repository.inkFor(page).single().colorArgb)
            assertEquals(foreign.colorArgb, repository.inkFor(other).single().colorArgb)
        } finally { database.close() }
    }

    @Test
    fun aFailedClipboardBatchRollsBackItsNewRowsAndAllEarlierChanges() = runBlocking {
        val database = NotesDatabase.inMemory()
        try {
            val repository = NotesRepository(database)
            val notebook = repository.createNotebook("Atomic clipboard")
            val section = repository.createSection(notebook, "Ink")
            val page = repository.createPage(section)
            val other = repository.createPage(section)
            val foreign = selectionLine("foreign", other)
            repository.applyInkEdit(other, InkEdit.AddStroke(foreign))
            val copy = selectionLine("copy", page)
            assertFailsWith<IllegalArgumentException> {
                repository.applyInkEdit(page, InkEdit.Batch(listOf(InkEdit.AddStroke(copy),
                    InkEdit.SetStrokeColors(mapOf(foreign.id to StrokeColor(0xff3b82f6.toInt(), false))))))
            }
            assertTrue(repository.inkFor(page).isEmpty())
            assertEquals(foreign.colorArgb, repository.inkFor(other).single().colorArgb)
        } finally { database.close() }
    }
}
