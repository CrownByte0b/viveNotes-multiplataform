package com.vivenotes.desktop

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopVersionBuildTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun developmentAndReleaseBuildsKeepTheAppVersionAndUseANumericNativePackageVersion() {
        val root = File(System.getProperty("vivenotes.test.projectRoot"))
        val fixture = temp.newFolder("app")
        for (path in listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "shared/build.gradle.kts", "webApp/build.gradle.kts", "desktopApp/build.gradle.kts",
            "gradle/karma/managed-chromium.js", "gradle/browser-test-page.gradle.kts",
            "gradle/tests/ratex-webpack.cjs", "webApp/webpack.config.d/ratex.js",
            "desktopApp/src/main/resources/build-info.properties",
        )) File(root, path).copyTo(File(fixture, path).apply { parentFile.mkdirs() })
        File(fixture, "gradle.properties").appendText("\nversion=1.0.0-dev.0\n")
        val init = File(fixture, "probe.gradle").apply {
            writeText("""
                gradle.projectsEvaluated {
                    def p = gradle.rootProject.project(':desktopApp')
                    def dist = p.extensions.getByName('compose').desktop.application.nativeDistributions
                    gradle.rootProject.file('native-version.txt').text = dist.packageVersion
                }
            """.trimIndent())
        }
        val versions = listOf(
            Triple(null, "1.0.0-dev.0", "1.0.0"),
            Triple("1.2.3", "1.2.3", "1.2.3"),
            Triple("2.4.6-rc.1+build.42", "2.4.6-rc.1+build.42", "2.4.6"),
            Triple("3.2.1+build.7", "3.2.1+build.7", "3.2.1"),
        )
        for ((override, appVersion, nativeVersion) in versions) {
            runGradle(fixture, init, override)
            assertEquals(nativeVersion, File(fixture, "native-version.txt").readText(), appVersion)
            val info = File(fixture, "desktopApp/build/resources/main/build-info.properties").inputStream()
                .use { stream -> Properties().apply { load(stream) } }
            assertEquals(appVersion, info.getProperty("version"))
        }
    }

    private fun runGradle(fixture: File, init: File, version: String?) {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val gradle = File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle${if (windows) ".bat" else ""}")
        val command = (if (windows) listOf("cmd", "/c", gradle.path) else listOf(gradle.path)) +
            listOf(":desktopApp:processResources", "--offline", "--console=plain",
                "--no-configuration-cache", "--max-workers=1", "--init-script", init.path,
                "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome")) +
            listOfNotNull(version?.let { "-Pversion=$it" })
        val log = File(fixture, "probe.log")
        val process = ProcessBuilder(command).directory(fixture).redirectErrorStream(true).redirectOutput(log)
            .apply {
                environment()["JAVA_HOME"] = System.getProperty("java.home")
                environment().remove("VIVE_GOOGLE_DESKTOP_CLIENT_ID")
            }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Desktop version probe timed out:\n${log.readText()}")
        }
        assertEquals(0, process.exitValue(), log.readText())
        assertTrue(File(fixture, "native-version.txt").isFile, log.readText())
    }
}
