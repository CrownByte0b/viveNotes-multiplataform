package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkBrushCatalog
import com.vivenotes.model.ink.LineType
import com.vivenotes.workspace.InkBrush
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool

/** What a stroke drawn with a tool is made of: its brush family and the values its row stores. */
internal data class InkToolStyle(
    val familyId: String,
    val sizeDp: Float,
    val colorArgb: Int,
    val colorFollowsTheme: Boolean,
    val stabilization: Int,
)

/**
 * The highlighter, or the pen at [penIndex] for every other tool. An automatic pen colour is the
 * page's ink colour, [automaticArgb]; the highlighter is always its stored colour.
 */
internal fun InkSettings.inkToolStyle(tool: InkTool?, automaticArgb: Int, penIndex: Int = 0): InkToolStyle {
    val value = normalized()
    if (tool == InkTool.Highlighter) return InkToolStyle(
        familyId = InkBrushCatalog.HIGHLIGHTER,
        sizeDp = value.highlighter.sizeDp,
        colorArgb = value.highlighter.colorArgb,
        colorFollowsTheme = false,
        stabilization = 0,
    )
    val pen = value.pen(penIndex)
    return InkToolStyle(
        familyId = if (pen.lineType != LineType.Solid) InkBrushCatalog.DASHED_LINE else when (pen.brush) {
            InkBrush.Marker -> InkBrushCatalog.MARKER
            InkBrush.PressurePen -> InkBrushCatalog.PRESSURE_PEN
            InkBrush.Calligraphy -> InkBrushCatalog.calligraphy(pen.pressure)
            InkBrush.DashedLine -> InkBrushCatalog.DASHED_LINE
        },
        sizeDp = pen.sizeDp,
        colorArgb = if (pen.colorFollowsTheme) automaticArgb else pen.colorArgb,
        colorFollowsTheme = pen.colorFollowsTheme,
        stabilization = pen.stabilization,
    )
}
