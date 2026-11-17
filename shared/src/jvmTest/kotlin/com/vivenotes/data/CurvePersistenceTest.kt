package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.vertices
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** The Android curve ViewModel reload contract, using the desktop's real repository and SQLite. */
class CurvePersistenceTest {
    private fun placed(): WorkspaceState {
        val base = WorkspaceState.demo()
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList())) }
            .setShapeSettings(ShapeToolSettings(kind = ShapeKind.Curve))
            .toggleShapeTool().createShape(40f, 220f, 280f, 380f)
    }

    private fun WorkspaceState.curve(): Outline.Shape = selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().single()

    @Test
    fun insertedAndMovedVerticesAndClipboardCopiesSurviveDatabaseReopen() = runBlocking {
        val directory = Files.createTempDirectory("curve-persistence").toFile()
        val file = File(directory, "notes.db")
        var db = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(db)
            val section = repository.createSection(repository.createNotebook("Curves"), "Draw")
            val pageId = repository.createPage(section)
            val initial = placed()
            val curve = initial.curve()
            val added = initial.addShapeVertex(curve.id, curve.segments.last().id)
            val moved = added.moveShapeVertex(curve.id, 1, 100f, 300f)
            val expected = moved.selectedPage!!.document
            repository.saveDoc(pageId, expected)
            db.close(); db = NotesDatabase.create(file); repository = NotesRepository(db)
            val loaded = (repository.loadDoc(pageId) as PageLoad.Loaded).doc
            assertEquals(expected, loaded)
            val restored = moved.updatePage(moved.selectedPageId) { it.copy(document = loaded) }
            assertEquals(4, restored.curve().vertices().size)
            assertTrue(restored.curve().segments.all { it.cubic != null })

            val copied = restored.copySelectedObjects().pasteCanvasAt(400f, 500f)
            val duplicate = copied.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().first { it.id != curve.id }
            val translated = moved.curve().translated(duplicate.x - moved.curve().x, duplicate.y - moved.curve().y)
            duplicate.segments.zip(translated.segments).forEach { (actual, reference) ->
                assertEquals(reference.copy(id = actual.id), actual)
            }
            assertTrue(duplicate.segments.none { copy -> curve.segments.any { copy.id == it.id } })
            repository.saveDoc(pageId, copied.selectedPage!!.document)
            db.close(); db = NotesDatabase.create(file); repository = NotesRepository(db)
            val reopened = (repository.loadDoc(pageId) as PageLoad.Loaded).doc
            assertEquals(copied.selectedPage!!.document, reopened)
            assertEquals(duplicate, reopened.outlines.first { it.id == duplicate.id })
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test
    fun vertexRemovalUndoRedoAndReadditionPersistAcrossReload() = runBlocking {
        val directory = Files.createTempDirectory("curve-removal").toFile()
        val file = File(directory, "notes.db")
        var db = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(db)
            val section = repository.createSection(repository.createNotebook("Curves"), "Draw")
            val pageId = repository.createPage(section)
            val initial = placed()
            val curve = initial.curve()
            val removed = initial.removeShapeVertex(curve.id, curve.segments.last().id, false)
            assertEquals(2, removed.curve().vertices().size)
            assertEquals(curve, removed.undoStructure().curve())
            val redone = removed.undoStructure().redoStructure()
            assertEquals(removed.curve(), redone.curve())
            repository.saveDoc(pageId, redone.selectedPage!!.document)
            db.close(); db = NotesDatabase.create(file); repository = NotesRepository(db)
            val loaded = (repository.loadDoc(pageId) as PageLoad.Loaded).doc
            assertEquals(redone.selectedPage!!.document, loaded)
            val restored = redone.updatePage(redone.selectedPageId) { it.copy(document = loaded) }
            val readded = restored.addShapeVertex(curve.id, restored.curve().segments.single().id)
            assertEquals(3, readded.curve().vertices().size)
            repository.saveDoc(pageId, readded.selectedPage!!.document)
            db.close(); db = NotesDatabase.create(file); repository = NotesRepository(db)
            assertEquals(readded.selectedPage!!.document, (repository.loadDoc(pageId) as PageLoad.Loaded).doc)
        } finally { db.close(); directory.deleteRecursively() }
    }
}
