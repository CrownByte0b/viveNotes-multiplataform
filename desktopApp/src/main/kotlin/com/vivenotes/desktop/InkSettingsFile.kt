package com.vivenotes.desktop

import com.vivenotes.workspace.InkBrush
import com.vivenotes.workspace.InkHighlighterSettings
import com.vivenotes.workspace.InkPenSettings
import com.vivenotes.workspace.InkSettings
import java.io.File
import java.util.Properties

internal class InkSettingsFile(private val file: File) {
    fun load(): InkSettings {
        val values = file.readProperties() ?: return InkSettings()
        val defaults = InkSettings()
        return InkSettings(
            pen = InkPenSettings(
                brush = InkBrush.entries.firstOrNull { it.name == values.getProperty("pen.brush") } ?: defaults.pen.brush,
                sizeDp = values.getProperty("pen.sizeDp")?.toFloatOrNull() ?: defaults.pen.sizeDp,
                colorArgb = values.getProperty("pen.colorArgb")?.toIntOrNull() ?: defaults.pen.colorArgb,
                colorFollowsTheme = values.getProperty("pen.colorFollowsTheme")?.toBooleanStrictOrNull()
                    ?: defaults.pen.colorFollowsTheme,
            ),
            highlighter = InkHighlighterSettings(
                sizeDp = values.getProperty("highlighter.sizeDp")?.toFloatOrNull() ?: defaults.highlighter.sizeDp,
                colorArgb = values.getProperty("highlighter.colorArgb")?.toIntOrNull() ?: defaults.highlighter.colorArgb,
            ),
        ).normalized()
    }

    fun save(settings: InkSettings) {
        val value = settings.normalized()
        file.writePropertiesAtomically(Properties().apply {
            setProperty("pen.brush", value.pen.brush.name)
            setProperty("pen.sizeDp", value.pen.sizeDp.toString())
            setProperty("pen.colorArgb", value.pen.colorArgb.toString())
            setProperty("pen.colorFollowsTheme", value.pen.colorFollowsTheme.toString())
            setProperty("highlighter.sizeDp", value.highlighter.sizeDp.toString())
            setProperty("highlighter.colorArgb", value.highlighter.colorArgb.toString())
        }, "ViveNotes ink settings")
    }
}
