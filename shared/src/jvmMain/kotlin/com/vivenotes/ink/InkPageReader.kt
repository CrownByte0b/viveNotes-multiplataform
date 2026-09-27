package com.vivenotes.ink

import com.vivenotes.data.EraserMode
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke

/** Converts Android's immutable rows to portable display inputs without ever rewriting the blobs. */
object InkPageReader {
    fun read(
        pageId: String,
        strokes: List<InkStrokeEntity>,
        erases: List<InkEraseWithTargets>,
        moves: List<InkMoveWithTargets>,
    ): InkPage {
        val visible = strokes.asSequence().filter { it.deletedAt == null && it.pageId == pageId }
            .sortedWith(compareBy(InkStrokeEntity::seq, InkStrokeEntity::id))
            .mapNotNull { row ->
                if (row.enc != InkCodec.ENCODING || !row.sizeDp.isFinite() || row.sizeDp <= 0f) return@mapNotNull null
                val inputs = runCatching { StrokeInputBatchCodec.decode(row.points) }.getOrNull()
                    ?: return@mapNotNull null
                if (inputs.size == 0) return@mapNotNull null
                VisibleInkStroke(row.id, row.brushFamily, row.brushVersion, row.sizeDp,
                    row.colorArgb, row.colorFollowsTheme, inputs.samples())
            }.toList()
        val operations = buildList {
            erases.forEach { stored ->
                val row = stored.erase
                if (row.pageId != pageId || row.deletedAt != null || row.enc != InkCodec.ENCODING ||
                    !row.sizeDp.isFinite() || row.sizeDp <= 0f) return@forEach
                val inputs = runCatching { StrokeInputBatchCodec.decode(row.points) }.getOrNull()
                    ?: return@forEach
                if (inputs.size == 0) return@forEach
                add(InkPageOperation.Erase(row.id, row.createdAt,
                    stored.targets.mapTo(mutableSetOf()) { it.strokeId },
                    row.mode == EraserMode.Object, row.sizeDp, inputs.samples()))
            }
            moves.forEach { stored ->
                val row = stored.move
                if (row.pageId != pageId || row.deletedAt != null || InkCodec.decodeMove(row) == null ||
                    !listOf(row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX, row.anchorY)
                        .all(Float::isFinite) || row.scaleX <= 0f || row.scaleY <= 0f) return@forEach
                add(InkPageOperation.Move(row.id, row.createdAt,
                    stored.targets.mapTo(mutableSetOf()) { it.strokeId },
                    row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX, row.anchorY))
            }
        }.sortedWith(compareBy(InkPageOperation::createdAt, InkPageOperation::id))
        return InkPage(pageId, visible, operations)
    }

    private fun StrokeInputs.samples(): List<InkSample> = List(size) { index ->
        InkSample(x[index], y[index], pressure?.get(index))
    }
}
