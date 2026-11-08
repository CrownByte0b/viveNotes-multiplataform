package com.vivenotes.ink

import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool

internal fun InkSettings.byteInkTool(tool: InkTool?, automaticArgb: Int, penIndex: Int = 0): ViveInkTool =
    inkToolStyle(tool, automaticArgb, penIndex).let { style ->
        ViveInkTool(
            familyId = style.familyId,
            sizeDp = style.sizeDp,
            colorArgb = style.colorArgb,
            colorFollowsTheme = style.colorFollowsTheme,
            stabilization = style.stabilization,
        )
    }
