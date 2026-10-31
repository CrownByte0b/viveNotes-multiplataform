package com.vivenotes.desktop

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
}
