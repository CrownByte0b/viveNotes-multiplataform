package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.data.InkEdit
import com.vivenotes.model.ink.InkPageOperation
import kotlin.test.*

class DesktopInkOverlayTest {
    @Test
    fun appendsKeepLargeBaseAndIndexAndUseBoundedAdditions() {
        val template = authored("base").row
        val initial = InkPageReader.read("page", List(4000) { template.copy(id = "base-$it", seq = it).toEntity() }, emptyList(), emptyList())
        val base = initial.desktopGeometry().base
        val index = base.index
        var page = initial
        repeat(32) { page = page.withDesktopEdit(authored("new-$it").desktopEdit()) }
        val native = page.desktopGeometry()
        assertSame(base, native.base)
        assertSame(index, native.base.index)
        assertEquals(32, native.additions.projections.size)
        assertEquals(4032, native.projections.size)
        assertEquals(initial.strokes.map { it.id } + (0 until 32).map { "new-$it" }, page.strokes.map { it.id })
        assertEquals(4000, initial.desktopGeometry().projections.size, "previous snapshots are immutable")
    }

    @Test
    fun pendingEditsApplyOnceInOrderWithDuplicateAcknowledgementsAndReadds() {
        val base = authored("base")
        val initial = InkPageReader.read("page", listOf(base.row.toEntity()), emptyList(), emptyList())
        val first = authored("first").desktopEdit()
        val second = authored("second", x = 100f).desktopEdit()
        val edits = listOf(base.desktopEdit(), first, first, InkEdit.EraseStrokes(setOf("first", "base")),
            second, first, InkEdit.EraseStrokes(setOf("missing")))
        val batch = initial.withDesktopEdits(edits)
        val sequential = edits.fold(initial) { page, edit -> page.withDesktopEdit(edit) }
        assertEquals(listOf("second", "first"), batch.strokes.map { it.id })
        assertEquals(sequential.strokes, batch.strokes)
        assertEquals(sequential.desktopGeometry().projections.map { it.id to it.pageBounds }, batch.desktopGeometry().projections.map { it.id to it.pageBounds })
        assertSame(initial.desktopGeometry().base, batch.desktopGeometry().base)
        assertEquals(setOf("base"), batch.desktopGeometry().excludedRows)
    }

    @Test
    fun appendAndTombstoneThresholdsCompactAndRetainOrderAndGeometry() {
        val original = authored("base")
        var page = InkPageReader.read("page", listOf(original.row.toEntity()), emptyList(), emptyList())
        val previous = page.desktopGeometry().base
        val edits = List(DesktopInkPage.ADDITION_LIMIT) { authored("new-$it").desktopEdit() }
        page = page.withDesktopEdits(edits)
        assertNotSame(previous, page.desktopGeometry().base)
        assertEquals(0, page.desktopGeometry().additions.projections.size)
        assertEquals(listOf("base") + edits.map { it.row.id }, page.strokes.map { it.id })
        val template = original.row
        val many = InkPageReader.read("page", List(300) { template.copy(id = "r-$it", seq = it).toEntity() }, emptyList(), emptyList())
        val erased = many.withDesktopEdit(InkEdit.EraseStrokes((0 until DesktopInkPage.TOMBSTONE_LIMIT).mapTo(HashSet()) { "r-$it" }))
        assertNotSame(many.desktopGeometry().base, erased.desktopGeometry().base)
        assertTrue(erased.desktopGeometry().excludedRows.isEmpty())
        assertEquals((256 until 300).map { "r-$it" }, erased.desktopGeometry().projections.map { it.id })
        assertEquals(300, many.desktopGeometry().projections.size)
    }

    @Test
    fun portableInputsAreLazyAndSharedWithDecodedNativeRows() {
        val original = authored("pressure")
        val page = InkPageReader.read("page", listOf(original.row.toEntity()), emptyList(), emptyList())
        val samples = page.strokes.single().samples as NativeInkSamples
        assertEquals(0, samples.materializations)
        assertEquals(original.canonicalStroke.inputs.size, samples.size)
        assertEquals(0, samples.materializations)
        val native = page.desktopGeometry().projections.single().stroke
        assertEquals(native.inputs[3].x, samples[3].x)
        assertEquals(native.inputs[3].pressure, samples[3].pressure)
        assertEquals(1, samples.materializations)
        samples.toList(); samples.toList()
        assertEquals(1, samples.materializations)
    }

    @Test
    fun canonicalPayloadSurvivesPendingBatchAndExactlyMatchesReloadedMesh() {
        val authored = authored("new")
        val edit = authored.desktopEdit()
        val initial = InkPageReader.read("page", emptyList(), emptyList(), emptyList())
        val pending = initial.withDesktopEdits(listOf(edit))
        assertSame((edit.geometry as DesktopInkStroke).projection, pending.desktopGeometry().projections.single())
        val loaded = InkPageReader.read("page", listOf(edit.row), emptyList(), emptyList())
        val a = pending.desktopGeometry().projections.single().stroke
        val b = loaded.desktopGeometry().projections.single().stroke
        repeat(a.shape.getRenderGroupCount()) { group ->
            val am = InkMeshes.triangles(a.shape, group); val bm = InkMeshes.triangles(b.shape, group)
            assertEquals(am.size, bm.size)
            am.zip(bm).forEach { (x, y) -> assertContentEquals(x.positions, y.positions); assertContentEquals(x.triangles, y.triangles) }
        }
    }

    @Test
    fun payloadCannotSubstituteGeometryOfAnotherRowAndExtentsShrinkAfterErase() {
        val initial = InkPageReader.read("page", emptyList(), emptyList(), emptyList())
        val old = authored("near").desktopEdit()
        val far = authored("far", x = 1000f).desktopEdit()
        val swapped = InkEdit.AddStroke(far.row, old.geometry)
        val page = initial.withDesktopEdits(listOf(old, swapped))
        assertEquals(2, page.desktopGeometry().index.at(InkPoint(1020f, 51f), 5000f).size)
        assertTrue(page.geometry!!.rightDp > 1000f)
        val erased = page.withDesktopEdit(InkEdit.EraseStrokes(setOf("far")))
        assertTrue(erased.geometry!!.rightDp < 100f)
        assertEquals(listOf("near"), erased.desktopGeometry().index.at(InkPoint(20f, 51f), 5000f).map { it.id })
        assertEquals(2, page.strokes.size)
    }

    private fun authored(id: String, x: Float = 10.123457f): AuthoredViveStroke {
        val tool = ViveInkTool(ViveBrushes.PRESSURE_PEN, 2, 0xff182f51.toInt(), 8f)
        val inputs = MutableStrokeInputBatch().apply {
            repeat(9) { index -> add(InputToolType.STYLUS, x + index * 2.136549f,
                50.234569f + index % 3, index * 17L, pressure = 0.223457f + index * 0.027654f) }
        }
        return tool.complete(Stroke(tool.brush, inputs), id, "page", 0, 1)
    }
}
