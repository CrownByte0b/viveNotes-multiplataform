package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CiTestDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun ciInitScriptReportsTestProgressAndStopsAStalledTestWorker() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val root = File(System.getProperty("vivenotes.test.projectRoot"))
        val fixture = temp.newFolder("ci-test-diagnostics")
        File(fixture, "settings.gradle").writeText("rootProject.name = 'ci-test-diagnostics'\n")
        val junit = File(org.junit.Test::class.java.protectionDomain.codeSource.location.toURI())
        val hamcrest = File(org.hamcrest.Matcher::class.java.protectionDomain.codeSource.location.toURI())
        File(fixture, "build.gradle").writeText("""
            plugins { id 'java' }
            dependencies { testImplementation files('${junit.invariantSeparatorsPath}', '${hamcrest.invariantSeparatorsPath}') }
            tasks.test {
                useJUnit()
                doFirst {
                    assert timeout.get() == java.time.Duration.ofMinutes(10)
                    assert testLogging.events*.name().containsAll(['STARTED', 'PASSED', 'SKIPPED', 'FAILED'])
                }
            }
        """.trimIndent())
        val source = File(fixture, "src/test/java/ProgressTest.java").apply { parentFile.mkdirs() }
        source.writeText("""
            public class ProgressTest {
                @org.junit.Test public void completes() { }
            }
        """.trimIndent())
        val log = File(fixture, "probe.log")
        val init = File(root, ".github/scripts/ci-tests.init.gradle")
        assertEquals(0, runGradle(fixture, log, init), log.readText())
        assertTrue(log.readText().contains("ProgressTest > completes STARTED"), log.readText())
        assertTrue(log.readText().contains("ProgressTest > completes PASSED"), log.readText())

        source.writeText("""
            public class ProgressTest {
                @org.junit.Test public void blocks() throws Exception { Thread.sleep(60000); }
            }
        """.trimIndent())
        val buildFile = File(fixture, "build.gradle")
        buildFile.writeText(buildFile.readText().replace("assert timeout.get() == java.time.Duration.ofMinutes(10)",
            "assert timeout.get() == java.time.Duration.ofSeconds(10)") +
            "\ntasks.test.timeout.set(java.time.Duration.ofSeconds(10))\n")
        assertTrue(runGradle(fixture, log, init) != 0, log.readText())
        assertTrue(log.readText().contains("ProgressTest > blocks STARTED"), log.readText())
        assertTrue(log.readText().contains("timeout", ignoreCase = true), log.readText())
    }

    private fun runGradle(fixture: File, log: File, init: File): Int {
        val command = listOf(
            File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle").path,
            "test", "--offline", "--console=plain", "--no-configuration-cache", "--max-workers=1",
            "--warning-mode=fail", "--init-script", init.path, "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome"),
        )
        val process = ProcessBuilder(command).directory(fixture).redirectErrorStream(true).redirectOutput(log)
            .apply { environment()["JAVA_HOME"] = System.getProperty("java.home") }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("CI diagnostics fixture timed out:\n${log.readText()}")
        }
        return process.exitValue()
    }
}
