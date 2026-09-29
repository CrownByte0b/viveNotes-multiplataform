package com.vivenotes.desktop

import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import java.io.File
import java.util.Properties

/**
 * This device's View settings — zoom, tabs layout, canvas brightness. Android keeps them in its own
 * preferences rather than the database, so they never sync; so does the desktop.
 */
internal class ViewSettingsFile(private val file: File) {
    fun load(): ViewSettings {
        val properties = file.readProperties() ?: return ViewSettings()
        return ViewSettings(
            zoom = properties.getProperty("zoom")?.toFloatOrNull() ?: 1f,
            // A layout this build does not know falls back rather than failing.
            tabsLayout = properties.getProperty("tabsLayout")
                ?.let { name -> TabsLayout.entries.firstOrNull { it.name == name } } ?: TabsLayout.Vertical,
            canvasDark = properties.getProperty("canvasDark")?.toBooleanStrictOrNull(),
            linkPreviews = properties.getProperty("linkPreviews")?.toBooleanStrictOrNull() ?: true,
            canvasThemeDark = properties.getProperty("canvasThemeDark")?.toBooleanStrictOrNull(),
        ).normalized()
    }

    fun save(settings: ViewSettings) {
        val value = settings.normalized()
        file.writePropertiesAtomically(Properties().apply {
            setProperty("zoom", value.zoom.toString())
            setProperty("tabsLayout", value.tabsLayout.name)
            // Absent until Switch Background is first used, so the canvas follows the theme.
            value.canvasDark?.let { setProperty("canvasDark", it.toString()) }
            value.canvasThemeDark?.let { setProperty("canvasThemeDark", it.toString()) }
            setProperty("linkPreviews", value.linkPreviews.toString())
        }, "ViveNotes view preferences")
    }
}
