package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlatpakPackagingTest {
    @Test
    fun packagingContracts() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val root = File(System.getProperty("vivenotes.test.projectRoot"))
        val process = ProcessBuilder("python3", "-B", "packaging/flatpak/test_packaging.py")
            .directory(root).redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Packaging contracts timed out")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }
}
