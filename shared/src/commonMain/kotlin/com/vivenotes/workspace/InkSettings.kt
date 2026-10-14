package com.vivenotes.workspace

import com.vivenotes.data.EraserMode
import com.vivenotes.model.ink.LineType

enum class InkBrush(val label: String) {
    Marker("Fountain"), PressurePen("Pressure pen"), Calligraphy("Calligraphy"), DashedLine("Dashed line"),
}

data class InkPenSettings(
    val brush: InkBrush = InkBrush.Marker,
    val sizeDp: Float = 1.5f,
    val colorArgb: Int = 0xff000000.toInt(),
    val colorFollowsTheme: Boolean = true,
    val lineType: LineType = LineType.Solid,
    val pressure: Int = 3,
    val stabilization: Int = 1,
    val holdForStraightLine: Boolean = true,
) {
    fun normalized(): InkPenSettings = copy(
        sizeDp = if (sizeDp.isFinite()) sizeDp.coerceIn(MIN_SIZE, MAX_SIZE) else 1.5f,
        colorArgb = colorArgb or 0xff000000.toInt(),
        pressure = pressure.coerceIn(0, 5), stabilization = stabilization.coerceIn(0, 5),
    )
    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 8f
        fun starting(index: Int) = InkPenSettings(
            colorArgb = listOf(0xff000000, 0xffe53935, 0xff1e88e5)[index.coerceIn(0, 2)].toInt(),
            colorFollowsTheme = index == 0,
        )
    }
}

data class InkHighlighterSettings(val sizeDp: Float = 18f, val colorArgb: Int = 0x66ffeb3b) {
    fun normalized(): InkHighlighterSettings = copy(
        sizeDp = if (sizeDp.isFinite()) sizeDp.coerceIn(MIN_SIZE, MAX_SIZE) else 18f,
        colorArgb = (colorArgb and 0x00ffffff) or 0x66000000,
    )
    companion object { const val MIN_SIZE = 6f; const val MAX_SIZE = 40f }
}

data class InkEraserSettings(val mode: EraserMode = EraserMode.Normal, val sizeDp: Float = 18f) {
    fun normalized() = copy(sizeDp = if (sizeDp.isFinite()) sizeDp.coerceIn(4f, 64f) else 18f)
}

enum class RulerKind { Straight, Protractor }
data class InkRulerSettings(val kind: RulerKind = RulerKind.Straight, val diameterDp: Int = 640) {
    fun normalized() = copy(diameterDp = diameterDp.coerceIn(240, 1200))
}

val PenColors = listOf(0xffffffff, 0xff000000, 0xffe53935, 0xff00bcd4, 0xff00c853,
    0xffffd600, 0xffff9100, 0xff9c27b0, 0xff2962ff).map { it.toInt() }
val HighlighterColors = listOf(0x66ffeb3b, 0x6676ff03, 0x6640c4ff, 0x66ff4081, 0x66ff9100, 0x66b388ff)
fun List<Int>.withColorInFront(argb: Int): List<Int> = (listOf(argb) + filterNot { it == argb }).take(9)

data class InkSettings(
    val pen: InkPenSettings = InkPenSettings.starting(0),
    val highlighter: InkHighlighterSettings = InkHighlighterSettings(),
    val pen2: InkPenSettings = InkPenSettings.starting(1),
    val pen3: InkPenSettings = InkPenSettings.starting(2),
    val eraser: InkEraserSettings = InkEraserSettings(),
    val ruler: InkRulerSettings = InkRulerSettings(),
    val palette: List<Int> = PenColors,
    val table: TableToolSettings = TableToolSettings(),
) {
    fun pen(index: Int): InkPenSettings = when (index) { 1 -> pen2; 2 -> pen3; else -> pen }
    fun withPen(index: Int, value: InkPenSettings): InkSettings = when (index) {
        1 -> copy(pen2 = value.normalized()); 2 -> copy(pen3 = value.normalized()); else -> copy(pen = value.normalized())
    }
    fun normalized(): InkSettings = copy(pen = pen.normalized(), pen2 = pen2.normalized(), pen3 = pen3.normalized(),
        table = table.normalized(), highlighter = highlighter.normalized(), eraser = eraser.normalized(), ruler = ruler.normalized(),
        palette = palette.map { it or 0xff000000.toInt() }.distinct().take(9).ifEmpty { PenColors })
}


enum class DrawPlacementTool { Table, Equation, InsertSpace }
data class PendingDrawEquation(val latex: String, val width: Float, val height: Float)
data class TableToolSettings(
    val inkOnly: Boolean = true, val columns: Int = 3, val rows: Int = 3,
    val headerRow: Boolean = true, val headerColumn: Boolean = false,
    val borderWidth: Int = 1, val borderArgb: Int = 0xff9aa0a6.toInt(),
    val fillArgb: Int? = null, val colorFollowsTheme: Boolean = true,
) {
    fun normalized(): TableToolSettings {
        val cols = columns.coerceIn(1, com.vivenotes.model.Outline.Table.MAX_COLUMNS)
        return copy(columns = cols, rows = rows.coerceIn(1, minOf(com.vivenotes.model.Outline.Table.MAX_ROWS,
            com.vivenotes.model.Outline.Table.MAX_CELLS / cols)), borderWidth = borderWidth.coerceIn(1, 8))
    }
}
