package com.vivenotes.localization

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlinx.coroutines.runBlocking
import org.junit.runner.JUnitCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizationEnvironmentTest {
    @Test
    fun loadingTheCatalogAndTranslationsDoesNotRepeatedlyQueryTheDesktopTheme() {
        val directory = Files.createTempDirectory("vive-localization-environment-").toFile()
        val log = File(directory, "process.log")
        var process: Process? = null
        try {
            // Count calls at Skiko's native boundary in a fresh JVM. Resource decoding, locale
            // selection and all ResourceCatalogTest assertions still use the real libraries.
            val source = File(directory, "SystemThemeHelper.java").apply {
                writeText("""
                    package org.jetbrains.skiko;
                    public final class SystemThemeHelper {
                        public static final SystemThemeHelper INSTANCE = new SystemThemeHelper();
                        public int getCurrentSystemTheme() {
                            String key = "vivenotes.test.systemThemeLookups";
                            int reads = Integer.parseInt(System.getProperty(key, "0"));
                            System.setProperty(key, Integer.toString(reads + 1));
                            return 0;
                        }
                    }
                """.trimIndent())
            }
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Tests require a JDK" }
            assertEquals(0, compiler.run(null, null, null, "-d", directory.path, source.path))
            val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/$javaName").absolutePath,
                "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
                "-cp", directory.path + File.pathSeparator + System.getProperty("java.class.path"),
                LocalizationEnvironmentProcess::class.java.name,
            ).redirectErrorStream(true).redirectOutput(log).start()
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Localization probe timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
            assertTrue(log.readText().contains("localization environment passed"), log.readText())
        } finally {
            process?.destroyForcibly()?.waitFor()
            directory.deleteRecursively()
        }
    }
}

object LocalizationEnvironmentProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val initialLocale = Locale.getDefault()
        val result = JUnitCore.runClasses(ResourceCatalogTest::class.java)
        assertTrue(result.wasSuccessful(), result.failures.joinToString("\n") { it.trace })
        assertEquals(initialLocale, Locale.getDefault())
        val reads = checkNotNull(System.getProperty("vivenotes.test.systemThemeLookups")).toInt()
        assertTrue(reads <= 2, "Catalog tests queried the desktop theme $reads times for only two locales")
        runBlocking { Localization.initialize() }
        assertEquals(reads + 1, System.getProperty("vivenotes.test.systemThemeLookups").toInt(),
            "Default initialization must resolve the system environment once for the whole catalog")
        assertEquals("Save", Strings.save)
        println("localization environment passed: $reads desktop-theme lookups")
    }
}
