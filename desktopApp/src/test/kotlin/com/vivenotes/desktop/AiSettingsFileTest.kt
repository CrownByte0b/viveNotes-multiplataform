package com.vivenotes.desktop

import com.vivenotes.ai.AiModelChoices
import com.vivenotes.ai.FormulaEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AiSettingsFileTest {
    @Test
    fun smallChoiceAndDeletedModelsRoundTripWithoutRenamingLegacyChoices() {
        val file = Files.createTempFile("ai-settings-small", ".properties").toFile()
        try {
            val store = AiSettingsFile(file)
            val choices = AiModelChoices(
                formulaEngine = FormulaEngine.UniMerNetSmall,
                deletedFormulaEngines = setOf(FormulaEngine.UniMerNetSmall, FormulaEngine.UniMerNetTiny),
            )
            store.save(choices)
            assertEquals(choices, AiSettingsFile(file).load())
            file.writeText("formulaEngine=UniMerNetTiny\ndeletedFormulaModels=UniMerNetSmall,FormulaNetS\n")
            assertEquals(
                AiModelChoices(FormulaEngine.UniMerNetTiny, setOf(FormulaEngine.UniMerNetSmall, FormulaEngine.FormulaNetS)),
                store.load(),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun noChoiceUntilOneIsMadeAndEveryChoiceIsKept() {
        val directory = Files.createTempDirectory("ai-settings").toFile()
        try {
            val file = File(directory, "config/ai.properties")
            val store = AiSettingsFile(file)
            assertEquals(AiModelChoices(), store.load())

            val choices = AiModelChoices(
                formulaEngine = FormulaEngine.FormulaNetS,
                deletedFormulaEngines = setOf(FormulaEngine.UniMerNetTiny, FormulaEngine.FormulaNetS),
            )
            store.save(choices)
            assertEquals(choices, AiSettingsFile(file).load())

            store.save(AiModelChoices(deletedFormulaEngines = setOf(FormulaEngine.FormulaNetS)))
            assertEquals(AiModelChoices(deletedFormulaEngines = setOf(FormulaEngine.FormulaNetS)), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** A model this build does not know — a later version's, say — is no choice at all. */
    @Test
    fun unknownModelNamesAreIgnored() {
        val file = Files.createTempFile("ai-settings-unknown", ".properties").toFile()
        try {
            file.writeText("formulaEngine=TexoLarge\ndeletedFormulaModels=FormulaNetS, Nonsense,,UniMerNetTiny\n")
            assertEquals(
                AiModelChoices(
                    formulaEngine = null,
                    deletedFormulaEngines = setOf(FormulaEngine.FormulaNetS, FormulaEngine.UniMerNetTiny),
                ),
                AiSettingsFile(file).load(),
            )
        } finally {
            file.delete()
        }
    }
}
