package com.vivenotes.ink

import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.workspace.InkBrush
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool

internal fun InkSettings.byteInkTool(tool: InkTool?, automaticArgb: Int): ViveInkTool {
    val value = normalized()
    if (tool == InkTool.Highlighter) return ViveInkTool(
        familyId = ViveBrushes.HIGHLIGHTER,
        sizeDp = value.highlighter.sizeDp,
        colorArgb = value.highlighter.colorArgb,
        colorFollowsTheme = false,
    )
    return ViveInkTool(
        familyId = when (value.pen.brush) {
            InkBrush.Marker -> ViveBrushes.MARKER
            InkBrush.PressurePen -> ViveBrushes.PRESSURE_PEN
            InkBrush.Calligraphy -> ViveBrushes.calligraphy(ViveBrushes.DEFAULT_CALLIGRAPHY_PRESSURE)
            InkBrush.DashedLine -> ViveBrushes.DASHED_LINE
        },
        sizeDp = value.pen.sizeDp,
        colorArgb = if (value.pen.colorFollowsTheme) automaticArgb else value.pen.colorArgb,
        colorFollowsTheme = value.pen.colorFollowsTheme,
    )
}
