package com.vivenotes.ui.ribbon.draw

import kotlin.test.Test
import kotlin.test.assertEquals

class ShapeColorTest {
    @Test
    fun rgbAndArgbHexAreAcceptedAndBadInputIsRejected() {
        assertEquals(0xFF123456.toInt(), parseShapeColor("#123456"))
        assertEquals(0x80123456.toInt(), parseShapeColor("80123456"))
        assertEquals(null, parseShapeColor("#12345"))
        assertEquals(null, parseShapeColor("#12345g"))
    }
}
