package com.vivenotes.desktop

import com.vivenotes.data.EraserMode
import com.vivenotes.model.ink.LineType
import com.vivenotes.workspace.*
import java.io.File
import java.util.Properties

internal class InkSettingsFile(private val file: File) {
    fun load(): InkSettings {
        val values = file.readProperties() ?: return InkSettings()
        val defaults = InkSettings()
        fun number(key: String, default: Float) = values.getProperty(key)?.toFloatOrNull() ?: default
        fun integer(key: String, default: Int) = values.getProperty(key)?.toIntOrNull() ?: default
        fun flag(key: String, default: Boolean) = values.getProperty(key)?.toBooleanStrictOrNull() ?: default
        fun pen(prefix: String, default: InkPenSettings) = InkPenSettings(
            brush = InkBrush.entries.firstOrNull { it.name == values.getProperty("$prefix.brush") } ?: default.brush,
            sizeDp = number("$prefix.sizeDp", default.sizeDp),
            colorArgb = integer("$prefix.colorArgb", default.colorArgb),
            colorFollowsTheme = flag("$prefix.colorFollowsTheme", default.colorFollowsTheme),
            lineType = LineType.entries.firstOrNull { it.name == values.getProperty("$prefix.lineType") } ?: default.lineType,
            pressure = integer("$prefix.pressure", default.pressure),
            stabilization = integer("$prefix.stabilization", default.stabilization),
            holdForStraightLine = flag("$prefix.holdForStraightLine", default.holdForStraightLine),
        )
        return InkSettings(
            pen = pen("pen", defaults.pen), pen2 = pen("pen2", defaults.pen2), pen3 = pen("pen3", defaults.pen3),
            highlighter = InkHighlighterSettings(number("highlighter.sizeDp", defaults.highlighter.sizeDp),
                integer("highlighter.colorArgb", defaults.highlighter.colorArgb)),
            eraser = InkEraserSettings(EraserMode.entries.firstOrNull { it.name == values.getProperty("eraser.mode") }
                ?: defaults.eraser.mode, number("eraser.sizeDp", defaults.eraser.sizeDp)),
            ruler = InkRulerSettings(RulerKind.entries.firstOrNull { it.name == values.getProperty("ruler.kind") }
                ?: defaults.ruler.kind, integer("ruler.diameterDp", defaults.ruler.diameterDp)),
            table = TableToolSettings(
                inkOnly = flag("table.inkOnly", true), columns = integer("table.columns", 3), rows = integer("table.rows", 3),
                headerRow = flag("table.headerRow", true), headerColumn = flag("table.headerColumn", false),
                borderWidth = integer("table.borderWidth", 1), borderArgb = integer("table.borderArgb", defaults.table.borderArgb),
                fillArgb = values.getProperty("table.fillArgb")?.toIntOrNull(), colorFollowsTheme = flag("table.colorFollowsTheme", true)),
            palette = values.getProperty("palette")?.split(',')?.mapNotNull { it.toIntOrNull() } ?: defaults.palette,
        ).normalized()
    }

    fun save(settings: InkSettings) {
        val value = settings.normalized()
        file.writePropertiesAtomically(Properties().apply {
            fun pen(prefix: String, pen: InkPenSettings) {
                setProperty("$prefix.brush", pen.brush.name)
                setProperty("$prefix.sizeDp", pen.sizeDp.toString())
                setProperty("$prefix.colorArgb", pen.colorArgb.toString())
                setProperty("$prefix.colorFollowsTheme", pen.colorFollowsTheme.toString())
                setProperty("$prefix.lineType", pen.lineType.name)
                setProperty("$prefix.pressure", pen.pressure.toString())
                setProperty("$prefix.stabilization", pen.stabilization.toString())
                setProperty("$prefix.holdForStraightLine", pen.holdForStraightLine.toString())
            }
            pen("pen", value.pen); pen("pen2", value.pen2); pen("pen3", value.pen3)
            setProperty("highlighter.sizeDp", value.highlighter.sizeDp.toString())
            setProperty("highlighter.colorArgb", value.highlighter.colorArgb.toString())
            setProperty("eraser.mode", value.eraser.mode.name)
            setProperty("eraser.sizeDp", value.eraser.sizeDp.toString())
            setProperty("ruler.kind", value.ruler.kind.name)
            setProperty("ruler.diameterDp", value.ruler.diameterDp.toString())
            setProperty("table.inkOnly", value.table.inkOnly.toString())
            setProperty("table.columns", value.table.columns.toString())
            setProperty("table.rows", value.table.rows.toString())
            setProperty("table.headerRow", value.table.headerRow.toString())
            setProperty("table.headerColumn", value.table.headerColumn.toString())
            setProperty("table.borderWidth", value.table.borderWidth.toString())
            setProperty("table.borderArgb", value.table.borderArgb.toString())
            setProperty("table.colorFollowsTheme", value.table.colorFollowsTheme.toString())
            value.table.fillArgb?.let { setProperty("table.fillArgb", it.toString()) }
            setProperty("palette", value.palette.joinToString(","))
        }, "ViveNotes ink settings")
    }
}
