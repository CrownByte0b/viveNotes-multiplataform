package com.vivenotes.model.ink

import com.vivenotes.localization.Strings
import kotlinx.serialization.Serializable

/** Border styles stored as part of the document format. */
@Serializable
enum class LineType(private val labelText: () -> String) {
    Solid({ Strings.solid }),
    Dashed({ Strings.dashed }),
    Dotted({ Strings.dotted });

    val label: String get() = labelText()
}

const val PAGE_BASIC = 0
const val PAGE_SOLID = 1

/** The stable shape names serialized by Android and desktop. */
@Serializable
enum class ShapeKind(private val labelText: () -> String, val page: Int) {
    Line({ Strings.line }, PAGE_BASIC),
    Arrow({ Strings.arrow }, PAGE_BASIC),
    Rectangle({ Strings.rectangle }, PAGE_BASIC),
    RoundedRectangle({ Strings.rounded_rectangle }, PAGE_BASIC),
    Ellipse({ Strings.ellipse }, PAGE_BASIC),
    Triangle({ Strings.triangle }, PAGE_BASIC),
    RightTriangle({ Strings.right_triangle }, PAGE_BASIC),
    Diamond({ Strings.diamond }, PAGE_BASIC),
    Pentagon({ Strings.pentagon }, PAGE_BASIC),
    Hexagon({ Strings.hexagon }, PAGE_BASIC),
    L({ Strings.l_shape }, PAGE_BASIC),
    Cube({ Strings.cube }, PAGE_SOLID),
    Pyramid({ Strings.pyramid }, PAGE_SOLID),
    Wedge({ Strings.wedge }, PAGE_SOLID),
    Sphere({ Strings.sphere }, PAGE_SOLID),
    Cone({ Strings.cone }, PAGE_SOLID),
    Cylinder({ Strings.cylinder }, PAGE_SOLID),
    ;

    val isSolid: Boolean get() = page == PAGE_SOLID
    val hasArms: Boolean get() = this == L
    val hasEnds: Boolean get() = this == Line || this == Arrow

    companion object {
        const val PAGE_COUNT = 2
        val DEFAULT = Rectangle

        fun onPage(page: Int): List<ShapeKind> = entries.filter { it.page == page }
    }

    val label: String get() = labelText()
}
