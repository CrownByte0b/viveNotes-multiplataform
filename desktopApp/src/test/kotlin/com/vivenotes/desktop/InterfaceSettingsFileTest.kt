package com.vivenotes.desktop

import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class InterfaceSettingsFileTest {
    @Test
    fun preferencesSurviveReopeningAndInvalidValuesFallBack() {
        val directory = createTempDirectory("interface-settings").toFile()
        try {
            val file = File(directory, "config/interface.properties")
            val store = InterfaceSettingsFile(file)
            assertEquals(InterfaceSettings(), store.load())
            val saved = InterfaceSettings(2f, 1.25f, 1.4f)
            store.save(saved)
            assertEquals(saved, InterfaceSettingsFile(file).load())
            file.writeText("displayScale=NaN\nuiScale=not-a-number\nfontScale=999\n")
            assertEquals(InterfaceSettings(0.75f, 1f, 1.8f), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }
}
