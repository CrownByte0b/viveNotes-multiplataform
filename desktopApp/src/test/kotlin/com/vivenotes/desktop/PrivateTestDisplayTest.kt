package com.vivenotes.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * On a Linux desktop the UI suites get a private Xvfb display (`gradle/test-display.gradle.kts`), so
 * their windows, pointer moves and keys stay off the session's display and its window manager; CI and
 * `-Pvive.tests.liveDisplay=true` keep the display they were given. A fixture build applies the
 * script and its one test records the display it ran on.
 */
class PrivateTestDisplayTest {
    @get:Rule val temp = TemporaryFolder()

    private val session = mapOf("DISPLAY" to ":vive-session", "WAYLAND_DISPLAY" to "vive-session-wl",
        "XDG_SESSION_TYPE" to "wayland")

    @Test
    fun aDesktopSessionsSuitesRunOnAPrivateXvfbThatEndsWithTheBuild() {
        val fixture = fixture()
        val (probe, log) = build(fixture, session, "--configuration-cache")
        val display = probe.getValue("DISPLAY")
        assertTrue(Regex(":\\d+").matches(display), "a private display: $probe\n$log")
        assertTrue(display.drop(1).toInt() >= 120, display)
        assertNotEquals(System.getenv("DISPLAY"), display, "not the display this suite runs on")
        assertNull(probe["WAYLAND_DISPLAY"], "no Wayland display to choose instead")
        assertEquals("x11", probe["XDG_SESSION_TYPE"])
        assertEquals("1920x1200", probe["SCREEN"], "a working server, CI's size")
        assertTrue("UI tests run on a private Xvfb display $display" in log, log)
        val socket = File("/tmp/.X11-unix/X${display.drop(1)}")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (socket.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse(socket.exists(), "the server ends with the build")
        assertFalse(File("/tmp/.X${display.drop(1)}-lock").exists())
    }

    @Test
    fun theLiveDisplayPropertyAndCiKeepTheDisplayTheyWereGiven() {
        val fixture = fixture()
        for ((environment, arguments) in listOf(
            session to arrayOf("-Pvive.tests.liveDisplay=true"),
            session + ("CI" to "true") to emptyArray(),
        )) {
            val (probe, log) = build(fixture, environment, *arguments)
            assertEquals(":vive-session", probe["DISPLAY"], log)
            assertEquals("vive-session-wl", probe["WAYLAND_DISPLAY"], log)
            assertFalse("private Xvfb display" in log, log)
        }
    }

    private fun fixture(): File {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        assumeTrue("Xvfb is installed", System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { File(it, "Xvfb").canExecute() })
        val project = File(System.getProperty("vivenotes.test.projectRoot"))
        val fixture = temp.newFolder("display")
        File(project, "gradle/test-display.gradle.kts").copyTo(File(fixture, "test-display.gradle.kts"))
        File(fixture, "settings.gradle.kts").writeText("rootProject.name = \"display-fixture\"\n")
        File(fixture, "build.gradle.kts").writeText("""
            plugins { java }
            repositories { mavenCentral() }
            dependencies { testImplementation("junit:junit:4.13.2") }
            tasks.test {
                systemProperty("probe.out", file("probe.txt").absolutePath)
                outputs.upToDateWhen { false }
            }
            apply(from = "test-display.gradle.kts")
        """.trimIndent())
        File(fixture, "src/test/java/DisplayProbeTest.java").apply { parentFile.mkdirs() }.writeText("""
            import java.awt.DisplayMode;
            import java.awt.GraphicsEnvironment;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.Test;

            public class DisplayProbeTest {
                @Test public void record() throws Exception {
                    StringBuilder out = new StringBuilder();
                    for (String name : new String[] {"DISPLAY", "WAYLAND_DISPLAY", "XDG_SESSION_TYPE"}) {
                        String value = System.getenv(name);
                        if (value != null) out.append(name).append('=').append(value).append('\n');
                    }
                    String display = System.getenv("DISPLAY");
                    if (display != null && display.matches(":\\d+")) {
                        DisplayMode mode = GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getDefaultScreenDevice().getDisplayMode();
                        out.append("SCREEN=").append(mode.getWidth()).append('x').append(mode.getHeight()).append('\n');
                    }
                    Files.writeString(Paths.get(System.getProperty("probe.out")), out);
                }
            }
        """.trimIndent())
        return fixture
    }

    /** Runs the fixture's test under [environment]; returns what it recorded and Gradle's output. */
    private fun build(fixture: File, environment: Map<String, String>, vararg arguments: String): Pair<Map<String, String>, String> {
        val probe = File(fixture, "probe.txt").apply { delete() }
        val log = File(temp.root, "gradle.log")
        val process = ProcessBuilder(
            listOf(File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle").path, "test",
                "--offline", "--console=plain",
                "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome")) + arguments,
        ).directory(fixture).redirectErrorStream(true).redirectOutput(log).apply {
            val env = environment()
            env["JAVA_HOME"] = System.getProperty("java.home")
            for (name in listOf("DISPLAY", "WAYLAND_DISPLAY", "XDG_SESSION_TYPE", "CI")) env.remove(name)
            env.putAll(environment)
        }.start()
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("the fixture build timed out:\n${log.readText()}")
        }
        val output = log.readText()
        assertEquals(0, process.exitValue(), output)
        val recorded = probe.readLines().filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return recorded to output
    }
}
