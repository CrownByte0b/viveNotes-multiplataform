package com.vivenotes.graph

import kotlin.test.*

class PortableGraphDecimalTest {
    @Test
    fun decimalTicksAndSnappedCoordinatesDoNotAccumulateBinaryRounding() {
        val tenth = PortableGraphDecimal("0.1")
        assertEquals("0.3", tenth.multiply(PortableGraphDecimal.valueOf(3)).toPlainString())
        assertEquals("-0.3", tenth.multiply(PortableGraphDecimal.valueOf(-3)).toPlainString())
        assertEquals("0.02", tenth.divide(PortableGraphDecimal(5)).toPlainString())
        assertEquals("1000", PortableGraphDecimal.ONE.scaleByPowerOfTen(3).toPlainString())
        assertEquals("0.001", PortableGraphDecimal.ONE.scaleByPowerOfTen(-3).toPlainString())
    }

    @Test
    fun labelsRoundHalfUpAndSuppressNegativeZeroAndTrailingZeros() {
        assertEquals("-1.235", PortableGraphDecimal("-1.2345").rounded(3).toPlainString())
        assertEquals("0", PortableGraphDecimal("-0.0001").rounded(3).toPlainString())
        assertEquals("1.5", PortableGraphDecimal("1.5000").stripTrailingZeros().toPlainString())
        assertEquals("0.0000001", PortableGraphDecimal(1e-7).toPlainString())
    }
}
