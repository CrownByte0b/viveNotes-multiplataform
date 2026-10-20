package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseRunConfigurationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun runReleaseStartsTheCompiledJarsOnJetBrainsRuntimeInAWaylandSession() {
        // Only Linux runtimes have the Wayland toolkit, so the build looks for JBR only there.
        assumeTrue(System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        val project = File(System.getProperty("vivenotes.test.projectRoot"))
        val fixture = temp.newFolder("app")
        for (path in listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "shared/build.gradle.kts", "webApp/build.gradle.kts", "desktopApp/build.gradle.kts",
            "gradle/karma/managed-chromium.js", "gradle/browser-test-page.gradle.kts",
            "gradle/tests/ratex-webpack.cjs", "webApp/webpack.config.d/ratex.js",
        )) {
            File(project, path).copyTo(File(fixture, path).apply { parentFile.mkdirs() })
        }
        val jbr = temp.newFolder("jbr")
        File(jbr, "release").writeText("IMPLEMENTOR=\"JetBrains s.r.o.\"\n")
        File(jbr, "bin/java").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
        val init = File(temp.root, "probe.gradle").apply {
            writeText("""
                gradle.taskGraph.whenReady { graph ->
                    def run = gradle.rootProject.project(':desktopApp').tasks.getByName('runRelease')
                    def report = graph.allTasks.collect { 'task|' + it.path }
                    report.add('executable|' + run.executable)
                    report.addAll(run.jvmArgs.collect { 'jvmArg|' + it })
                    report.add('profile|' + run.systemProperties['vivenotes.profile'])
                    gradle.rootProject.file('release-run-report.txt').text = report.join('\n')
                }
            """.trimIndent())
        }
        runGradle(
            fixture, ":desktopApp:runRelease", "--dry-run", "--init-script", init.path,
            session = mapOf("XDG_SESSION_TYPE" to "wayland", "WAYLAND_DISPLAY" to "wayland-test",
                "VIVE_JBR_HOME" to jbr.absolutePath),
        )
        val report = File(fixture, "release-run-report.txt").readLines()

        assertTrue("task|:desktopApp:runRelease" in report, report.toString())
        // ProGuard 7.10 writes classes the JVM rejects (see desktopApp/build.gradle.kts), and 7.8
        // cannot read the build JDK: the release run uses the jars as compiled.
        assertTrue(report.none { it.startsWith("task|:desktopApp:proguard") }, report.toString())
        // The native Wayland host refuses any other runtime.
        assertEquals("executable|${File(jbr, "bin/java").absolutePath}", report.single { it.startsWith("executable|") })
        assertTrue("jvmArg|--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED" in report, report.toString())
        assertTrue("jvmArg|--enable-native-access=ALL-UNNAMED" in report, report.toString())
        // No profile property: the installed app's production profile, as packaged builds use.
        assertEquals("profile|null", report.single { it.startsWith("profile|") })
    }

    private fun runGradle(fixture: File, vararg arguments: String, session: Map<String, String>) {
        val gradle = File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle")
        val command = listOf(gradle.path) + arguments + listOf(
            "--offline", "--console=plain", "--no-configuration-cache",
            "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome"),
        )
        val log = File(temp.root, "gradle.log")
        val process = ProcessBuilder(command).directory(fixture).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
            environment().putAll(session)
        }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Release run configuration probe timed out:\n${log.readText()}")
        }
        assertEquals(0, process.exitValue(), log.readText())
    }
}
