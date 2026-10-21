package com.vivenotes.pdf

import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfBoundsIndexTest {
    @Test
    fun movingBroadPhaseMatchesExactScanIncludingWideAndDegenerateBoxes() {
        val random = Random(76)
        repeat(30) {
            fun box(): PdfBounds {
                val x = random.nextInt(-1000, 1000).toFloat()
                val y = random.nextInt(-1000, 1000).toFloat()
                return PdfBounds(x, y, x + random.nextInt(0, 2000), y + random.nextInt(0, 2000))
            }
            val boxes = MutableList(60) { box() }
            val index = PdfBoundsIndex(160f, 220f, boxes)
            repeat(80) {
                val changed = random.nextInt(boxes.size)
                boxes[changed] = box()
                index.update(changed, boxes[changed])
                val query = box()
                assertEquals(boxes.indices.any { it != changed && query.overlaps(boxes[it]) },
                    index.anyOverlap(query) { it != changed })
            }
        }
    }

    @Test
    fun occupiedTilesMatchTheOriginalFullScanInBothFittingModes() {
        val random = Random(919)
        repeat(160) { case ->
            val items = List(30) { index ->
                val x = random.nextInt(-5, 20) * 30f
                val y = random.nextInt(-5, 20) * 30f
                PdfItem("$index", if (index == 0) PdfItemKind.Title else PdfItemKind.Content,
                    PdfBounds(x, y, x + random.nextInt(0, 30) * 10f, y + random.nextInt(0, 30) * 10f))
            }
            val plan = PageTiling.plan(items, 160f, 220f, fit = case % 2 == 0)
            val content = items.contentBounds()!!
            val placed = items.map { item -> plan.shiftFor(item.id).let { item.bounds.translated(it.dx, it.dy) } }
            val columns = ceil((placed.maxOf { it.right } - content.left) / 160f).toInt().coerceIn(1, 100)
            val rows = ceil((placed.maxOf { it.bottom } - content.top) / 220f).toInt().coerceIn(1, 100)
            val expected = buildList {
                for (column in 0 until columns) for (row in 0 until rows) {
                    val bounds = PdfBounds(content.left + column * 160f, content.top + row * 220f,
                        content.left + (column + 1) * 160f, content.top + (row + 1) * 220f)
                    if (placed.any { it.overlaps(bounds) }) add(PdfTile(column, row, bounds))
                }
            }
            assertEquals(expected, plan.tiles)
        }
    }
}
