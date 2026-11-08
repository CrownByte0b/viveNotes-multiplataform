package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkBrushCatalog
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.InkLassoMove
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.portable.InkSample
import com.vivenotes.byteink.portable.InkStyle
import com.vivenotes.byteink.portable.InkTool
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkStrokeEntity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** The real engine, served at /ink/ by gradle/karma/ink-engine.js as the web app serves it. */
internal suspend fun loadedWebInk(): WebInkPages {
    WebInk.load()
    return WebInk.pages.filterNotNull().first()
}

private fun samples(points: Array<out Pair<Float, Float>>) =
    points.mapIndexed { index, (x, y) -> InkSample(x, y, index * 10L, InkTool.UNKNOWN) }

/** A stored 6 dp marker row drawn through [points], as the ink layer writes one. */
internal fun WebInkPages.strokeRow(id: String, vararg points: Pair<Float, Float>, colorArgb: Int = 0xff202020.toInt(),
                                   followsTheme: Boolean? = false, seq: Int = 0): InkStrokeEntity =
    engine.createStroke(InkStyle(InkBrushCatalog.MARKER, 0, colorArgb, 6f), samples(points)).use { stroke ->
        kit.encodeStroke(stroke, id, "page", seq, InkBrushCatalog.MARKER, 0, followsTheme, 1L).toEntity()
    }

/** An 18 dp eraser gesture through [points] that targets [targets]. */
internal fun WebInkPages.eraseEdit(id: String, targets: Set<String>, mode: EraserMode, createdAt: Long,
                                   vararg points: Pair<Float, Float>): InkEdit.PartialErase {
    val stored = checkNotNull(kit.reloadedEraseMask(samples(points), 18f)).use { mask ->
        kit.encodeErase(mask, id, "page", if (mode == EraserMode.Object) InkEraseMode.Object else InkEraseMode.Normal,
            createdAt, targets.toList())
    }
    return InkEdit.PartialErase(InkEraseEntity(id, "page", mode, stored.sizeDp, stored.points, stored.enc, createdAt), targets)
}

/** A lasso move of [targets] inside [path] by ([dx], [dy]). */
internal fun WebInkPages.moveEdit(id: String, targets: Set<String>, dx: Float, dy: Float, createdAt: Long,
                                  path: List<InkPoint>): InkEdit.Move {
    val stored = kit.encodeMove(InkLassoMove(path, targets, emptySet(), dx, dy), id, "page", createdAt)
    return InkEdit.Move(InkMoveEntity(id, "page", dx, dy, points = stored.points, enc = stored.enc, createdAt = createdAt), targets)
}
