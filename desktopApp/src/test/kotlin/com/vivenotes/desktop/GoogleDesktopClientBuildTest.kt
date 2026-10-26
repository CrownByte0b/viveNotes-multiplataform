package com.vivenotes.desktop

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the build finds the Google Desktop OAuth client that both profiles sign in with: a
 * developer's `local.properties` or `-P` (the Flathub manifest), else the release workflows'
 * environment. The app carries only the client id; the managed server holds the client secret and
 * exchanges the sign-in code with it, so no build is given one.
 */
class GoogleDesktopClientBuildTest {
    @get:Rule val temp = TemporaryFolder()

    private val root = File(System.getProperty("vivenotes.test.projectRoot"))

    @Test
    fun aBuildWithNoClientLeavesGoogleSignInOffAndCarriesNoSecret() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val fixture = fixture()

        val info = buildInfo(fixture, variables = emptyMap())

        assertEquals("", info.getProperty("googleDesktopClientId"))
        assertEquals(setOf("version", "cloudBaseUrl", "googleDesktopClientId"), info.stringPropertyNames())
    }

    @Test
    fun theReleaseEnvironmentFillsTheClientIdOnlyWhereLocalPropertiesLeaveItUnset() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val variables = mapOf(CLIENT_ID to "from-environment.apps.googleusercontent.com")

        assertEquals("from-environment.apps.googleusercontent.com",
            buildInfo(fixture(), variables).getProperty("googleDesktopClientId"))

        val local = fixture("app-with-local-properties").apply {
            File(this, "local.properties").writeText("vive.googleDesktopClientId=from-local-properties\n")
        }
        assertEquals("from-local-properties", buildInfo(local, variables).getProperty("googleDesktopClientId"))
    }

    /** Only the steps that build what ships see the client id; tests and lint run without it. */
    @Test
    fun onlyTheReleaseBuildStepsReceiveTheClientId() {
        val builds = mapOf("windows-msi.yml" to "Build MSI", "flatpak.yml" to "Build Flatpak bundle")
        for ((file, buildStep) in builds) {
            val steps = steps(File(root, ".github/workflows/$file").readText())
            for ((name, body) in steps) {
                assertEquals(name == buildStep, "$CLIENT_ID: \${{ secrets.$CLIENT_ID }}" in body, "$file step \"$name\"")
            }
            assertTrue(buildStep in steps, "$file has no \"$buildStep\" step")
        }
        val tests = File(root, ".github/workflows/desktop-tests.yml").readText()
        assertFalse(CLIENT_ID in tests, "desktop-tests.yml must not see $CLIENT_ID")
    }

    @Test
    fun noWorkflowHandsABuildAGoogleClientSecret() {
        File(root, ".github/workflows").listFiles()!!.filter { it.extension == "yml" }.forEach { workflow ->
            assertFalse("GOOGLE_DESKTOP_CLIENT_SECRET" in workflow.readText(), "${workflow.name} passes a client secret")
        }
    }

    private fun fixture(name: String = "app"): File {
        val fixture = temp.newFolder(name)
        for (path in listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "shared/build.gradle.kts", "webApp/build.gradle.kts", "desktopApp/build.gradle.kts",
            "gradle/karma/managed-chromium.js", "gradle/browser-test-page.gradle.kts",
            "gradle/tests/ratex-webpack.cjs", "webApp/webpack.config.d/ratex.js",
            "desktopApp/src/main/resources/build-info.properties",
        )) File(root, path).copyTo(File(fixture, path).apply { parentFile.mkdirs() })
        return fixture
    }

    /** Runs the fixture's `processResources` and reads back the expanded `build-info.properties`. */
    private fun buildInfo(fixture: File, variables: Map<String, String>): Properties {
        val command = listOf(File(System.getProperty("vivenotes.test.gradleHome"), "bin/gradle").path,
            ":desktopApp:processResources", "--rerun-tasks", "--offline", "--console=plain",
            "--no-configuration-cache", "--gradle-user-home", System.getProperty("vivenotes.test.gradleUserHome"))
        val log = File(fixture, "probe.log")
        val process = ProcessBuilder(command).directory(fixture).redirectErrorStream(true).redirectOutput(log)
            .apply {
                environment()["JAVA_HOME"] = System.getProperty("java.home")
                environment().remove(CLIENT_ID)
                environment().putAll(variables)
            }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Build-info probe timed out:\n${log.readText()}")
        }
        assertEquals(0, process.exitValue(), log.readText())
        return File(fixture, "desktopApp/build/resources/main/build-info.properties").inputStream()
            .use { stream -> Properties().apply { load(stream) } }
    }

    /** Each `- name:` step of a workflow and the lines under it. */
    private fun steps(workflow: String): Map<String, String> =
        workflow.split(Regex("""\n\s+- name: """)).drop(1).associate { step ->
            step.substringBefore('\n').trim() to step.substringAfter('\n', "")
        }

    private companion object {
        const val CLIENT_ID = "VIVE_GOOGLE_DESKTOP_CLIENT_ID"
    }
}
