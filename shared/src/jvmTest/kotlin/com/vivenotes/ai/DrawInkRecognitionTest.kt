package com.vivenotes.ai

import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.selectionLine
import kotlin.test.*

class DrawInkRecognitionTest {
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
