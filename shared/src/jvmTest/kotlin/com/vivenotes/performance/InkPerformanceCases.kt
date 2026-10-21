package com.vivenotes.performance

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopEdit
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.toEntity
import com.vivenotes.ink.withDesktopEdit
import com.vivenotes.ink.withDesktopEdits
import com.vivenotes.model.ink.InkPage
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal fun measureInkPerformance(runner: BaselineMeasurements) {
    for (count in listOf(1_000, 10_000, 40_000)) {
        val fixture = performanceInkAppendFixture(count)
        val ordinary = fixture.before127.withDesktopEdit(fixture.append127)
        val boundary = fixture.before128.withDesktopEdit(fixture.append128)
        check(ordinary.strokes.size == count + 127)
        check(boundary.strokes.size == count + 128)
        check(ordinary.desktopGeometry().projections.size == count + 127)
        check(boundary.desktopGeometry().projections.size == count + 128)
        runner.measure("ink.apply_127th_append", count, fixture.fingerprint127, fixedOps = 1) {
            fixture.before127.withDesktopEdit(fixture.append127)
        }
        runner.measure("ink.apply_128th_append", count, fixture.fingerprint128, fixedOps = 1) {
            fixture.before128.withDesktopEdit(fixture.append128)
        }
    }
}

internal class InkAppendFixture(
    val initial: InkPage,
    val before127: InkPage,
    val before128: InkPage,
    val append127: InkEdit.AddStroke,
    val append128: InkEdit.AddStroke,
    val fingerprint127: String,
    val fingerprint128: String,
)

internal fun performanceInkAppendFixture(baseStrokes: Int): InkAppendFixture {
    require(baseStrokes > 0)
    val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff668877.toInt(), 2f)
    fun stroke(index: Int): Stroke {
        val x = index % 200 * 12f
        val y = index / 200 * 12f
        return Stroke(tool.brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, x + 1f, y + 3f, 0L)
            add(InputToolType.MOUSE, x + 5f, y + 6f, 10L)
            add(InputToolType.MOUSE, x + 9f, y + 3f, 20L)
        })
    }
    val rows = List(baseStrokes) { index ->
        ViveInkCodec.encodeStroke(stroke(index), "base-$index", INK_FIXTURE_PAGE, index,
            ViveBrushes.MARKER, 0, false, INK_FIXTURE_TIME).toEntity()
    }
    val additions = List(128) { index ->
        tool.complete(stroke(baseStrokes + index), "append-$index", INK_FIXTURE_PAGE,
            baseStrokes + index, INK_FIXTURE_TIME).desktopEdit()
    }
    val initial = InkPageReader.read(INK_FIXTURE_PAGE, rows, emptyList(), emptyList())
    val before127 = initial.withDesktopEdits(additions.take(126))
    val before128 = before127.withDesktopEdit(additions[126])
    return InkAppendFixture(initial, before127, before128, additions[126], additions[127],
        inkAppendFingerprint(rows, additions.take(127), 126),
        inkAppendFingerprint(rows, additions, 127))
}

private fun inkAppendFingerprint(base: List<InkStrokeEntity>, additions: List<InkEdit.AddStroke>,
    startingTailSize: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { data ->
        data.writeUTF("vive-ink-append-fixture/1")
        data.writeInt(base.size)
        data.writeInt(startingTailSize)
        for (row in base + additions.map { it.row }) {
            data.writeUTF(row.id)
            data.writeUTF(row.pageId)
            data.writeInt(row.seq)
            data.writeUTF(row.brushFamily)
            data.writeInt(row.brushVersion)
            data.writeFloat(row.sizeDp)
            data.writeInt(row.colorArgb)
            data.writeByte(when (row.colorFollowsTheme) { null -> -1; false -> 0; true -> 1 })
            data.writeFloat(row.epsilon)
            data.writeInt(row.stabilization)
            data.writeFloat(row.minX)
            data.writeFloat(row.minY)
            data.writeFloat(row.maxX)
            data.writeFloat(row.maxY)
            data.writeUTF(row.enc)
            data.writeLong(row.createdAt)
            data.writeInt(row.points.size)
            data.write(row.points)
            check(row.groupId == null && row.deletedAt == null)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val INK_FIXTURE_PAGE = "performance-append-page"
private const val INK_FIXTURE_TIME = 1_000_000L
