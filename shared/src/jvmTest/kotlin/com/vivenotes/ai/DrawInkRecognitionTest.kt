package com.vivenotes.ai

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.selectionLine
import com.vivenotes.ink.splitSelectionPage
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
}
