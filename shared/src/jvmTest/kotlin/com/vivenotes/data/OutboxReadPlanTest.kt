package com.vivenotes.data

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.SYNC_OUTBOX_ORDER
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxReadPlanTest {
    @Test
    fun rankQueriesPreserveParentOrderAndKeysetsWithoutATemporarySorter() = runBlocking {
        val db = NotesDatabase.inMemory()
        try {
            db.useWriterConnection { writer ->
                for (kind in SYNC_OUTBOX_ORDER.reversed()) repeat(12) { index ->
                    writer.usePrepared("INSERT INTO sync_outbox(kind,entityId,generation,changedAt) VALUES(?,?,1,0)") {
                        it.bindText(1, kind); it.bindText(2, "row-${index.toString().padStart(3, '0')}"); it.step()
                    }
                }
            }
            val rows = db.syncDao().outbox(1_000)
            assertEquals(SYNC_OUTBOX_ORDER.flatMap { kind -> List(12) { kind } }, rows.map { it.kind })
            val first = db.syncDao().outboxKind("inkStroke", 5)
            val rest = db.syncDao().outboxKindAfter("inkStroke", first.last().entityId, 20)
            assertEquals(12, (first + rest).map { it.entityId }.distinct().size)
            assertEquals((first + rest).map { it.entityId }.sorted(), (first + rest).map { it.entityId })
            val plan = db.useReaderConnection { reader ->
                reader.usePrepared("EXPLAIN QUERY PLAN SELECT * FROM sync_outbox WHERE kind = 'inkStroke' " +
                    "AND entityId > 'row-004' ORDER BY entityId LIMIT 512") { statement -> buildList {
                    while (statement.step()) add(statement.getText(3))
                } }
            }
            assertTrue(plan.any { it.contains("INDEX") }, plan.toString())
            assertFalse(plan.any { it.contains("TEMP B-TREE") }, plan.toString())
        } finally { db.close() }
    }
}
