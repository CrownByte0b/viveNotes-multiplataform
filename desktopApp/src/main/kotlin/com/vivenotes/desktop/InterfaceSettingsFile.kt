package com.vivenotes.desktop

import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Per-user interface preferences, kept separate from the Android-compatible notes database. */
internal class InterfaceSettingsFile(private val file: File) {
    fun load(): InterfaceSettings {
        if (!file.isFile) return InterfaceSettings()
        return runCatching {
            val properties = Properties().apply { file.inputStream().use(::load) }
            InterfaceSettings(
                displayScale = properties.getProperty("displayScale")?.toFloatOrNull() ?: InterfaceSettings().displayScale,
                uiScale = properties.getProperty("uiScale")?.toFloatOrNull() ?: 1f,
                fontScale = properties.getProperty("fontScale")?.toFloatOrNull() ?: 1f,
            ).normalized()
        }.getOrDefault(InterfaceSettings())
    }

    fun save(settings: InterfaceSettings) {
        val value = settings.normalized()
        file.parentFile.mkdirs()
        val temporary = File.createTempFile("interface-", ".tmp", file.parentFile)
        try {
            val properties = Properties().apply {
                setProperty("displayScale", value.displayScale.toString())
                setProperty("uiScale", value.uiScale.toString())
                setProperty("fontScale", value.fontScale.toString())
            }
            temporary.outputStream().use { properties.store(it, "ViveNotes interface preferences") }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
