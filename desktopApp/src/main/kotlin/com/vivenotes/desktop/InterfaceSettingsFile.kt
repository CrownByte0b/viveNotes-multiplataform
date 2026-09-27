package com.vivenotes.desktop

import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import java.io.File
import java.util.Properties

/** Per-user interface preferences, kept separate from the Android-compatible notes database. */
internal class InterfaceSettingsFile(private val file: File) {
    fun load(): InterfaceSettings {
        val properties = file.readProperties() ?: return InterfaceSettings()
        return InterfaceSettings(
            displayScale = properties.getProperty("displayScale")?.toFloatOrNull() ?: InterfaceSettings().displayScale,
            uiScale = properties.getProperty("uiScale")?.toFloatOrNull() ?: 1f,
            fontScale = properties.getProperty("fontScale")?.toFloatOrNull() ?: 1f,
        ).normalized()
    }

    fun save(settings: InterfaceSettings) {
        val value = settings.normalized()
        file.writePropertiesAtomically(Properties().apply {
            setProperty("displayScale", value.displayScale.toString())
            setProperty("uiScale", value.uiScale.toString())
            setProperty("fontScale", value.fontScale.toString())
        }, "ViveNotes interface preferences")
    }
}
