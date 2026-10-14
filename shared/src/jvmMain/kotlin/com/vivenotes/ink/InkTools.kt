package com.vivenotes.ink

import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.workspace.InkBrush
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool

internal fun InkSettings.byteInkTool(tool: InkTool?, automaticArgb: Int, penIndex: Int = 0): ViveInkTool {
    val value = normalized()
    if (tool == InkTool.Highlighter) return ViveInkTool(
        familyId = ViveBrushes.HIGHLIGHTER,
        sizeDp = value.highlighter.sizeDp,
        colorArgb = value.highlighter.colorArgb,
        colorFollowsTheme = false,
    )
    val pen = value.pen(penIndex)
    return ViveInkTool(
        familyId = if (pen.lineType != com.vivenotes.model.ink.LineType.Solid) ViveBrushes.DASHED_LINE else when (pen.brush) {
            InkBrush.Marker -> ViveBrushes.MARKER
            InkBrush.PressurePen -> ViveBrushes.PRESSURE_PEN
            InkBrush.Calligraphy -> ViveBrushes.calligraphy(pen.pressure)
            InkBrush.DashedLine -> ViveBrushes.DASHED_LINE
        },
        sizeDp = pen.sizeDp,
        colorArgb = if (pen.colorFollowsTheme) automaticArgb else pen.colorArgb,
        colorFollowsTheme = pen.colorFollowsTheme,
        stabilization = pen.stabilization,
    )
}
