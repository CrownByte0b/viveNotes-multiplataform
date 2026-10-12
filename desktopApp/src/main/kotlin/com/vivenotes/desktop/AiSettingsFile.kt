package com.vivenotes.desktop

import com.vivenotes.ai.AiModelChoices
import com.vivenotes.ai.AiModelPreferences
import com.vivenotes.ai.FormulaEngine
import java.io.File
import java.util.Properties

/**
 * Which formula model the user picked with Use, and which ones they deleted: Android's `ai`
 * DataStore, as a properties file beside the other device preferences. What is installed is never
 * stored here; the model store reads that from the model files themselves.
 */
internal class AiSettingsFile(private val file: File) : AiModelPreferences {
    override fun load(): AiModelChoices {
        val values = file.readProperties() ?: return AiModelChoices()
        return AiModelChoices(
            formulaEngine = values.getProperty(FORMULA_ENGINE)?.let(::engineNamed),
            deletedFormulaEngines = values.getProperty(DELETED_FORMULA_MODELS).orEmpty()
                .split(',').mapNotNullTo(mutableSetOf(), ::engineNamed),
        )
    }

    override fun save(choices: AiModelChoices) {
        file.writePropertiesAtomically(Properties().apply {
            choices.formulaEngine?.let { setProperty(FORMULA_ENGINE, it.name) }
            setProperty(DELETED_FORMULA_MODELS,
                choices.deletedFormulaEngines.sortedBy(FormulaEngine::ordinal).joinToString(",") { it.name })
        }, "ViveNotes model preferences")
    }

    private fun engineNamed(name: String): FormulaEngine? = FormulaEngine.entries.firstOrNull { it.name == name.trim() }

    private companion object {
        const val FORMULA_ENGINE = "formulaEngine"
        const val DELETED_FORMULA_MODELS = "deletedFormulaModels"
    }
}
