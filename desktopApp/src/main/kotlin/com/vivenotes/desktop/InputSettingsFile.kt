package com.vivenotes.desktop

import com.vivenotes.workspace.InputSettings
import java.io.File
import java.util.Properties

/** This device's Settings → Hardware input choices, beside the other device preferences. */
internal class InputSettingsFile(private val file: File) {
    fun load(): InputSettings {
        val values = file.readProperties() ?: return InputSettings()
        return InputSettings(
            drawWithFinger = values.getProperty("drawWithFinger")?.toBooleanStrictOrNull() ?: false,
        )
    }

    fun save(settings: InputSettings) {
        file.writePropertiesAtomically(Properties().apply {
            setProperty("drawWithFinger", settings.drawWithFinger.toString())
        }, "ViveNotes input settings")
    }
}
