package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * The JVM UI suites open real windows and send native clicks and keys on one shared display. Run
 * at once, their windows cover each other and take each other's input and focus, so the build
 * orders them even when Gradle runs tasks in parallel, as the configuration cache does by default
 * (CI's single worker is covered by [CiTestDiagnosticsTest]).
 */
class NativeUiSuiteOrderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun theNativeUiSuitesNeverShareTheDisplayAtOnce() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
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
        val log = File(temp.root, "gradle.log")
        // Asked for last, the shared suite still comes first: without an ordering constraint Gradle
        // plans requested tasks in command-line order. Parallel execution honours the same constraint.
        val process = ProcessBuilder(
            File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle").path,
            ":desktopApp:test", ":shared:jvmTest", "--dry-run",
            "--offline", "--console=plain", "--no-configuration-cache",
            "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome"),
        ).directory(fixture).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
        }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("UI suite order probe timed out:\n${log.readText()}")
        }
        assertEquals(0, process.exitValue(), log.readText())

        val suites = listOf(":shared:jvmTest", ":desktopApp:test", ":desktopApp:waylandPopupTest")
        val planned = log.readLines().mapNotNull { Regex("^(:\\S+) SKIPPED$").find(it)?.groupValues?.get(1) }
        assertEquals(suites, planned.filter { it in suites }, planned.joinToString("\n"))
    }
}
