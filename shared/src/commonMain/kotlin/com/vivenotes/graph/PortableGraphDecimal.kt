package com.vivenotes.graph

import kotlin.math.abs
import kotlin.math.pow

/** Decimal grid arithmetic for the dormant browser targets. JVM uses BigDecimal unchanged. */
class PortableGraphDecimal private constructor(private val coefficient: Long, private val exponent: Int) : Comparable<PortableGraphDecimal> {
    private constructor(parts: Pair<Long, Int>) : this(parts.first, parts.second)
    constructor(value: Double) : this(parse(value.toString()))
    constructor(value: Int) : this(value.toLong(), 0)
    constructor(value: String) : this(parse(value))
    fun toDouble(): Double = coefficient * 10.0.pow(exponent)
    fun signum(): Int = coefficient.compareTo(0)
    fun negate(): PortableGraphDecimal = PortableGraphDecimal(-coefficient, exponent)
    fun multiply(other: PortableGraphDecimal): PortableGraphDecimal =
        PortableGraphDecimal(coefficient * other.coefficient, exponent + other.exponent)
    fun divide(other: PortableGraphDecimal): PortableGraphDecimal = PortableGraphDecimal(toDouble() / other.toDouble())
    fun scaleByPowerOfTen(exponent: Int): PortableGraphDecimal = PortableGraphDecimal(coefficient, this.exponent + exponent)
    fun stripTrailingZeros(): PortableGraphDecimal {
        if (coefficient == 0L) return ZERO
        var value = coefficient
        var power = exponent
        while (value % 10 == 0L) { value /= 10; power++ }
        return PortableGraphDecimal(value, power)
    }
    fun rounded(places: Int): PortableGraphDecimal {
        val shift = -places - exponent
        if (shift <= 0) return this
        if (shift > 18) return ZERO
        var divisor = 1L
        repeat(shift) { divisor *= 10 }
        val rounded = coefficient / divisor + if (abs(coefficient % divisor) >= divisor / 2) signum() else 0
        return PortableGraphDecimal(rounded, -places)
    }
    fun toPlainString(): String {
        val number = stripTrailingZeros()
        val digits = abs(number.coefficient).toString()
        val point = digits.length + number.exponent
        val body = when {
            number.coefficient == 0L -> "0"
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return if (number.coefficient < 0) "-$body" else body
    }
    override fun compareTo(other: PortableGraphDecimal): Int = toDouble().compareTo(other.toDouble())

    companion object {
        val ZERO = PortableGraphDecimal(0, 0)
        val ONE = PortableGraphDecimal(1, 0)
        fun valueOf(value: Long): PortableGraphDecimal = PortableGraphDecimal(value, 0)
        private fun parse(value: String): Pair<Long, Int> {
            val parts = value.lowercase().split('e')
            val decimal = parts[0]
            val fractional = decimal.substringAfter('.', "").length
            return decimal.replace(".", "").toLong() to ((parts.getOrNull(1)?.toInt() ?: 0) - fractional)
        }
    }
}
