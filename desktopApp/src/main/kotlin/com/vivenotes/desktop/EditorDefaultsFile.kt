package com.vivenotes.desktop

import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.workspace.EditorDefaults
import java.io.File
import java.util.Properties

/** Device preferences for the next text and page, kept apart from synced document content. */
internal class EditorDefaultsFile(private val file: File) {
    fun load(): EditorDefaults {
        val values = file.readProperties() ?: return EditorDefaults()
        return EditorDefaults(
            fontFamily = values.getProperty("fontFamily") ?: "sans-serif",
            fontSize = values.getProperty("fontSize")?.toIntOrNull() ?: 15,
            paper = values.getProperty("paper")?.let { saved ->
                PaperSize.entries.firstOrNull { it.name == saved }
            } ?: PaperSize.Auto,
            ruleLines = values.getProperty("ruleLines")?.let { saved ->
                RuleLines.entries.firstOrNull { it.name == saved }
            } ?: RuleLines.GridMedium,
            customPaper = values.getProperty("customWidth")?.toFloatOrNull()?.let { width ->
                values.getProperty("customHeight")?.toFloatOrNull()?.let { height ->
                    PaperDimensions(width, height)
                }
            },
        ).normalized()
    }

    fun save(settings: EditorDefaults) {
        val defaults = settings.normalized()
        file.writePropertiesAtomically(Properties().apply {
            setProperty("fontFamily", defaults.fontFamily)
            setProperty("fontSize", defaults.fontSize.toString())
            setProperty("paper", defaults.paper.name)
            setProperty("ruleLines", defaults.ruleLines.name)
            defaults.customPaper?.let {
                setProperty("customWidth", it.widthInches.toString())
                setProperty("customHeight", it.heightInches.toString())
            }
        }, "ViveNotes editor defaults")
    }
}
