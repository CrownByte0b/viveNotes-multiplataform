package com.vivenotes.graph

import java.math.BigDecimal
import java.math.RoundingMode

actual class GraphDecimal private constructor(private val value: BigDecimal) : Comparable<GraphDecimal> {
    actual constructor(value: Double) : this(BigDecimal(value))
    actual constructor(value: Int) : this(BigDecimal(value))
    actual constructor(value: String) : this(BigDecimal(value))
    actual fun toDouble(): Double = value.toDouble()
    actual fun toPlainString(): String = value.toPlainString()
    actual fun signum(): Int = value.signum()
    actual fun negate(): GraphDecimal = GraphDecimal(value.negate())
    actual fun multiply(other: GraphDecimal): GraphDecimal = GraphDecimal(value.multiply(other.value))
    actual fun divide(other: GraphDecimal): GraphDecimal = GraphDecimal(value.divide(other.value))
    actual fun scaleByPowerOfTen(exponent: Int): GraphDecimal = GraphDecimal(value.scaleByPowerOfTen(exponent))
    actual fun stripTrailingZeros(): GraphDecimal = GraphDecimal(value.stripTrailingZeros())
    actual fun rounded(places: Int): GraphDecimal = GraphDecimal(value.setScale(places, RoundingMode.HALF_UP))
    actual override fun compareTo(other: GraphDecimal): Int = value.compareTo(other.value)

    actual companion object {
        actual val ZERO: GraphDecimal = GraphDecimal(BigDecimal.ZERO)
        actual val ONE: GraphDecimal = GraphDecimal(BigDecimal.ONE)
        actual fun valueOf(value: Long): GraphDecimal = GraphDecimal(BigDecimal.valueOf(value))
    }
}
