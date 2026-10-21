package com.vivenotes.data

import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vivenotes.model.Block
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.TextDocumentCodec
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PersistencePerformanceTest {
    private fun doc(text: String) = PageDoc(outlines = listOf(Outline.Text(id = "text",
        blocks = listOf(Block(id = "block", runs = listOf(com.vivenotes.model.Run(text)))))))

    @Test
    fun encodingRunsOnTheInjectedWorkerAndRepeatedLoadsShareOneImmutableDecode() = runBlocking {
        val threads = CopyOnWriteArrayList<String>()
        val codec = object : TextDocumentCodec by JsonDocumentCodec {
            override fun encodeToString(doc: PageDoc): String {
                threads += Thread.currentThread().name
                return JsonDocumentCodec.encodeToString(doc)
            }
        }
        Executors.newSingleThreadExecutor { task -> Thread(task, "document-cpu-test") }.asCoroutineDispatcher().use { cpu ->
            val db = NotesDatabase.inMemory()
            try {
                val repo = NotesRepository(db, codec = codec, cpu = cpu)
                val page = repo.createPage(repo.createSection(repo.createNotebook("Notebook"), "Section"))
                threads.clear()
                repo.saveDoc(page, doc("saved"))
                assertEquals(1, threads.size)
                assertTrue(threads.single().startsWith("document-cpu-test"), threads.toString())
                val loaded = assertIs<PageLoad.Loaded>(repo.loadDoc(page))
                val observed = assertIs<PageLoad.Loaded>(repo.observeDoc(page).first())
                assertSame(loaded.doc, observed.doc)
                repo.saveDoc(page, doc("replacement"))
                val replaced = assertIs<PageLoad.Loaded>(repo.loadDoc(page))
                assertEquals(doc("replacement"), replaced.doc)
                assertNotSame(loaded.doc, replaced.doc)
            } finally { db.close() }
        }
    }

    @Test
    fun decodeCacheIsSingleFlightBoundedAndIncludesTheFormatAndWholeBody() = runBlocking {
        val cache = DecodedDocumentCache(maxEntries = 2, maxCharacters = 10)
        var decodes = 0
        val first = coroutineScope { List(20) { async {
            cache.get("json", "body") { decodes++; PageLoad.Loaded(doc("one")) }
        } }.awaitAll() }
        assertEquals(1, decodes)
        first.forEach { assertSame(first.first(), it) }
        cache.get("other", "body") { decodes++; PageLoad.Loaded(doc("other format")) }
        cache.get("json", "third") { decodes++; PageLoad.Loaded(doc("third")) }
        cache.get("json", "body") { decodes++; PageLoad.Loaded(doc("evicted")) }
        assertEquals(4, decodes)
    }

    @Test
    fun checkpointStateQueriesOmitGeometryAndKeepExactActiveOrderAndRestore() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val bundled = BundledSQLiteDriver()
        val driver = object : SQLiteDriver by bundled {
            override fun open(fileName: String): SQLiteConnection {
                val connection = bundled.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement { queries += sql; return connection.prepare(sql) }
                }
            }
        }
        val db = Room.inMemoryDatabaseBuilder<NotesDatabase>().setDriver(driver)
            .setQueryCoroutineContext(kotlinx.coroutines.Dispatchers.IO).addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK).build()
        var now = 1_000_000L
        try {
            val repo = NotesRepository(db, clock = { now })
            val page = repo.createPage(repo.createSection(repo.createNotebook("Notebook"), "Section"))
            val points = ByteArray(128 * 1024) { (it % 251).toByte() }
            fun row(id: String, seq: Int, deleted: Long? = null) = InkStrokeEntity(
                id, page, seq, "marker", 1, 3f, 0xff112233.toInt(), false,
                0.1f, 0, 0f, 0f, 10f, 10f, points, "opaque/test", now,
                deletedAt = deleted, groupId = "group-$id",
            )
            db.inkStrokeDao().insert(listOf(row("b", 1), row("a", 1), row("deleted", 0, now)))
            val states = db.inkStrokeDao().revisionStates(page)
            assertEquals(listOf("a", "b"), states.map { it.id })
            assertEquals(listOf("group-a", "group-b"), states.map { it.groupId })
            repo.saveDoc(page, doc("first"))
            now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS
            repo.saveDoc(page, doc("second"))
            val revision = repo.revisionHistory(page).first()
            val snapshot = InkRevisionPayload.unpack(db.pageRevisionDao().byId(page, revision.id)!!)
            assertEquals(states.map { it.id }, snapshot.strokes.map { it.id })
            assertTrue(queries.none { it.startsWith("SELECT * FROM ink_strokes WHERE pageId") ||
                it.startsWith("SELECT * FROM ink_erases WHERE pageId") || it.startsWith("SELECT * FROM ink_moves WHERE pageId") },
                "checkpoint must not fetch point blobs or target relations")
            repo.restoreRevision(page, revision.id)
            assertEquals(doc("first"), assertIs<PageLoad.Loaded>(repo.loadDoc(page)).doc)
            assertTrue(repo.inkFor(page).all { it.points.contentEquals(points) })
        } finally { db.close() }
    }
}
