package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.selectionLine
import com.vivenotes.model.*
import com.vivenotes.model.ink.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class AutomaticColorPersistenceTest {
    @Test
    fun everyColorIntentSurvivesSqliteAndDocumentCodecReopeningWithoutRewritingInk() = runBlocking {
        val file = Files.createTempFile("automatic-colors", ".db").toFile()
        var database = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(database)
            val notebook = repository.createNotebook("Colors")
            val section = repository.createSection(notebook, "Intents")
            val pageId = repository.createPage(section)
            val cases = listOf(true, false, null).flatMap { flag ->
                listOf(0xff000000.toInt(), -1, 0xffe53935.toInt(), 0x66ffffff).map { it to flag }
            }
            val rows = cases.mapIndexed { index, (argb, follows) ->
                selectionLine("stroke-$index", pageId, 50f + index * 20f).copy(colorArgb = argb, colorFollowsTheme = follows)
            }
            rows.forEach { repository.applyInkEdit(pageId, InkEdit.AddStroke(it)) }
            val outlines = cases.flatMap { (argb, follows) -> listOf(
                newTable(1, 1, borderArgb = argb, borderFollowsTheme = follows),
                Outline.Shape(id = newId(), kind = ShapeKind.Rectangle, segments = seedSegments(ShapeKind.Rectangle, 10f, 10f, 110f, 90f) { newId() },
                    borderArgb = argb, borderFollowsTheme = follows).withRecomputedBounds()) }
            val document = PageDoc(outlines = outlines)
            repository.saveDoc(pageId, document)
            repeat(2) {
                database.close()
                database = NotesDatabase.create(file)
                repository = NotesRepository(database)
                assertEquals(document, (repository.loadDoc(pageId) as PageLoad.Loaded).doc)
                val ink = repository.loadInk(pageId)
                assertEquals(cases, ink.strokes.map { it.colorArgb to it.colorFollowsTheme })
                for (stored in repository.inkFor(pageId)) {
                    val original = rows.single { it.id == stored.id }
                    assertEquals(original.colorArgb, stored.colorArgb)
                    assertEquals(original.colorFollowsTheme, stored.colorFollowsTheme)
                    assertContentEquals(original.points, stored.points)
                }
            }
        } finally { database.close(); file.delete() }
    }
}
