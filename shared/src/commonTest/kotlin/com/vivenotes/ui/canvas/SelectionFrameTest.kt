package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.Outline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionFrameTest {
    @Test
    fun boundsEncloseAllSelectedObjectsAndIgnoreUnselectedOnes() {
        val first = Outline.Equation(id = "first", x = 30f, y = 90f, width = 80f, height = 40f)
        val second = Outline.Image(id = "second", x = 180f, y = 50f,
            width = 60f, height = 110f, attachmentId = "fixture")
        val other = Outline.Equation(id = "other", x = 500f, y = 500f)

        assertEquals(Rect(30f, 50f, 240f, 160f), selectedCanvasBounds(
            listOf(first, second, other), setOf("first", "second")))
        assertNull(selectedCanvasBounds(listOf(first, second), setOf("first")))
    }

    @Test
    fun textUsesItsRenderedHeightWhenIncludedInAMixedSelection() {
        val text = Outline.Text.empty().copy(id = "text", x = 20f, y = 40f,
            width = 180f, minHeight = 90f)
        val image = Outline.Image(id = "image", x = 230f, y = 90f,
            width = 40f, height = 35f, attachmentId = "fixture")

        assertEquals(Rect(20f, 40f, 270f, 240f), selectedCanvasBounds(
            listOf(text, image), setOf("text", "image"), mapOf("text" to 200f)))
        assertEquals(Rect(20f, 40f, 270f, 190f), selectedCanvasBounds(
            listOf(text, image), setOf("text", "image")))
    }
}
