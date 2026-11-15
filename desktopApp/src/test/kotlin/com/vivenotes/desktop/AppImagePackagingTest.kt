package com.vivenotes.desktop

import com.vivenotes.desktop.update.GitHubReleaseFeed
import com.vivenotes.desktop.update.appImageName
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppImagePackagingTest {
    private val root = File(System.getProperty("vivenotes.test.projectRoot"))

    @Test
    fun packagingContracts() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val process = ProcessBuilder("python3", "-B", "packaging/appimage/test_packaging.py")
            .directory(root).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "AppImage packaging contracts timed out")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    /** Desktops match the AppImage's windows to its entry through StartupWMClass. */
    @Test
    fun desktopEntryNamesTheProductionWindowClass() {
        val build = File(root, "packaging/appimage/build.py").readText()
        val declared = Regex("""^WINDOW_CLASS = "(.+)"$""", RegexOption.MULTILINE).find(build)?.groupValues?.get(1)
        assertEquals(WINDOW_CLASS, declared)
        assertEquals(WINDOW_CLASS, linuxWindowClass(DesktopProfile.PRODUCTION, flatpakId = null))
    }

    /**
     * The in-app updater and AppImage tools look for the same files: the update information names the
     * repository the app asks and a control file pattern that matches the image name it downloads.
     */
    @Test
    fun updateInformationNamesTheRepositoryAndFilesTheUpdaterUses() {
        val build = File(root, "packaging/appimage/build.py").readText()
        val information = Regex("""^UPDATE_INFORMATION = "(.+)"$""", RegexOption.MULTILINE).find(build)?.groupValues?.get(1)
        val (transport, owner, repository, channel, pattern) = information.orEmpty().split('|')
        assertEquals("gh-releases-zsync", transport)
        assertEquals("latest", channel)
        assertEquals("https://api.github.com/repos/$owner/$repository/releases/latest", GitHubReleaseFeed.LATEST_RELEASE.toString())
        val glob = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
        assertTrue(glob.matches(appImageName("1.3.0") + ".zsync"), pattern)
        assertTrue(build.contains("f\"ViveNotes-{version}-x86_64.AppImage\""), "build.py names the image as the updater expects")
        val attach = File(root, ".github/scripts/attach_release_artifacts.py").readText()
        assertTrue(attach.contains("f\"ViveNotes-{version}-x86_64.AppImage\""), "and so does every release")
        assertEquals("ViveNotes-1.3.0-x86_64.AppImage", appImageName("1.3.0"))
    }
}
