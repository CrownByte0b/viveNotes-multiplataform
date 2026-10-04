package com.vivenotes.ink

import com.vivenotes.byteink.kit.DecodedInkOperation
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample

/** Native decoding supplies both exact geometry and lazy portable samples; stored bytes stay intact. */
object InkPageReader {
    fun read(pageId: String, strokes: List<InkStrokeEntity>, erases: List<InkEraseWithTargets>, moves: List<InkMoveWithTargets>): InkPage {
        val loaded = ViveInkPage.load(strokes.filter { it.pageId == pageId }.map { it.toByteInk() },
            erases.filter { it.erase.pageId == pageId }.map { it.toByteInk() },
            moves.filter { it.move.pageId == pageId }.map { it.toByteInk() })
        val visible = loaded.sourceStrokes.filter { it.stroke.inputs.size != 0 }.map { it.portableStroke() }
        val operations = loaded.operations.mapNotNull { operation -> when (operation) {
            is DecodedInkOperation.Erase -> if (operation.mask.inputs.size == 0) null else
                InkPageOperation.Erase(operation.id, operation.createdAt, operation.targetIds,
                    operation.mode == InkEraseMode.Object, operation.mask.brush.size, NativeInkSamples(operation.mask.inputs))
            is DecodedInkOperation.Move -> InkPageOperation.Move(operation.id, operation.createdAt, operation.targetIds,
                operation.dx, operation.dy, operation.scaleX, operation.scaleY, operation.anchor.x, operation.anchor.y,
                operation.path.map { InkSample(it.x, it.y) })
        } }
        return InkPage(pageId, visible, operations, DesktopInkPage(loaded.strokes, visible.map { it.id }))
    }
}
