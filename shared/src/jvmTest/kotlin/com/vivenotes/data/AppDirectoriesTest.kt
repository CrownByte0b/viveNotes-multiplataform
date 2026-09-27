package com.vivenotes.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirectoriesTest {

    @Test
    fun linuxConfigUsesXdgConfigHomeAndRejectsRelativePaths() {
        assertEquals(File("/config/ada/vivenotes"), AppDirectories.config("Linux",
            mapOf("XDG_CONFIG_HOME" to "/config/ada")::get, "/home/ada"))
        assertEquals(File("/home/ada/.config/vivenotes"), AppDirectories.config("Linux",
            mapOf("XDG_CONFIG_HOME" to "relative")::get, "/home/ada"))
    }

    private fun data(os: String, vararg variables: Pair<String, String>) =
        AppDirectories.data(osName = os, environment = mapOf(*variables)::get, home = "/home/ada")

    @Test
    fun linuxUsesXdgDataHomeWhenItIsSet() {
        assertEquals(File("/data/ada/vivenotes"), data("Linux", "XDG_DATA_HOME" to "/data/ada"))
    }

    /** What a Flatpak sandbox sets, so the notes land inside the app's own data directory. */
    @Test
    fun aFlatpakSandboxKeepsNotesInItsOwnDataDirectory() {
        assertEquals(
            File("/home/ada/.var/app/app-id/data/vivenotes"),
            data("Linux", "XDG_DATA_HOME" to "/home/ada/.var/app/app-id/data"),
        )
    }

    @Test
    fun linuxFallsBackToTheSpecificationsDefault() {
        assertEquals(File("/home/ada/.local/share/vivenotes"), data("Linux"))
        assertEquals(File("/home/ada/.local/share/vivenotes"), data("Linux", "XDG_DATA_HOME" to ""))
    }

    /** A relative XDG path is invalid by the specification, not relative to the working directory. */
    @Test
    fun aRelativeXdgDataHomeIsIgnored() {
        assertEquals(File("/home/ada/.local/share/vivenotes"), data("Linux", "XDG_DATA_HOME" to "data"))
    }

    @Test
    fun windowsUsesTheRoamingApplicationDataFolder() {
        assertEquals(
            File("C:\\Users\\Ada\\AppData\\Roaming", "ViveNotes"),
            data("Windows 11", "APPDATA" to "C:\\Users\\Ada\\AppData\\Roaming"),
        )
        assertEquals(File("/home/ada/AppData/Roaming/ViveNotes"), data("Windows 11"))
    }

    @Test
    fun macOsUsesApplicationSupport() {
        assertEquals(File("/home/ada/Library/Application Support/ViveNotes"), data("Mac OS X"))
    }

    private fun cache(os: String, vararg variables: Pair<String, String>) =
        AppDirectories.cache(osName = os, environment = mapOf(*variables)::get, home = "/home/ada")

    @Test
    fun linuxCacheFollowsXdgCacheHomeAndItsDefault() {
        assertEquals(File("/cache/ada/vivenotes"), cache("Linux", "XDG_CACHE_HOME" to "/cache/ada"))
        assertEquals(File("/home/ada/.cache/vivenotes"), cache("Linux"))
        assertEquals(File("/home/ada/.cache/vivenotes"), cache("Linux", "XDG_CACHE_HOME" to "cache"))
    }

    /** Local, never roaming: a transfer's staging files must not follow the user between machines. */
    @Test
    fun windowsCacheIsInLocalApplicationData() {
        assertEquals(
            File("C:\\Users\\Ada\\AppData\\Local", "ViveNotes/Cache"),
            cache("Windows 11", "LOCALAPPDATA" to "C:\\Users\\Ada\\AppData\\Local", "APPDATA" to "C:\\Roaming"),
        )
        assertEquals(File("/home/ada/AppData/Local/ViveNotes/Cache"), cache("Windows 11"))
    }

    @Test
    fun macOsCacheIsInLibraryCaches() {
        assertEquals(File("/home/ada/Library/Caches/ViveNotes"), cache("Mac OS X"))
    }
}
