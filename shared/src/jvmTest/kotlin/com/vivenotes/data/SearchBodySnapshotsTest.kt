package com.vivenotes.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.model.Block
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SearchBodySnapshotsTest {
    private fun encoded(text: String) = JsonDocumentCodec.encodeToString(PageDoc(outlines = listOf(
        Outline.Text(id = "text", blocks = listOf(Block(id = "block", runs = listOf(com.vivenotes.model.Run(text))))),
    )))

    @Test
    fun queryOnlyReadsReuseExactRowsAndSameSizeSameTimestampDirectWritesInvalidateImmediately() = runBlocking {
        val directory = Files.createTempDirectory("search-snapshots").toFile()
        val file = File(directory, "notes.db")
        val db = NotesDatabase.create(file)
        try {
            val repo = NotesRepository(db)
            val page = repo.createPage(repo.createSection(repo.createNotebook("Notebook"), "Section"))
            val firstJson = encoded("first")
            val secondJson = encoded("other")
            assertEquals(firstJson.length, secondJson.length)
            db.pageContentDao().upsert(PageContentEntity(page, firstJson, 1L))
            val first = repo.searchBodies(listOf(page))
            assertSame(first, repo.searchBodies(listOf(page)))
            db.pageContentDao().upsert(PageContentEntity(page, secondJson, 1L))
            val second = repo.searchBodies(listOf(page))
            assertNotSame(first, second)
            assertEquals(secondJson, second.single().docJson)
            BundledSQLiteDriver().open(file.absolutePath).use { external ->
                external.prepare("UPDATE page_content SET docJson = ? WHERE pageId = ?").use { statement ->
                    statement.bindText(1, firstJson); statement.bindText(2, page); statement.step()
                }
            }
            assertEquals(firstJson, repo.searchBodies(listOf(page)).single().docJson)
            assertEquals(1L, repo.searchBodies(listOf(page)).single().updatedAt)
            assertSame(repo.searchBodies(listOf(page)), repo.searchBodies(listOf(page)))
        } finally { db.close() }
        assertTrue(directory.deleteRecursively(), "database close also releases its auxiliary reader")
    }

    @Test
    fun scopesTransactionsConcurrentQueriesAndCloseKeepTheirBoundaries() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("search-snapshot-close").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repo = NotesRepository(db)
        try {
            val section = repo.createSection(repo.createNotebook("Notebook"), "Section")
            val first = repo.createPage(section)
            val second = repo.createPage(section)
            db.withTransaction {
                db.pageContentDao().upsert(PageContentEntity(first, encoded("uncommitted"), 1L))
                assertEquals(encoded("uncommitted"), repo.searchBodies(listOf(first)).single().docJson)
            }
            val rows = coroutineScope { List(24) { index -> async {
                repo.searchBodies(if (index % 2 == 0) listOf(first) else listOf(second))
            } }.awaitAll() }
            rows.forEachIndexed { index, body -> assertEquals(if (index % 2 == 0) first else second, body.single().pageId) }
            db.close()
            assertFails { repo.searchBodies(listOf(first)) }
        } finally {
            db.close()
            assertTrue(directory.deleteRecursively())
        }
    }
}
