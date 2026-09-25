package com.vivenotes.model.ink

import kotlinx.serialization.Serializable

/** Border styles stored as part of the document format. */
@Serializable
enum class LineType(val label: String) {
    Solid("Solid"),
    Dashed("Dashed"),
    Dotted("Dotted"),
}

const val PAGE_BASIC = 0
const val PAGE_SOLID = 1

/** The stable shape names serialized by Android and desktop. */
@Serializable
enum class ShapeKind(val label: String, val page: Int) {
    Line("Line", PAGE_BASIC),
    Arrow("Arrow", PAGE_BASIC),
    Rectangle("Rectangle", PAGE_BASIC),
    RoundedRectangle("Rounded rectangle", PAGE_BASIC),
    Ellipse("Ellipse", PAGE_BASIC),
    Triangle("Triangle", PAGE_BASIC),
    RightTriangle("Right triangle", PAGE_BASIC),
    Diamond("Diamond", PAGE_BASIC),
    Pentagon("Pentagon", PAGE_BASIC),
    Hexagon("Hexagon", PAGE_BASIC),
    L("L shape", PAGE_BASIC),
    Cube("Cube", PAGE_SOLID),
    Pyramid("Pyramid", PAGE_SOLID),
    Wedge("Wedge", PAGE_SOLID),
    Sphere("Sphere", PAGE_SOLID),
    Cone("Cone", PAGE_SOLID),
    Cylinder("Cylinder", PAGE_SOLID),
    ;

    val isSolid: Boolean get() = page == PAGE_SOLID
    val hasArms: Boolean get() = this == L
    val hasEnds: Boolean get() = this == Line || this == Arrow

    companion object {
        const val PAGE_COUNT = 2
        val DEFAULT = Rectangle

        fun onPage(page: Int): List<ShapeKind> = entries.filter { it.page == page }
    }
}
