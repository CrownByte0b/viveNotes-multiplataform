package com.vivenotes.data

import androidx.ink.brush.InputToolType
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.*
import com.vivenotes.model.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DrawToolsPersistenceTest {
    @Test
    fun objectErasingOneFragmentSurvivesReopenAndUndoWithoutRewritingTheSource() = runBlocking {
        val file = Files.createTempFile("draw-object-erase", ".db").toFile()
        var db = NotesDatabase.create(file)
        try {
            var repo = NotesRepository(db)
            val notebook = repo.createNotebook("Draw")
            val section = repo.createSection(notebook, "Ink")
            val pageId = repo.createPage(section)
            val original = selectionLine(pageId = pageId)
            repo.applyInkEdit(pageId, InkEdit.AddStroke(original))
            val (cut, _) = splitSelectionPage(pageId)
            repo.applyInkEdit(pageId, cut)
            val before = repo.loadInk(pageId)
            val gesture = DesktopPartialEraseGesture(before, 1f, 4f, mode = EraserMode.Object)
            gesture.observe(InkPointerSample(30f, 200f, 100L, InputToolType.MOUSE))
            val (edit, after) = assertNotNull(gesture.request().prepare())
            repo.applyInkEdit(pageId, edit)
            val expected = after.desktopGeometry().projections.single().pageBounds
            db.close(); db = NotesDatabase.create(file); repo = NotesRepository(db)
            assertEquals(expected, repo.loadInk(pageId).desktopGeometry().projections.single().pageBounds)
            assertContentEquals(original.points, repo.inkFor(pageId).single().points)
            assertContentEquals(edit.row.points, repo.partialErasesFor(pageId).single { it.erase.id == edit.row.id }.erase.points)
            repo.applyInkEdit(pageId, edit.copy(active = false))
            assertEquals(before.desktopGeometry().projections.map { it.pageBounds }, repo.loadInk(pageId).desktopGeometry().projections.map { it.pageBounds })
            repo.applyInkEdit(pageId, edit)
            assertEquals(expected, repo.loadInk(pageId).desktopGeometry().projections.single().pageBounds)
        } finally { db.close(); file.delete() }
    }

    @Test
    fun aTypedTableAndMeasuredEquationRoundTripWithAndroidCodecAndDatabase() = runBlocking {
        val file = Files.createTempFile("draw-objects", ".db").toFile()
        var db = NotesDatabase.create(file)
        try {
            var repo = NotesRepository(db)
            val notebook = repo.createNotebook("Objects")
            val section = repo.createSection(notebook, "Draw")
            val pageId = repo.createPage(section)
            val table = newTable(4, 3, x = 100f, y = 240f, headerRow = true, inkOnly = false)
                .withCellBlocks(mapOf("not-a-cell" to listOf(Block.of("Ignored"))))
            val cell = table.rows[1].cells[2]
            val typed = table.withCellBlocks(mapOf(cell.id to listOf(Block.of("Typed cell"))))
            val equation = Outline.Equation("equation", 100f, 120f, 80f, 30f, "x^2 + 1")
            val expected = PageDoc(outlines = listOf(typed, equation))
            repo.saveDoc(pageId, expected)
            db.close(); db = NotesDatabase.create(file); repo = NotesRepository(db)
            val loaded = (repo.loadDoc(pageId) as PageLoad.Loaded).doc
            assertEquals(expected, loaded)
            assertEquals("Typed cell", (loaded.outlines.first() as Outline.Table).cellAt(1, 2)!!.plainText)
        } finally { db.close(); file.delete() }
    }
}
