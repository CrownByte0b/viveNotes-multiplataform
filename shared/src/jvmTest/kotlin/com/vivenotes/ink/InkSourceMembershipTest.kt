package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkSourceMembershipTest {
    @Test
    fun baseSealedAndTailMembershipRespectsDeletionReadditionAndOldSnapshots() {
        val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff112233.toInt(), 3f)
        val stroke = Stroke(tool.brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 50f, 0L)
            add(InputToolType.MOUSE, 20f, 55f, 10L)
        })
        fun projection(id: String) = PageStroke(id, stroke)
        val base = projection("base")
        val sealed = List(128) { projection("sealed-$it") }
        val tail = projection("tail")
        val page = DesktopInkPage(listOf(base)).withChanges(sealed, emptySet())
            .withChanges(listOf(tail), emptySet())
        assertEquals(1, page.retainedSegments.size)
        val ids = setOf("base", "sealed-64", "tail")
        ids.forEach { assertTrue(page.containsSource(it), it) }
        assertFalse(page.containsSource("absent"))

        val deleted = page.withChanges(emptyList(), ids)
        ids.forEach { id ->
            assertFalse(deleted.containsSource(id), "deleted $id is hidden even when its segment retains it")
            assertTrue(page.containsSource(id), "old snapshot retains $id")
        }
        assertTrue(deleted.containsSource("sealed-63"))

        val replacements = ids.map(::projection)
        val restored = deleted.withChanges(replacements, emptySet())
        replacements.forEach { replacement ->
            assertTrue(restored.containsSource(replacement.id))
            assertFalse(deleted.containsSource(replacement.id))
            assertEquals(listOf(replacement.projectionKey), restored.projections
                .filter { it.id == replacement.id }.map { it.projectionKey }, "only the new projection is live")
        }
        assertFalse(restored.containsSource("absent"))
    }
}
