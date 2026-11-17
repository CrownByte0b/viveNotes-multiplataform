package com.vivenotes.desktop

import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.DEFAULT_SOLID_WALL_THICKNESS
import com.vivenotes.model.ink.MIN_SOLID_WALL_THICKNESS
import com.vivenotes.model.ink.MAX_SOLID_WALL_THICKNESS
import com.vivenotes.workspace.ShapeToolSettings
import java.io.File
import java.util.Properties

/** Device choices for the next shape; page shapes store their own style in the document. */
internal class ShapeSettingsFile(private val file: File) {
    fun load(): ShapeToolSettings {
        val values = file.readProperties() ?: return ShapeToolSettings()
        return ShapeToolSettings(
            kind = values.getProperty("kind")?.let { saved ->
                ShapeKind.entries.firstOrNull { it.name == saved }
            } ?: ShapeKind.DEFAULT,
            lineType = values.getProperty("lineType")?.let { saved ->
                LineType.entries.firstOrNull { it.name == saved }
            } ?: LineType.Solid,
            borderWidth = (values.getProperty("borderWidth")?.toIntOrNull() ?: 2)
                .coerceIn(ShapeToolSettings.MIN_BORDER_WIDTH, ShapeToolSettings.MAX_BORDER_WIDTH),
            borderArgb = values.getProperty("borderArgb")?.toIntOrNull() ?: 0xFF000000.toInt(),
            fillArgb = values.getProperty("fillArgb")?.toIntOrNull(),
            colorFollowsTheme = values.getProperty("colorFollowsTheme")?.toBooleanStrictOrNull() ?: true,
            wallThickness = (values.getProperty("wallThickness")?.toIntOrNull() ?: DEFAULT_SOLID_WALL_THICKNESS)
                .coerceIn(MIN_SOLID_WALL_THICKNESS, MAX_SOLID_WALL_THICKNESS),
        )
    }

    fun save(settings: ShapeToolSettings) {
        file.writePropertiesAtomically(Properties().apply {
            setProperty("kind", settings.kind.name)
            setProperty("lineType", settings.lineType.name)
            setProperty("borderWidth", settings.borderWidth.coerceIn(
                ShapeToolSettings.MIN_BORDER_WIDTH, ShapeToolSettings.MAX_BORDER_WIDTH).toString())
            setProperty("borderArgb", settings.borderArgb.toString())
            settings.fillArgb?.let { setProperty("fillArgb", it.toString()) }
            setProperty("colorFollowsTheme", settings.colorFollowsTheme.toString())
            setProperty("wallThickness", settings.wallThickness.coerceIn(
                MIN_SOLID_WALL_THICKNESS, MAX_SOLID_WALL_THICKNESS).toString())
        }, "ViveNotes shape settings")
    }
}
