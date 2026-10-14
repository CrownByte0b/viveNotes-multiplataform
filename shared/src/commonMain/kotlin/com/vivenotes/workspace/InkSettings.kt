package com.vivenotes.workspace

enum class InkBrush(val label: String) {
    Marker("Marker"), PressurePen("Pressure pen"), Calligraphy("Calligraphy"), DashedLine("Dashed line"),
}

data class InkPenSettings(
    val brush: InkBrush = InkBrush.Marker,
    val sizeDp: Float = 3f,
    val colorArgb: Int = 0xff000000.toInt(),
    val colorFollowsTheme: Boolean = true,
) {
    fun normalized(): InkPenSettings = copy(
        sizeDp = if (sizeDp.isFinite()) sizeDp.coerceIn(MIN_SIZE, MAX_SIZE) else 3f,
        colorArgb = colorArgb or 0xff000000.toInt(),
    )

    companion object {
        const val MIN_SIZE = 0.5f
        const val MAX_SIZE = 48f
    }
}

data class InkHighlighterSettings(
    val sizeDp: Float = 18f,
    val colorArgb: Int = 0x80ffe000.toInt(),
) {
    fun normalized(): InkHighlighterSettings = copy(
        sizeDp = if (sizeDp.isFinite()) sizeDp.coerceIn(MIN_SIZE, MAX_SIZE) else 18f,
        colorArgb = (colorArgb and 0x00ffffff) or 0x80000000.toInt(),
    )

    companion object {
        const val MIN_SIZE = 2f
        const val MAX_SIZE = 96f
    }
}

/** Device preferences for future strokes. Saved strokes retain their own brush, width and color. */
data class InkSettings(
    val pen: InkPenSettings = InkPenSettings(),
    val highlighter: InkHighlighterSettings = InkHighlighterSettings(),
) {
    fun normalized(): InkSettings = copy(pen = pen.normalized(), highlighter = highlighter.normalized())
}
