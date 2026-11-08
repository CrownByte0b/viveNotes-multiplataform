package com.vivenotes.ink

import com.vivenotes.data.EraserMode
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The shared mapping between Room's ink rows and ByteInk's, used by desktop and the browser. */
class InkRowsTest {
    @Test
    fun strokeRowsKeepEveryColumnAndTheirOriginalBlob() {
        val entity = InkStrokeEntity("stroke", "page", 7, "calligraphy-v1-p2", 1, 3.5f, 0x80123456.toInt(), null,
            0.3f, 4, 1f, 2f, 3f, 4f, byteArrayOf(1, 2, 3), "ink/androidx1", 99L, "group", 5L)
        val stored = entity.toByteInk()
        assertSame(entity.points, stored.points, "the blob is handed over, not copied or re-encoded")
        assertEquals(listOf<Any?>("stroke", "page", 7, "calligraphy-v1-p2", 1, 3.5f, 0x80123456.toInt(), null, 0.3f, 4,
            1f, 2f, 3f, 4f, "ink/androidx1", 99L, "group", 5L), listOf(stored.id, stored.pageId, stored.seq,
            stored.brushFamily, stored.brushVersion, stored.sizeDp, stored.colorArgb, stored.colorFollowsTheme,
            stored.epsilon, stored.stabilization, stored.minX, stored.minY, stored.maxX, stored.maxY, stored.enc,
            stored.createdAt, stored.groupId, stored.deletedAt))
        assertEquals(entity, stored.toEntity())
        assertSame(entity.points, stored.toEntity().points)
    }

    @Test
    fun eraseRowsCarryTheirModeTargetsAndTombstone() {
        val row = InkEraseEntity("cut", "page", EraserMode.Object, 18f, byteArrayOf(4, 5), "ink/androidx1", 12L, 30L)
        val stored = InkEraseWithTargets(row, listOf(InkEraseTargetEntity("cut", "a"), InkEraseTargetEntity("cut", "b"))).toByteInk()
        assertEquals(listOf<Any?>("cut", "page", "Object", 18f, "ink/androidx1", 12L, 30L, listOf("a", "b")),
            listOf(stored.id, stored.pageId, stored.mode, stored.sizeDp, stored.enc, stored.createdAt,
                stored.deletedAt, stored.targetIds))
        assertSame(row.points, stored.points)
        assertEquals("Normal", row.copy(mode = EraserMode.Normal).toByteInk(listOf("a")).mode)
    }

    @Test
    fun moveRowsCarryTheirTransformTargetsAndTombstone() {
        val row = InkMoveEntity("move", "page", 5f, -7f, 1.5f, 0.5f, 10f, 20f, byteArrayOf(9), "ink/lasso-f32le1", 3L, 40L)
        val stored = InkMoveWithTargets(row, listOf(InkMoveTargetEntity("move", "a"))).toByteInk()
        assertEquals(listOf<Any?>("move", "page", 5f, -7f, 1.5f, 0.5f, 10f, 20f, "ink/lasso-f32le1", 3L, 40L, listOf("a")),
            listOf(stored.id, stored.pageId, stored.dxDp, stored.dyDp, stored.scaleX, stored.scaleY, stored.anchorX,
                stored.anchorY, stored.enc, stored.createdAt, stored.deletedAt, stored.targetIds))
        assertSame(row.points, stored.points)
    }
}
