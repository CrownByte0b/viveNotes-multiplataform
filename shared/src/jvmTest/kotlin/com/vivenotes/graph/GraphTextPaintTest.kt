package com.vivenotes.graph

import org.jetbrains.skia.Surface
import kotlin.test.*

class GraphTextPaintTest {
    @Test
    fun integralLabelsUseARealGlyphWhenTheDefaultFontNeedsFallback() {
        GraphTextPaint().use { text ->
            text.textSize = 32f; text.color = 0xff000000.toInt()
            fun render(value: String): ByteArray = Surface.makeRasterN32Premul(64, 64).use { surface ->
                surface.canvas.clear(0xffffffff.toInt())
                text.draw(surface.canvas, value, 5f, 45f)
                surface.makeImageSnapshot().use { it.encodeToData()!!.use { data -> data.bytes } }
            }
            assertFalse(render("∫").contentEquals(render("\u0000")), "integrals must not appear as missing-glyph boxes")
            assertTrue(text.measureText("∫ y dx = −10.667") > text.measureText("y dx = −10.667"))
        }
    }
}
