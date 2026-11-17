package com.vivenotes.workspace

import com.vivenotes.model.CborDocumentCodec
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.canFill
import com.vivenotes.model.ink.fillRegion
import com.vivenotes.model.ink.vertices
import kotlin.test.*

/** Android's curve ViewModel scenarios through the desktop workspace's structural history. */
class CurveWorkspaceTest {
    private fun placed(): WorkspaceState {
        val base = WorkspaceState.demo()
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList())) }
            .setShapeSettings(ShapeToolSettings(kind = ShapeKind.Curve, fillArgb = 0xff3584e4.toInt()))
            .toggleShapeTool().createShape(40f, 220f, 280f, 380f)
    }

    private fun WorkspaceState.curve(): Outline.Shape = selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().single()

    /**
     * Shape geometry is Float. Kotlin/JS keeps Float arithmetic (and `Double.toFloat()`) at Double
     * precision, and its codecs differ: JSON decodes a Float32, CBOR the full Double. Both sides of a
     * round trip are compared at Float32 precision; elsewhere Float is Float32 and this is the identity.
     */
    private fun PageDoc.storedAsFloat32(): PageDoc {
        fun Float.f32() = Float.fromBits(toBits())
        return copy(outlines = outlines.map { outline ->
            if (outline !is Outline.Shape) return@map outline
            outline.copy(x = outline.x.f32(), y = outline.y.f32(),
                width = outline.width.f32(), height = outline.height.f32(),
                segments = outline.segments.map { s ->
                    s.copy(x1 = s.x1.f32(), y1 = s.y1.f32(), x2 = s.x2.f32(), y2 = s.y2.f32(),
                        cubic = s.cubic?.let { c -> c.copy(x1 = c.x1.f32(), y1 = c.y1.f32(), x2 = c.x2.f32(), y2 = c.y2.f32()) })
                })
        })
    }

    @Test
    fun aCurveStartsWithThreeVerticesAndNeverAcceptsFill() {
        val state = placed()
        val curve = state.curve()
        assertEquals(ShapeKind.Curve, curve.kind)
        assertEquals(3, curve.vertices().size)
        assertTrue(curve.segments.all { it.cubic != null })
        assertNull(curve.fillArgb)
        assertFalse(curve.canFill)
        assertTrue(curve.fillRegion().isEmpty())
        assertSame(state, state.setSelectedShapeFill(0xffff0000.toInt()))
    }

    @Test
    fun chosenSpanInsertionAndVertexMovesUndoRedoAndCopyWithTheirCubicControls() {
        val initial = placed()
        val curve = initial.curve()
        val added = initial.addShapeVertex(curve.id, curve.segments.last().id)
        val split = added.curve()
        assertEquals(4, split.vertices().size)
        assertEquals(curve.segments.first(), split.segments.first())
        assertEquals(initial.structuralUndo.size + 1, added.structuralUndo.size)
        assertEquals(initial.selectedObjectIds, added.selectedObjectIds)

        val moved = added.moveShapeVertex(curve.id, 1, 100f, 300f)
        val edited = moved.curve()
        assertEquals(100f, edited.vertices()[1].x)
        assertEquals(300f, edited.vertices()[1].y)
        assertEquals(added.structuralUndo.size + 1, moved.structuralUndo.size)
        assertEquals(split, moved.undoStructure().curve())
        assertEquals(curve, moved.undoStructure().undoStructure().curve())
        assertEquals(edited, moved.undoStructure().undoStructure().redoStructure().redoStructure().curve())
        for (codec in listOf(JsonDocumentCodec, CborDocumentCodec)) {
            val document = moved.selectedPage!!.document
            assertEquals(document.storedAsFloat32(), codec.decode(codec.encode(document)).storedAsFloat32())
        }

        val pasted = moved.copySelectedObjects().pasteCanvasAt(400f, 500f)
        val duplicate = pasted.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().first { it.id != curve.id }
        assertEquals(4, duplicate.vertices().size)
        assertTrue(duplicate.segments.all { it.cubic != null })
        assertTrue(duplicate.segments.none { copy -> edited.segments.any { it.id == copy.id } })
        val translated = edited.translated(duplicate.x - edited.x, duplicate.y - edited.y)
        duplicate.segments.zip(translated.segments).forEach { (actual, expected) ->
            assertEquals(expected.copy(id = actual.id), actual)
        }
    }

    @Test
    fun removingAnInteriorOrEndpointMakesOneUndoStepAndKeepsAtLeastTwoVertices() {
        val initial = placed()
        val curve = initial.curve()
        val targets = listOf(curve.segments.first().id to false,
            curve.segments.last().id to false, curve.segments.last().id to true)
        for ((segment, atEnd) in targets) {
            val removed = initial.removeShapeVertex(curve.id, segment, atEnd)
            assertEquals(2, removed.curve().vertices().size)
            assertEquals(initial.structuralUndo.size + 1, removed.structuralUndo.size)
            assertEquals(initial.selectedObjectIds, removed.selectedObjectIds)
            assertEquals(curve, removed.undoStructure().curve())
            assertEquals(removed.curve(), removed.undoStructure().redoStructure().curve())
            assertSame(removed, removed.removeShapeVertex(curve.id, removed.curve().segments.single().id, false))
            val restored = removed.addShapeVertex(curve.id, removed.curve().segments.single().id)
            assertEquals(3, restored.curve().vertices().size)
        }
    }

    @Test
    fun staleTargetsWrongKindsAndInvalidMovesDoNotAddHistory() {
        val initial = placed()
        val curve = initial.curve()
        assertSame(initial, initial.addShapeVertex("missing", curve.segments.first().id))
        assertSame(initial, initial.addShapeVertex(curve.id, "missing"))
        assertSame(initial, initial.removeShapeVertex(curve.id, "missing", false))
        assertSame(initial, initial.removeShapeVertex(curve.id, curve.segments.first().id, true))
        assertSame(initial, initial.moveShapeVertex(curve.id, -1, 100f, 300f))
        assertSame(initial, initial.moveShapeVertex(curve.id, 3, 100f, 300f))
        assertSame(initial, initial.moveShapeVertex(curve.id, 1, Float.NaN, 300f))
        val vertex = curve.vertices()[1]
        assertSame(initial, initial.moveShapeVertex(curve.id, 1, vertex.x, vertex.y))
        val other = initial.updatePage(initial.selectedPageId) { page ->
            page.copy(document = PageDoc(outlines = listOf(curve.copy(kind = ShapeKind.Rectangle))))
        }
        assertSame(other, other.addShapeVertex(curve.id, curve.segments.first().id))
        assertSame(other, other.removeShapeVertex(curve.id, curve.segments.first().id, false))
        assertSame(other, other.moveShapeVertex(curve.id, 1, 100f, 300f))
    }

    @Test
    fun lockedAndUnreadablePagesRefuseEveryVertexEdit() {
        val initial = placed()
        val curve = initial.curve()
        val locked = initial.updatePage(initial.selectedPageId) { page ->
            page.copy(document = PageDoc(outlines = listOf(curve.copy(lockGroup = "pinned"))))
        }
        val unreadable = initial.updatePage(initial.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        for (state in listOf(locked, unreadable)) {
            assertSame(state, state.addShapeVertex(curve.id, curve.segments.first().id))
            assertSame(state, state.removeShapeVertex(curve.id, curve.segments.last().id, false))
            assertSame(state, state.moveShapeVertex(curve.id, 1, 100f, 300f))
        }
    }

    @Test
    fun previewVertexMovesLeaveTheUndoHistoryUntouched() {
        val initial = placed()
        val preview = initial.moveShapeVertex(initial.curve().id, 1, 100f, 300f, recordHistory = false)
        assertEquals(100f, preview.curve().vertices()[1].x)
        assertEquals(initial.structuralUndo, preview.structuralUndo)
        assertEquals(initial.structuralRedo, preview.structuralRedo)
        assertEquals(initial.selectedObjectIds, preview.selectedObjectIds)
    }
}
