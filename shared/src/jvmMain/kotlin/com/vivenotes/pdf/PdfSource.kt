package com.vivenotes.pdf

import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.model.PageDoc
import com.vivenotes.model.migrated
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal data class PdfSourceIdentity(val title: String, val createdAt: Long, val doc: PageDoc, val inkSha256: String)

internal class PdfSource(
    val id: String,
    val identity: PdfSourceIdentity,
    private val strokes: List<InkStrokeEntity>,
    private val erases: List<InkEraseWithTargets>,
    private val moves: List<InkMoveWithTargets>,
) {
    val title get() = identity.title
    val createdAt get() = identity.createdAt
    val doc get() = identity.doc
    val style get() = doc.style
    val decodedStrokes by lazy { InkPageReader.read(id, strokes, erases, moves).desktopGeometry().projections }
}

internal suspend fun loadPdfSource(repository: NotesRepository, pageId: String): PdfSource? {
    val entity = repository.pageById(pageId) ?: return null
    val doc = (repository.loadDoc(pageId) as? PageLoad.Loaded)?.doc?.migrated() ?: PageDoc()
    val strokes = repository.inkFor(pageId)
    val erases = repository.partialErasesFor(pageId)
    val moves = repository.inkMovesFor(pageId)
    return PdfSource(pageId, PdfSourceIdentity(entity.title, entity.createdAt, doc,
        pdfInkFingerprint(strokes, erases, moves)), strokes, erases, moves)
}

/** Includes all rendering/order/target metadata, not timestamps alone or entity id/points equality. */
private fun pdfInkFingerprint(strokes: List<InkStrokeEntity>, erases: List<InkEraseWithTargets>,
    moves: List<InkMoveWithTargets>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { out ->
        fun bytes(value: ByteArray) { out.writeInt(value.size); out.write(value) }
        fun optional(value: String?) { out.writeBoolean(value != null); if (value != null) out.writeUTF(value) }
        fun deleted(value: Long?) { out.writeBoolean(value != null); if (value != null) out.writeLong(value) }
        out.writeInt(strokes.size)
        for (row in strokes) {
            out.writeUTF(row.id); out.writeUTF(row.pageId); out.writeInt(row.seq)
            out.writeUTF(row.brushFamily); out.writeInt(row.brushVersion); out.writeFloat(row.sizeDp)
            out.writeInt(row.colorArgb)
            out.writeByte(when (row.colorFollowsTheme) { null -> -1; false -> 0; true -> 1 })
            out.writeFloat(row.epsilon); out.writeInt(row.stabilization)
            out.writeFloat(row.minX); out.writeFloat(row.minY); out.writeFloat(row.maxX); out.writeFloat(row.maxY)
            bytes(row.points); out.writeUTF(row.enc); out.writeLong(row.createdAt)
            optional(row.groupId); deleted(row.deletedAt)
        }
        out.writeInt(erases.size)
        for ((row, targets) in erases) {
            out.writeUTF(row.id); out.writeUTF(row.pageId); out.writeUTF(row.mode.name)
            out.writeFloat(row.sizeDp); bytes(row.points); out.writeUTF(row.enc); out.writeLong(row.createdAt)
            deleted(row.deletedAt); out.writeInt(targets.size)
            targets.forEach { out.writeUTF(it.strokeId) }
        }
        out.writeInt(moves.size)
        for ((row, targets) in moves) {
            out.writeUTF(row.id); out.writeUTF(row.pageId)
            out.writeFloat(row.dxDp); out.writeFloat(row.dyDp); out.writeFloat(row.scaleX); out.writeFloat(row.scaleY)
            out.writeFloat(row.anchorX); out.writeFloat(row.anchorY)
            bytes(row.points); out.writeUTF(row.enc); out.writeLong(row.createdAt); deleted(row.deletedAt)
            out.writeInt(targets.size); targets.forEach { out.writeUTF(it.strokeId) }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
