package com.vivenotes.math

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/**
 * What the runtime does around uv: finds it and refuses an old one, records what an install was
 * for, removes everything it installed, and runs uv in its own directories. The install itself, with
 * the real uv, is [MathRuntimeFixture]'s and is checked at the end here.
 */
class UvMathRuntimeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val noUv = UvLookup.Unavailable("Installing the math engine needs uv.")

    private fun runtime(
        root: File = temporary.newFolder(UvMathRuntime.DIRECTORY),
        uv: () -> UvLookup = { noUv },
        project: MathEngineProject = MathEngineProject(),
        log: DebugLog = DebugLog(),
    ) = UvMathRuntime(root, log, project, uv, Dispatchers.IO, windows)

    private suspend fun UvMathRuntime.settled(): MathRuntimeState = withTimeout(30_000) {
        state.first { it !is MathRuntimeState.Checking && it !is MathRuntimeState.Installing }
    }

    @Test
    fun withoutUvNothingIsInstalledAndTheReasonIsShown() = runBlocking {
        runtime().use { runtime ->
            assertEquals(MathRuntimeState.UvUnavailable(noUv.reason), runtime.settled())

            runtime.install()
            delay(200)
            assertEquals(MathRuntimeState.UvUnavailable(noUv.reason), runtime.settled())

            val failure = runCatching { runtime.engine.analyze("x") }.exceptionOrNull()
            assertTrue("$failure", failure is MathEngineUnavailableException)
            assertEquals("The math engine is not installed. Install it in Settings → Models.", failure!!.message)
        }
    }

    @Test
    fun withUvTheInstallIsOffered() = runBlocking {
        val uv = UvLookup.Found(File("uv"), UvVersion(0, 12, 19))
        runtime(uv = { uv }).use { assertEquals(MathRuntimeState.NotInstalled, it.settled()) }
    }

    @Test
    fun uvIsFoundOnThePathThenWhereItsInstallersPutIt() {
        val home = temporary.newFolder("home")
        val bin = temporary.newFolder("bin")
        val name = if (windows) "uv.exe" else "uv"
        val version: (File) -> String? = { "uv 0.12.19 (1a2b3c 2026-10-01)" }

        assertEquals(UvLookup.Unavailable("Installing the math engine needs uv. Get it from docs.astral.sh/uv, then install again."),
            UvLookup.find(explicit = null, path = bin.path, home = home, windows = windows, version = version))

        val installed = File(home, ".local/bin/$name").executable()
        assertEquals(UvLookup.Found(installed, UvVersion(0, 12, 19)),
            UvLookup.find(explicit = null, path = bin.path, home = home, windows = windows, version = version))

        val onPath = File(bin, name).executable()
        assertEquals(UvLookup.Found(onPath, UvVersion(0, 12, 19)),
            UvLookup.find(explicit = null, path = bin.path, home = home, windows = windows, version = version))
    }

    @Test
    fun anExplicitPathIsUsedOrReportedButNeverSearchedPast() {
        val elsewhere = File(temporary.newFolder("tools"), "uv-custom").executable()
        val version: (File) -> String? = { "uv 0.9.0" }

        assertEquals(UvLookup.Found(elsewhere, UvVersion(0, 9, 0)),
            UvLookup.find(explicit = elsewhere.path, path = null, home = temporary.root, windows = windows, version = version))
        val missing = File(temporary.root, "nowhere/uv").path
        assertEquals(UvLookup.Unavailable("No uv at $missing."),
            UvLookup.find(explicit = missing, path = null, home = temporary.root, windows = windows, version = version))
    }

    @Test
    fun anOldOrSilentUvIsRefused() {
        val uv = File(temporary.newFolder("bin"), "uv").executable()

        assertEquals(UvLookup.Unavailable("uv 0.7.22 is too old; the math engine needs uv 0.8.0 or newer."),
            UvLookup.find(explicit = uv.path, path = null, home = temporary.root, windows = windows, version = { "uv 0.7.22" }))
        assertEquals(UvLookup.Unavailable("uv at ${uv.path} did not report its version."),
            UvLookup.find(explicit = uv.path, path = null, home = temporary.root, windows = windows, version = { null }))
    }

    @Test
    fun versionsReadAsUvPrintsThem() {
        assertEquals(UvVersion(0, 12, 19), UvVersion.parse("uv 0.12.19 (x86_64-unknown-linux-gnu)"))
        assertEquals(UvVersion(1, 0, 0), UvVersion.parse("uv 1.0.0"))
        assertNull(UvVersion.parse("uvx 0.12.19"))
        assertNull(UvVersion.parse("Python 3.13.15"))
        assertTrue(UvVersion(0, 10, 0) > UvVersion(0, 8, 9))
    }

    /**
     * Remove takes away everything the install put there — interpreter, environment, record — and
     * the runtime then offers the install again.
     */
    @Test
    fun removeDeletesTheWholeInstall() = runBlocking {
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        recordInstall(root)
        val uv = UvLookup.Found(File("uv"), UvVersion(0, 12, 19))
        runtime(root, uv = { uv }).use { runtime ->
            assertEquals(MathRuntimeState.Ready("3.13.15", "1.14.0"), runtime.settled())

            runtime.remove()

            assertEquals(MathRuntimeState.NotInstalled, withTimeout(30_000) {
                runtime.state.first { it == MathRuntimeState.NotInstalled }
            })
            assertFalse(root.exists())
        }
    }

    /** An app update that moves a pin is a different environment: the old one is not offered as ready. */
    @Test
    fun anInstallOfAnotherLockIsNotReady() = runBlocking {
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        recordInstall(root)
        val moved = MathEngineProject { name ->
            MathEngineProject::class.java.getResourceAsStream("/math-engine/$name")?.use { it.readBytes() }
                ?.let { bytes -> if (name == "uv.lock") bytes + "\n# moved".encodeToByteArray() else bytes }
        }
        val uv = UvLookup.Found(File("uv"), UvVersion(0, 12, 19))

        runtime(root, uv = { uv }, project = moved).use { assertEquals(MathRuntimeState.NotInstalled, it.settled()) }
    }

    /**
     * uv runs in the runtime's own directories — interpreter, environment and cache all under its
     * root — pinned to the exact interpreter, from the frozen lock, with no configuration of the
     * user's, and a failure is shown with uv's own last line rather than as a ready engine.
     */
    @Test
    fun uvRunsInTheRuntimesOwnDirectoriesAndItsFailureIsShown() = runBlocking {
        assumeFalse("the stand-in uv is a shell script", windows)
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        val calls = File(temporary.root, "calls.txt")
        val uv = File(temporary.newFolder("bin"), "uv").executable(
            """
            #!/bin/sh
            echo "${'$'}* | ${'$'}UV_PYTHON_INSTALL_DIR | ${'$'}UV_PROJECT_ENVIRONMENT | ${'$'}UV_CACHE_DIR | ${'$'}(pwd)" >> "${calls.path}"
            case "${'$'}1" in
              sync) echo "error: Failed to download sympy" >&2; exit 2 ;;
            esac
            echo "Installed Python 3.13.15"
            """.trimIndent(),
        )
        val lookup = UvLookup.Found(uv, UvVersion(0, 12, 19))
        runtime(root, uv = { lookup }).use { runtime ->
            runtime.settled()
            runtime.install()

            val failed = withTimeout(30_000) { runtime.state.first { it is MathRuntimeState.Failed } }

            assertEquals(MathRuntimeState.Failed("error: Failed to download sympy"), failed)
        }
        val lines = calls.readLines()
        val directories = " | ${File(root, "python").absolutePath} | ${File(root, "venv").absolutePath} | " +
            "${File(root, "cache").absolutePath} | ${File(root, "project").canonicalPath}"
        assertEquals(
            listOf(
                "python install 3.13.15 --managed-python --no-bin --no-registry --no-config --color never --no-progress$directories",
                "sync --frozen --managed-python --no-python-downloads --compile-bytecode --link-mode copy --no-config --color never --no-progress$directories",
            ),
            lines,
        )
        assertFalse("a failed install left a record of success", File(root, "installed.properties").exists())
        MathEngineProject.FILES.forEach { name -> assertTrue(name, File(root, "project/$name").isFile) }
    }

    /** Remove during an install stops uv rather than waiting for a download to finish. */
    @Test
    fun removeStopsAnInstallUnderWay() = runBlocking {
        assumeFalse("the stand-in uv is a shell script", windows)
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        val uv = File(temporary.newFolder("bin"), "uv").executable("#!/bin/sh\nexec sleep 600\n")
        val lookup = UvLookup.Found(uv, UvVersion(0, 12, 19))
        runtime(root, uv = { lookup }).use { runtime ->
            runtime.settled()
            runtime.install()
            withTimeout(10_000) {
                runtime.state.first { it == MathRuntimeState.Installing(MathInstallStep.DownloadingPython) }
            }

            runtime.remove()

            // The cancelled install reports NotInstalled itself, before the removal has run, so
            // wait for the directory: a uv still running would hold the install open far longer.
            withTimeout(10_000) { while (root.exists()) delay(20) }
            assertEquals(MathRuntimeState.NotInstalled, runtime.settled())
        }
    }

    /** The real install, by the fixture: the record names this app's environment and its versions. */
    @Test
    fun aRealInstallRecordsItsEnvironmentAndAnswers() = runBlocking {
        val runtime = MathRuntimeFixture.runtime()

        assertEquals(MathRuntimeState.Ready("3.13.15", "1.14.0"), runtime.state.value)
        val record = Properties().apply {
            File(runtime.serverDirectory.parentFile, "installed.properties").inputStream().use(::load)
        }
        assertEquals(MathEngineProject().environmentDigest, record.getProperty("environment"))
        assertFalse("the install kept its download cache", File(runtime.serverDirectory.parentFile, "cache").exists())
        assertEquals("Expression", runtime.engine.analyze("x + 1").summary)
    }

    /** The record a finished install leaves, beside a stand-in interpreter. */
    private fun recordInstall(root: File) {
        File(root, if (windows) "venv/Scripts/python.exe" else "venv/bin/python").apply {
            parentFile.mkdirs()
            writeText("")
        }
        File(root, "installed.properties").outputStream().use { output ->
            Properties().apply {
                setProperty("environment", MathEngineProject().environmentDigest)
                setProperty("python", "3.13.15")
                setProperty("sympy", "1.14.0")
            }.store(output, null)
        }
    }

    private fun File.executable(script: String = "#!/bin/sh\n"): File {
        parentFile.mkdirs()
        writeText(script + "\n")
        setExecutable(true)
        return this
    }
}
