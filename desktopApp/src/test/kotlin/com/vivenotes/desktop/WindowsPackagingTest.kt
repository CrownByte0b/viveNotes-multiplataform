package com.vivenotes.desktop

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPackagingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun stagesVerifiedWindowsHelperAndNoticesAndRejectsTampering() {
        // This offline Gradle fixture uses the Unix launcher. The real MSI lifecycle runs in
        // windows-msi.yml on Windows, where an installer can actually be executed.
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val root = File(System.getProperty("vivenotes.test.projectRoot"))
        val fixture = temp.newFolder("app")
        for (path in listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "shared/build.gradle.kts", "webApp/build.gradle.kts", "desktopApp/build.gradle.kts",
            "gradle/karma/managed-chromium.js", "gradle/browser-test-page.gradle.kts",
            "gradle/tests/ratex-webpack.cjs", "webApp/webpack.config.d/ratex.js",
        )) File(root, path).copyTo(File(fixture, path).apply { parentFile.mkdirs() })
        val archive = File(fixture, "uv.zip")
        zip(archive, mapOf("uv.exe" to "fake Windows helper", "unwanted.exe" to "not shipped"))
        val jar = File(fixture, "dependency.jar")
        zip(jar, mapOf("META-INF/LICENSE.txt" to "library license", "META-INF/NOTICE" to "notice",
            "../../NOTICE" to "escape", "com/example/Code.class" to "not a notice"))
        val license = File(fixture, "LICENSE.md").apply { writeText("app license") }
        val init = File(fixture, "probe.gradle").apply {
            writeText("""
                gradle.projectsEvaluated {
                    def p = gradle.rootProject.project(':desktopApp')
                    def dist = p.extensions.getByName('compose').desktop.application.nativeDistributions
                    assert dist.packageName == 'Vive Notes'
                    assert dist.includeAllModules
                    assert dist.targetFormats.collect { it.toString() }.contains('Msi')
                    assert dist.windows.perUserInstall
                    assert dist.windows.menu && dist.windows.shortcut && dist.windows.dirChooser
                    assert dist.windows.upgradeUuid == '6d1f580e-dc54-4d47-bc21-c84aef8f8011'
                    assert !p.extensions.getByName('compose').desktop.application.buildTypes.release.proguard.isEnabled().get()
                    p.tasks.named('prepareWindowsResources').configure {
                        uvUrl.set(gradle.rootProject.file('uv.zip').toURI().toString())
                        uvSha256.set('${sha256(archive)}')
                        jars.setFrom(gradle.rootProject.file('dependency.jar'))
                        notices.setFrom(gradle.rootProject.file('LICENSE.md'))
                    }
                }
            """.trimIndent())
        }
        assertEquals(0, runGradle(fixture, init), File(fixture, "probe.log").readText())
        val resources = File(fixture, "desktopApp/build/windows/resources/windows-x64")
        assertEquals("fake Windows helper", File(resources, "bin/uv.exe").readText())
        assertFalse(File(resources, "bin/unwanted.exe").exists())
        assertEquals(license.readText(), File(resources, "licenses/LICENSE.md").readText())
        val digest = sha256(jar)
        assertEquals("library license",
            File(resources, "licenses/dependencies/$digest-dependency.jar/META-INF/LICENSE.txt").readText())
        assertEquals("notice",
            File(resources, "licenses/dependencies/$digest-dependency.jar/META-INF/NOTICE").readText())
        assertEquals("$digest  dependency.jar\n", File(resources, "licenses/SHA256SUMS").readText())
        assertFalse(File(resources, "licenses/NOTICE").exists())
        assertFalse(resources.walkTopDown().any { it.name == "Code.class" })

        archive.appendText("tampered")
        assertTrue(runGradle(fixture, init) != 0)
        assertTrue(File(fixture, "probe.log").readText().contains("SHA-256 mismatch"))
        assertFalse(File(resources, "bin/uv.exe").exists(), "A failed verification must not leave an old helper")
    }

    @Test
    fun windowsIconContainsStandardSizesAndWorkflowRunsInstalledSmoke() {
        val root = File(System.getProperty("vivenotes.test.projectRoot"))
        val icon = File(root, "packaging/windows/ViveNotes.ico").readBytes()
        assertEquals(listOf(0, 0, 1, 0), icon.take(4).map { it.toInt() })
        val count = (icon[4].toInt() and 255) or ((icon[5].toInt() and 255) shl 8)
        val sizes = (0 until count).map { icon[6 + it * 16].toInt() and 255 }.toSet()
        assertTrue(sizes.containsAll(setOf(0, 16, 32, 48, 64, 128)))
        val workflow = File(root, ".github/workflows/windows-msi.yml").readText()
        assertTrue(workflow.contains("runs-on: windows-2025"))
        assertTrue(workflow.contains("./packaging/windows/build.ps1"))
        assertTrue(workflow.contains("./packaging/windows/smoke-test.ps1"))
    }

    private fun runGradle(fixture: File, init: File): Int {
        val command = listOf(File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle").path,
            ":desktopApp:prepareWindowsResources", "--rerun-tasks", "--offline", "--console=plain",
            "--no-configuration-cache", "--init-script", init.path, "--gradle-user-home",
            System.getProperty("vivenotes.test.gradleUserHome"))
        val log = File(fixture, "probe.log")
        val process = ProcessBuilder(command).directory(fixture).redirectErrorStream(true).redirectOutput(log)
            .apply { environment()["JAVA_HOME"] = System.getProperty("java.home") }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Windows packaging probe timed out:\n${log.readText()}")
        }
        return process.exitValue()
    }

    private fun zip(file: File, entries: Map<String, String>) = ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
