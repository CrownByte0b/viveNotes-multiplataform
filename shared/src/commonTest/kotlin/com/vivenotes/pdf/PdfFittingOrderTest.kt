package com.vivenotes.pdf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfFittingOrderTest {
    private fun item(id: String, x: Float, y: Float, width: Float = 100f, height: Float = 100f) =
        PdfItem(id, PdfItemKind.Content, PdfBounds(x, y, x + width, y + height))

    @Test
    fun fittingAcrossMultipleRowsAndColumnsDoesNotDependOnDocumentInsertionOrder() {
        val items = listOf(
            item("anchor", 0f, 0f),
            item("right-before", 800f, 100f, width = 150f),
            item("right-break", 960f, 100f, width = 150f),
            item("bottom-before", 100f, 800f, height = 150f),
            item("bottom-break", 100f, 960f, height = 150f),
            item("bottom-after", 100f, 1180f),
            item("lower-right", 1100f, 1100f),
        )
        val reference = PageTiling.plan(items, 1000f, 1000f)
        assertEquals(PdfShift(40f, 0f), reference.shiftFor("right-break"))
        assertEquals(PdfShift(0f, 40f), reference.shiftFor("bottom-break"))
        assertEquals(PdfShift(0f, 40f), reference.shiftFor("bottom-after"))
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), reference.tiles.map { it.column to it.row })
        repeat(20) { seed ->
            assertEquals(reference, PageTiling.plan(items.shuffled(Random(seed)), 1000f, 1000f), "seed=$seed")
        }
    }
}
