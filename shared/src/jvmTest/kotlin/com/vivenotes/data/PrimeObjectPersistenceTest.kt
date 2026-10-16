package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.*
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PrimeObjectPersistenceTest {
    @Test
    fun aLockedGroupReopensAcrossAllFourKindsAndCopiesKeepOnlyThePictureReference() = runBlocking {
        val directory = Files.createTempDirectory("prime-objects").toFile()
        val file = File(directory, NotesDatabase.FILE_NAME)
        var database = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(database)
            val notebook = repository.createNotebook("Objects")
            val section = repository.createSection(notebook, "Group")
            val page = repository.createPage(section)
            val attachmentDirectory = File(directory, AttachmentStore.DIRECTORY)
            var attachments = AttachmentStore(attachmentDirectory, database)
            val picture = assertNotNull(attachments.import(png()))
            val bytes = assertNotNull(attachments.bytes(picture.attachmentId))
            val table = newTable(2, 2, 250f, 220f, inkOnly = false)
            val cell = table.rows.first().cells.first()
            val outlines = listOf(
                Outline.Image("picture", 100f, 220f, 80f, attachmentId = picture.attachmentId, height = 60f),
                table.withCellBlocks(mapOf(cell.id to listOf(Block("block", runs = listOf(Run("Styled cell", setOf(Mark.Bold))))))),
                Outline.Equation("equation", 650f, 220f, 80f, 60f, "x^2"),
                Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
                    segments = seedSegments(ShapeKind.Rectangle, 800f, 220f, 880f, 280f, ::newId)).withRecomputedBounds(),
            )
            fun state(document: PageDoc): WorkspaceState {
                val base = WorkspaceState.demo()
                return base.updatePage(base.selectedPageId) { it.copy(document = document) }
            }
            val locked = state(PageDoc(outlines = outlines))
                .selectObjectsInRect(80f, 200f, 900f, 500f).toggleObjectLock()
            val expected = locked.selectedPage!!.document
            repository.saveDoc(page, expected)
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            attachments = AttachmentStore(attachmentDirectory, database)

            val loaded = (repository.loadDoc(page) as PageLoad.Loaded).doc
            assertEquals(expected, loaded)
            val selected = state(loaded).selectObject("picture")
            assertEquals(outlines.map { it.id }.toSet(), selected.selectedObjectIds)
            assertTrue(selected.selectedObjectsLocked)
            assertEquals(loaded, selected.moveSelectedObjects(40f, 60f).selectedPage!!.document)
            val copied = selected.copySelectedObjects().pasteCanvasAt(100f, 600f)
            val duplicateImage = copied.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().last()
            assertEquals(picture.attachmentId, duplicateImage.attachmentId)
            assertNotEquals("picture", duplicateImage.id)
            assertNull(duplicateImage.lockGroup)
            assertContentEquals(bytes, attachments.bytes(picture.attachmentId))
            assertEquals(listOf(picture.attachmentId), database.attachmentDao().allIds())

            val unlocked = selected.toggleObjectLock().resizeSelectedObjects(100f, 220f, 2f, 0.5f)
            repository.saveDoc(page, unlocked.selectedPage!!.document)
            database.close()
            database = NotesDatabase.create(file)
            repository = NotesRepository(database)
            val resized = (repository.loadDoc(page) as PageLoad.Loaded).doc
            assertEquals(unlocked.selectedPage!!.document, resized)
            val image = resized.outlines.filterIsInstance<Outline.Image>().single()
            assertEquals(160f, image.width)
            assertEquals(30f, image.height)
            assertEquals(picture.attachmentId, image.attachmentId)
            assertTrue(resized.outlines.all { it.lockGroup == null })
            assertTrue(Mark.Bold in resized.outlines.filterIsInstance<Outline.Table>().single()
                .rows.first().cells.first().blocks.first().runs.first().marks)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun png(): ByteArray = Surface.makeRasterN32Premul(20, 10).use { surface ->
        surface.canvas.clear(0x883584e4.toInt())
        surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
    }
}
