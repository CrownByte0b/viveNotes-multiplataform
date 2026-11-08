package com.vivenotes.ink

import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.StoredInkMove
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity

// Room's ink rows and ByteInk's carry the same Android columns. The point blobs are handed over
// as they are, never re-encoded.

internal fun InkStrokeEntity.toByteInk() = StoredInkStroke(id, pageId, seq, brushFamily,
    brushVersion, sizeDp, colorArgb, colorFollowsTheme, epsilon, stabilization, minX, minY,
    maxX, maxY, points, enc, createdAt, groupId, deletedAt)

internal fun StoredInkStroke.toEntity() = InkStrokeEntity(id, pageId, seq, brushFamily,
    brushVersion, sizeDp, colorArgb, colorFollowsTheme, epsilon, stabilization, minX, minY,
    maxX, maxY, points, enc, createdAt, groupId, deletedAt)

internal fun InkEraseWithTargets.toByteInk(): StoredInkErase = erase.toByteInk(targets.map { it.strokeId })

internal fun InkEraseEntity.toByteInk(targetIds: Collection<String>): StoredInkErase =
    StoredInkErase(id, pageId, mode.name, sizeDp, points, enc, createdAt, deletedAt, targetIds.toList())

internal fun InkMoveWithTargets.toByteInk(): StoredInkMove = move.toByteInk(targets.map { it.strokeId })

internal fun InkMoveEntity.toByteInk(targetIds: Collection<String>): StoredInkMove =
    StoredInkMove(id, pageId, dxDp, dyDp, scaleX, scaleY, anchorX, anchorY, points, enc, createdAt,
        deletedAt, targetIds.toList())
