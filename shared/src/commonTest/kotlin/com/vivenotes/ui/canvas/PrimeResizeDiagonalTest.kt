package com.vivenotes.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals

class PrimeResizeDiagonalTest {
    @Test
    fun oppositeCornersShareTheMatchingDiagonalCursor() {
        assertEquals(listOf("nwse-resize", "nesw-resize", "nesw-resize", "nwse-resize"),
            (0..3).map { primeResizeDiagonal(it).keyword })
    }
}
