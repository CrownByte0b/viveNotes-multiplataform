package com.vivenotes.ink

import com.vivenotes.data.EraserMode
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.model.ink.InkPageOperation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InkPageReaderTest {
    @Test
    fun readsAndroidFixtureAndPreservesItsPressureAndDrawOrder() {
        val high = stroke("later", seq = 2, points = InkFixtures.blob("stylus-pressure-tilt-orientation"))
        val low = stroke("earlier", seq = 1)
        val original = high.points.copyOf()

        val page = InkPageReader.read("page", listOf(high, low), emptyList(), emptyList())

        assertEquals(listOf("earlier", "later"), page.strokes.map { it.id })
        assertEquals(listOf(10f, 30f), page.strokes.first().samples.map { it.x })
        assertTrue(page.strokes.last().samples.any { it.pressure != null })
        assertTrue(original.contentEquals(high.points), "reading must leave Android's bytes alone")
    }

    @Test
    fun skipsUnknownAndBrokenBlobsWithoutChangingOtherInk() {
        val page = InkPageReader.read("page", listOf(
            stroke("good"), stroke("unknown").copy(enc = "ink/future"),
            stroke("broken").copy(points = byteArrayOf(1, 2, 3)),
            stroke("deleted").copy(deletedAt = 9),
        ), emptyList(), emptyList())
        assertEquals(listOf("good"), page.strokes.map { it.id })
    }

    @Test
    fun sortsErasesAndMovesByOperationClockAndKeepsTargets() {
        val erase = InkEraseWithTargets(
            InkEraseEntity("erase", "page", EraserMode.Normal, 8f,
                InkFixtures.twoPointStroke, InkCodec.ENCODING, 30),
            listOf(InkEraseTargetEntity("erase", "stroke")),
        )
        val move = InkMoveWithTargets(
            InkMoveEntity("move", "page", 4f, 5f, points = movePath(),
                enc = InkCodec.MOVE_ENCODING, createdAt = 20),
            listOf(InkMoveTargetEntity("move", "stroke")),
        )
        val page = InkPageReader.read("page", listOf(stroke("stroke")), listOf(erase), listOf(move))
        assertEquals(listOf("move", "erase"), page.operations.map { it.id })
        assertEquals(setOf("stroke"), page.operations.first().targetIds)
        assertEquals(2, (page.operations.last() as InkPageOperation.Erase).samples.size)
    }

    private fun stroke(id: String, seq: Int = 0, points: ByteArray = InkFixtures.twoPointStroke) =
        InkStrokeEntity(id, "page", seq, "pressure-pen", 1, 8f, 0xFFCC0000.toInt(),
            false, 0.1f, 1, 0f, 0f, 40f, 50f, points, InkCodec.ENCODING, 1)

    private fun movePath(): ByteArray = ByteBuffer.allocate(4 + 3 * 8).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(3).putFloat(0f).putFloat(0f).putFloat(50f).putFloat(0f)
        .putFloat(0f).putFloat(50f).array()
}
