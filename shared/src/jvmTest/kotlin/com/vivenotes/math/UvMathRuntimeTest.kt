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

    /**
     * A uv that is found but cannot run: an absolute path to nothing, so an install that should not
     * happen fails loudly instead of reaching a real uv on `PATH`.
     */
    private val unrunnableUv by lazy { UvLookup.Found(File(temporary.root, "no-uv-here/uv"), UvVersion(0, 12, 19)) }

    /** Off unless a test is about it, so the others see the states a launch starts in. */
    private fun runtime(
        root: File = temporary.newFolder(UvMathRuntime.DIRECTORY),
        uv: () -> UvLookup = { noUv },
        project: MathEngineProject = MathEngineProject(),
        log: DebugLog = DebugLog(),
        autoInstall: Boolean = false,
    ) = UvMathRuntime(root, log, project, uv, Dispatchers.IO, windows, autoInstall)

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
        runtime(uv = { unrunnableUv }).use { assertEquals(MathRuntimeState.NotInstalled, it.settled()) }
    }

    /**
     * SymPy is installed by default, as Android always has it: a launch that finds no engine runs
     * the install itself, without anyone pressing Install.
     */
    @Test
    fun aLaunchInstallsTheEngineByDefault() = runBlocking {
        assumeFalse("the stand-in uv is a shell script", windows)
        val calls = File(temporary.root, "calls.txt")
        val uv = File(temporary.newFolder("bin"), "uv").executable(
            """
            #!/bin/sh
            echo "${'$'}1" >> "${calls.path}"
            case "${'$'}1" in
              sync) echo "error: offline" >&2; exit 2 ;;
            esac
            """.trimIndent(),
        )
        val lines = mutableListOf<String>()
        val lookup = UvLookup.Found(uv, UvVersion(0, 12, 19))
        runtime(uv = { lookup }, autoInstall = true, log = DebugLog(enabled = true) { lines += it }).use { runtime ->
            // An offline first launch fails where it is shown, and the next launch tries again.
            assertEquals(MathRuntimeState.Failed("error: offline"), runtime.settled())
        }
        assertEquals(listOf("python", "sync"), calls.readLines())
        assertTrue(lines.toString(), lines.any { it.contains("installing by default") })
    }

    /** An installed engine is left alone, and so is a launch with no uv to install with. */
    @Test
    fun aLaunchInstallsOnlyWhatIsMissingAndOnlyWithUv() = runBlocking {
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        recordInstall(root)
        runtime(root, uv = { unrunnableUv }, autoInstall = true).use { runtime ->
            assertEquals(MathRuntimeState.Ready("3.13.15", "1.14.0"), runtime.settled())
        }
        runtime(temporary.newFolder("without-uv"), uv = { noUv }, autoInstall = true).use { runtime ->
            assertEquals(MathRuntimeState.UvUnavailable(noUv.reason), runtime.settled())
        }
    }

    /**
     * Remove is a no to the default, and it is kept: a later launch leaves the engine uninstalled
     * and offers Install, rather than downloading 180 MB the person just deleted.
     */
    @Test
    fun aRemovedEngineIsNotInstalledAgainAtLaunch() = runBlocking {
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        recordInstall(root)
        runtime(root, uv = { unrunnableUv }, autoInstall = true).use { runtime ->
            assertEquals(MathRuntimeState.Ready("3.13.15", "1.14.0"), runtime.settled())
            runtime.remove()
            withTimeout(10_000) { while (!File(root, "removed").isFile) delay(20) }
        }

        // Were it to install by default, the uv that cannot run would leave it Failed.
        runtime(root, uv = { unrunnableUv }, autoInstall = true).use { runtime ->
            assertEquals(MathRuntimeState.NotInstalled, runtime.settled())
            delay(300)
            assertEquals(MathRuntimeState.NotInstalled, runtime.state.value)
        }
    }

    /** Pressing Install takes the Remove back, so the default applies again from then on. */
    @Test
    fun installTakesBackAnEarlierRemove() = runBlocking {
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        File(root, "removed").writeText("")
        runtime(root, uv = { unrunnableUv }, autoInstall = true).use { runtime ->
            assertEquals(MathRuntimeState.NotInstalled, runtime.settled())
            runtime.install()
            assertTrue(withTimeout(10_000) { runtime.state.first { it is MathRuntimeState.Failed } } is MathRuntimeState.Failed)
        }
        assertFalse(File(root, "removed").exists())

        runtime(root, uv = { unrunnableUv }, autoInstall = true).use { runtime ->
            assertTrue("${runtime.state.value}", runtime.settled() is MathRuntimeState.Failed)
        }
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
    fun packagedUvWorksWithoutAUserInstallationAndExplicitOverridesStillWin() {
        val resources = temporary.newFolder("app resources")
        val bundled = File(resources, "bin/uv.exe").executable()
        val path = temporary.newFolder("user bin")
        File(path, "uv.exe").executable()
        val version: (File) -> String? = { "uv 0.12.19" }
        assertEquals(UvLookup.Found(bundled, UvVersion(0, 12, 19)),
            UvLookup.find(explicit = null, path = "", home = temporary.root,
                windows = true, resources = resources, version = version))
        assertEquals(UvLookup.Found(bundled, UvVersion(0, 12, 19)),
            UvLookup.find(explicit = null, path = path.path, home = temporary.root,
                windows = true, resources = resources, version = version))
        val override = File(temporary.root, "custom.exe").executable()
        assertEquals(UvLookup.Found(override, UvVersion(0, 12, 19)),
            UvLookup.find(explicit = override.path, path = path.path, home = temporary.root,
                windows = true, resources = resources, version = version))
        val missing = File(temporary.root, "missing.exe").path
        assertEquals(UvLookup.Unavailable("No uv at $missing."),
            UvLookup.find(explicit = missing, path = path.path, home = temporary.root,
                windows = true, resources = resources, version = version))
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
        runtime(root, uv = { unrunnableUv }).use { runtime ->
            assertEquals(MathRuntimeState.Ready("3.13.15", "1.14.0"), runtime.settled())

            runtime.remove()

            assertEquals(MathRuntimeState.NotInstalled, withTimeout(30_000) {
                runtime.state.first { it == MathRuntimeState.NotInstalled }
            })
            // Only the answer stays: see `aRemovedEngineIsNotInstalledAgainAtLaunch`.
            assertEquals(listOf("removed"), root.list()!!.toList())
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
        runtime(root, uv = { unrunnableUv }, project = moved).use { assertEquals(MathRuntimeState.NotInstalled, it.settled()) }
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
            // wait for its marker: a uv still running would hold the install open far longer.
            withTimeout(10_000) { while (!File(root, "removed").isFile) delay(20) }
            assertEquals(MathRuntimeState.NotInstalled, runtime.settled())
            assertEquals(listOf("removed"), root.list()!!.toList())
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
