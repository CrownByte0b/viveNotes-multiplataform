package com.vivenotes.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirectoriesTest {

    private val home = File("/home/ada").absoluteFile
    private val configHome = File("/config/ada").absoluteFile
    private val dataHome = File("/data/ada").absoluteFile
    private val cacheHome = File("/cache/ada").absoluteFile

    @Test
    fun linuxConfigUsesXdgConfigHomeAndRejectsRelativePaths() {
        assertEquals(File(configHome, "vivenotes"), AppDirectories.config("Linux",
            mapOf("XDG_CONFIG_HOME" to configHome.path)::get, home.path))
        assertEquals(File(home, ".config/vivenotes"), AppDirectories.config("Linux",
            mapOf("XDG_CONFIG_HOME" to "relative")::get, home.path))
    }

    private fun data(os: String, vararg variables: Pair<String, String>) =
        AppDirectories.data(osName = os, environment = mapOf(*variables)::get, home = home.path)

    @Test
    fun linuxUsesXdgDataHomeWhenItIsSet() {
        assertEquals(File(dataHome, "vivenotes"), data("Linux", "XDG_DATA_HOME" to dataHome.path))
    }

    /** What a Flatpak sandbox sets, so the notes land inside the app's own data directory. */
    @Test
    fun aFlatpakSandboxKeepsNotesInItsOwnDataDirectory() {
        val sandboxData = File(home, ".var/app/app-id/data")
        assertEquals(
            File(sandboxData, "vivenotes"),
            data("Linux", "XDG_DATA_HOME" to sandboxData.path),
        )
    }

    @Test
    fun linuxFallsBackToTheSpecificationsDefault() {
        assertEquals(File(home, ".local/share/vivenotes"), data("Linux"))
        assertEquals(File(home, ".local/share/vivenotes"), data("Linux", "XDG_DATA_HOME" to ""))
    }

    /** A relative XDG path is invalid by the specification, not relative to the working directory. */
    @Test
    fun aRelativeXdgDataHomeIsIgnored() {
        assertEquals(File(home, ".local/share/vivenotes"), data("Linux", "XDG_DATA_HOME" to "data"))
    }

    @Test
    fun windowsUsesTheRoamingApplicationDataFolder() {
        assertEquals(
            File("C:\\Users\\Ada\\AppData\\Roaming", "ViveNotes"),
            data("Windows 11", "APPDATA" to "C:\\Users\\Ada\\AppData\\Roaming"),
        )
        assertEquals(File(home, "AppData/Roaming/ViveNotes"), data("Windows 11"))
    }

    @Test
    fun macOsUsesApplicationSupport() {
        assertEquals(File(home, "Library/Application Support/ViveNotes"), data("Mac OS X"))
    }

    private fun cache(os: String, vararg variables: Pair<String, String>) =
        AppDirectories.cache(osName = os, environment = mapOf(*variables)::get, home = home.path)

    @Test
    fun linuxCacheFollowsXdgCacheHomeAndItsDefault() {
        assertEquals(File(cacheHome, "vivenotes"), cache("Linux", "XDG_CACHE_HOME" to cacheHome.path))
        assertEquals(File(home, ".cache/vivenotes"), cache("Linux"))
        assertEquals(File(home, ".cache/vivenotes"), cache("Linux", "XDG_CACHE_HOME" to "cache"))
    }

    /** Local, never roaming: a transfer's staging files must not follow the user between machines. */
    @Test
    fun windowsCacheIsInLocalApplicationData() {
        assertEquals(
            File("C:\\Users\\Ada\\AppData\\Local", "ViveNotes/Cache"),
            cache("Windows 11", "LOCALAPPDATA" to "C:\\Users\\Ada\\AppData\\Local", "APPDATA" to "C:\\Roaming"),
        )
        assertEquals(File(home, "AppData/Local/ViveNotes/Cache"), cache("Windows 11"))
    }

    @Test
    fun macOsCacheIsInLibraryCaches() {
        assertEquals(File(home, "Library/Caches/ViveNotes"), cache("Mac OS X"))
    }
}
