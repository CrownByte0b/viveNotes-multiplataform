package com.vivenotes.desktop

import com.vivenotes.data.NotesLibrary
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopProfileTest {
    @Test
    fun developmentAndProductionHaveDistinctNamesAndDirectories() {
        val root = Files.createTempDirectory("vivenotes-profiles").toFile()
        val production = DesktopProfile.PRODUCTION.directories(root)
        val development = DesktopProfile.DEVELOPMENT.directories(root)

        assertEquals("vivenotes", DesktopProfile.PRODUCTION.windowClass)
        assertEquals("Vive Notes", DesktopProfile.PRODUCTION.windowTitle)
        assertEquals("vivenotes-dev", DesktopProfile.DEVELOPMENT.windowClass)
        assertEquals("Vive Notes Dev", DesktopProfile.DEVELOPMENT.windowTitle)
        assertEquals(File(root, "devdb"), development.data)
        assertEquals(File(root, "devdb/config"), development.config)
        assertEquals(File(root, "devdb/cache"), development.cache)
        assertFalse(production.data.path.startsWith(development.data.path))
        assertFalse(production.config.path.startsWith(development.data.path))
        assertFalse(production.cache.path.startsWith(development.data.path))
    }

    @Test
    fun developmentNotesSurviveReopenWithoutTouchingProduction() = runBlocking {
        val root = Files.createTempDirectory("vivenotes-dev-profile").toFile()
        val dev = DesktopProfile.DEVELOPMENT.directories(root)
        val production = File(root, "production-notes")

        NotesLibrary.open(dev.data, dev.cache).use { library ->
            library.repository.createNotebook("Development")
        }
        NotesLibrary.open(dev.data, dev.cache).use { library ->
            assertEquals("Development", library.repository.observeTree().first().single().notebook.name)
        }
        val settingsFile = File(dev.config, "interface.properties")
        InterfaceSettingsFile(settingsFile).save(InterfaceSettings(uiScale = 1.25f))
        assertEquals(1.25f, InterfaceSettingsFile(settingsFile).load().uiScale)
        assertTrue(File(dev.data, "notes.db").isFile)
        assertFalse(production.exists())
    }

    @Test
    fun profilePropertyDefaultsToProductionAndRejectsUnknownValues() {
        assertEquals(DesktopProfile.PRODUCTION, DesktopProfile.fromProperty(null))
        assertEquals(DesktopProfile.DEVELOPMENT, DesktopProfile.fromProperty("dev"))
        assertEquals(DesktopProfile.PRODUCTION, DesktopProfile.fromProperty("production"))
        assertFailsWith<IllegalStateException> { DesktopProfile.fromProperty("other") }
    }
}
