package com.vivenotes.ai

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.selectionLine
import com.vivenotes.ink.splitSelectionPage
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import org.jetbrains.skia.Image
import kotlin.test.*

class DrawInkRecognitionTest {
    @OptIn(InternalComposeUiApi::class)
    @BeforeTest
    fun initializeStandaloneGraphics() = registerSkikoComposeImplementation()

    @Test
    fun selectedFragmentRasterNeverRebuildsItsErasedGapOrUnselectedSibling() {
        val (_, split) = splitSelectionPage()
        val selected = split.geometry!!.selectInRect(split, 0f, 180f, 55f, 220f)!!.move(20f, 40f)!!.selection!!
        val pieces = selected.forRecognition()!!.desktopGeometry().projections
        assertEquals(1, pieces.size)
        for (stem in listOf(4f, 10f)) renderDrawInk(pieces, stem).use { image ->
            val pixels = image.argbPixels()
            val center = image.height / 2
            assertEquals(0xffffffff.toInt(), pixels[center * image.width + image.width - 1],
                "right padding stays white beyond the selected fragment")
            assertTrue(pixels.any { it == 0xff000000.toInt() })
        }
    }

    @Test
    fun calculatorRasterUsesAndroidsStemSizingAndAWhiteBackgroundWithOpaqueBlackInk() {
        val page = InkPageReader.read("page", listOf(selectionLine()), emptyList(), emptyList())
        val projections = page.desktopGeometry().projections
        for (stem in listOf(4f, 10f)) renderDrawInk(projections, stem).use { image ->
            val pixels = image.argbPixels()
            assertEquals(0xffffffff.toInt(), pixels.first())
            assertTrue(pixels.any { it == 0xff000000.toInt() })
            assertTrue(pixels.all { (it ushr 24) == 255 })
            assertTrue(pixels.all { red(it) == green(it) && red(it) == blue(it) })
            assertTrue(image.width in 1..2048); assertTrue(image.height in 1..2048)
        }
        assertFailsWith<IllegalArgumentException> { renderDrawInk(emptyList(), 10f) }
    }

    private fun shape(id: String, kind: ShapeKind, startX: Float, startY: Float, endX: Float, endY: Float,
                      borderArgb: Int = 0xff000000.toInt(), borderWidth: Float = 2f, lineType: LineType = LineType.Solid,
    ): Outline.Shape {
        var next = 0
        return Outline.Shape(id = id, kind = kind, segments = seedSegments(kind, startX, startY, endX, endY) { "$id-${next++}" },
            borderArgb = borderArgb, borderWidth = borderWidth, lineType = lineType).withRecomputedBounds()
    }

    private fun Image.dark(): Int = argbPixels().count { red(it) < 128 }

    /** Lengths of the dark runs down one column, top to bottom. */
    private fun Image.darkRuns(x: Int): List<Int> {
        val pixels = argbPixels()
        val runs = mutableListOf<Int>()
        var run = 0
        for (y in 0 until height) {
            if (red(pixels[y * width + x]) < 128) run++ else if (run > 0) { runs += run; run = 0 }
        }
        if (run > 0) runs += run
        return runs
    }

    @Test
    fun aFractionBarDrawnWithTheLineToolIsInkedForTheModelToo() {
        // Android's InkSelectionRenderTest: the ink is at y = 200; the bar sits well below it, so the
        // crop has to grow to hold it and the pixels it adds can be counted on their own.
        val ink = InkPageReader.read("page", listOf(selectionLine()), emptyList(), emptyList()).desktopGeometry().projections
        val bar = shape("bar", ShapeKind.Line, 10f, 240f, 110f, 240f)
        for (stem in listOf(4f, 10f)) renderDrawInk(ink, stem, listOf(bar)).use { withBar ->
            renderDrawInk(ink, stem).use { inkAlone ->
                assertTrue(withBar.height > inkAlone.height, "the crop grows to hold the bar")
                assertTrue(withBar.dark() > inkAlone.dark(), "the line was left out of what the model is shown")
                val runs = withBar.darkRuns(withBar.width / 2)
                assertEquals(2, runs.size, "the stroke and the bar, apart: $runs")
                assertTrue(kotlin.math.abs(runs[0] - runs[1]) <= maxOf(2, runs[0] / 4),
                    "the bar is inked at the ink's stem width: $runs")
                val pixels = withBar.argbPixels()
                assertEquals(0xffffffff.toInt(), pixels.last(), "padding below the bar stays white")
                assertTrue(pixels.all { red(it) == green(it) && red(it) == blue(it) })
            }
        }
    }

    @Test
    fun onlyLinesAndArrowsAreInkedAndAlwaysBlackSolidAtTheStemWhateverTheirPageStyle() {
        val ink = InkPageReader.read("page", listOf(selectionLine()), emptyList(), emptyList()).desktopGeometry().projections
        renderDrawInk(ink, 10f).use { inkAlone ->
            val notMarks = listOf(shape("box", ShapeKind.Rectangle, 10f, 230f, 110f, 280f),
                shape("ell", ShapeKind.L, 10f, 230f, 110f, 280f))
            renderDrawInk(ink, 10f, notMarks).use { rendered ->
                assertEquals(inkAlone.width, rendered.width); assertEquals(inkAlone.height, rendered.height)
                assertContentEquals(inkAlone.argbPixels(), rendered.argbPixels(), "a rectangle or an L is no part of an equation")
            }
        }
        val plain = shape("bar", ShapeKind.Line, 10f, 240f, 110f, 240f)
        val styled = shape("bar", ShapeKind.Line, 10f, 240f, 110f, 240f, borderArgb = 0xffe01b24.toInt(),
            borderWidth = 8f, lineType = LineType.Dashed)
        renderDrawInk(ink, 10f, listOf(plain)).use { solid ->
            renderDrawInk(ink, 10f, listOf(styled)).use { dashed ->
                assertContentEquals(solid.argbPixels(), dashed.argbPixels(), "colour, width and dashes are the page's, not the model's")
            }
        }
        val arrow = shape("arrow", ShapeKind.Arrow, 10f, 240f, 110f, 240f)
        renderDrawInk(ink, 10f, listOf(plain)).use { line ->
            renderDrawInk(ink, 10f, listOf(arrow)).use { withArrow ->
                assertTrue(withArrow.dark() > line.dark(), "an arrow is inked with its head")
            }
        }
        assertFailsWith<IllegalArgumentException>("lines without ink are not a formula") { renderDrawInk(emptyList(), 10f, listOf(plain)) }
    }
}
