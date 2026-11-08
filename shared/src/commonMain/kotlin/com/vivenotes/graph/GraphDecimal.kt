package com.vivenotes.graph

/** Exact decimal grid arithmetic; desktop delegates to java.math.BigDecimal. */
expect class GraphDecimal : Comparable<GraphDecimal> {
    constructor(value: Double)
    constructor(value: Int)
    constructor(value: String)
    fun toDouble(): Double
    fun toPlainString(): String
    fun signum(): Int
    fun negate(): GraphDecimal
    fun multiply(other: GraphDecimal): GraphDecimal
    fun divide(other: GraphDecimal): GraphDecimal
    fun scaleByPowerOfTen(exponent: Int): GraphDecimal
    fun stripTrailingZeros(): GraphDecimal
    fun rounded(places: Int): GraphDecimal
    /** `setScale(places, HALF_EVEN)`. */
    fun roundedHalfEven(places: Int): GraphDecimal
    /** Digits after the point, as `BigDecimal.scale`. */
    fun scale(): Int
    override fun compareTo(other: GraphDecimal): Int

    companion object {
        val ZERO: GraphDecimal
        val ONE: GraphDecimal
        fun valueOf(value: Long): GraphDecimal
        /** `BigDecimal.valueOf(double)`: the shortest decimal that reads back as [value]. */
        fun valueOf(value: Double): GraphDecimal
    }
}
