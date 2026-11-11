package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneNoteBuildConfigurationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun aPrebuiltLibraryAndItsLicensesArePackagedAndRefreshWithConfigurationCache() {
        val project = File(System.getProperty("vivenotes.test.projectRoot"))
        val fixture = temp.newFolder("app")
        val converter = File(fixture, "converter/vive-converter").apply { mkdirs() }
        File(converter, "Cargo.toml").writeText("[package]\nname = \"fixture\"\n")
        File(converter, "LICENSE").writeText("converter license")
        File(fixture, "converter/onenote.rs/LICENSE").apply { parentFile.mkdirs(); writeText("parser license") }
        val library = File(fixture, "prebuilt").apply { writeText("native library") }
        File(project, "gradle/onenote-converter.gradle.kts").copyTo(File(fixture, "converter.gradle.kts"))
        File(fixture, "settings.gradle.kts").writeText("rootProject.name = \"onenote-package-test\"\n")
        File(fixture, "build.gradle.kts").writeText("""
            tasks.register<ProcessResources>("jvmProcessResources") {
                destinationDir = layout.buildDirectory.dir("resources").get().asFile
            }
            apply(from = "converter.gradle.kts")
        """.trimIndent())

        runGradle(fixture)
        val resources = File(fixture, "build/resources/onenote")
        val libraries = File(resources, "native").walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, libraries.size)
        assertEquals("native library", libraries.single().readText())
        assertEquals("converter license", File(resources, "licenses/LICENSE-vive-converter.txt").readText())
        assertEquals("parser license", File(resources, "licenses/LICENSE-onenote-parser.txt").readText())

        library.writeText("updated native library")
        File(converter, "LICENSE").writeText("updated converter license")
        runGradle(fixture)
        assertEquals("updated native library", libraries.single().readText())
        assertEquals("updated converter license", File(resources, "licenses/LICENSE-vive-converter.txt").readText())
        assertTrue(File(fixture, "gradle.log").readText().contains("Reusing configuration cache"))
    }

    private fun runGradle(fixture: File) {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val gradle = File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle${if (windows) ".bat" else ""}")
        val command = (if (windows) listOf("cmd", "/c", gradle.path) else listOf(gradle.path)) + listOf(
            "jvmProcessResources", "-PviveConverterDir=converter/vive-converter", "-PviveConverterLibrary=prebuilt",
            "--offline", "--console=plain", "--configuration-cache", "--gradle-user-home",
            System.getProperty("vivenotes.test.gradleUserHome"),
        )
        val log = File(fixture, "gradle.log")
        val process = ProcessBuilder(command).directory(fixture).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
        }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("OneNote packaging probe timed out:\n${log.readText()}")
        }
        assertEquals(0, process.exitValue(), log.readText())
    }
}
