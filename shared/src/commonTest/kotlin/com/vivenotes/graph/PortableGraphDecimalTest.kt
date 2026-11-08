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
    fun toDoubleIsTheNearestDoubleLikeBigDecimal() {
        // A slider snapped 2.2999999 to "2.3" and then read back 2.3000000000000003 in the browser.
        assertEquals(2.3, PortableGraphDecimal.valueOf(2.2999999).roundedHalfEven(1).toDouble())
        for (units in -1000..1000) {
            assertEquals(units / 10.0, PortableGraphDecimal.valueOf(units.toLong()).scaleByPowerOfTen(-1).toDouble(), "$units/10")
            assertEquals(units / 100.0, PortableGraphDecimal.valueOf(units.toLong()).scaleByPowerOfTen(-2).toDouble(), "$units/100")
        }
        assertEquals(listOf(0.3, -0.7, 4.35, 1e-7, 123456.789, 1.5e20, 0.0),
            listOf("0.3", "-0.7", "4.35", "0.0000001", "123456.789", "1.5e20", "-0.000").map {
                PortableGraphDecimal(it).toDouble()
            })
        assertEquals(1e-30, PortableGraphDecimal.ONE.scaleByPowerOfTen(-30).toDouble(), "beyond the exact powers")
        assertEquals(3e40, PortableGraphDecimal.valueOf(3).scaleByPowerOfTen(40).toDouble())
    }

    @Test
    fun labelsRoundHalfUpAndSuppressNegativeZeroAndTrailingZeros() {
        assertEquals("-1.235", PortableGraphDecimal("-1.2345").rounded(3).toPlainString())
        assertEquals("0", PortableGraphDecimal("-0.0001").rounded(3).toPlainString())
        assertEquals("1.5", PortableGraphDecimal("1.5000").stripTrailingZeros().toPlainString())
        assertEquals("0.0000001", PortableGraphDecimal(1e-7).toPlainString())
    }
}
