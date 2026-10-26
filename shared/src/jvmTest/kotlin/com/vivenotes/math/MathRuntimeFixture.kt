package com.vivenotes.math

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * One real math engine for the whole test run: uv installs CPython and SymPy into the directory
 * Gradle names (`vivenotes.test.mathRuntime`, under the root build directory) the first time, and
 * every later run finds them there. Tests that need it skip where uv is not installed, saying so.
 *
 * `vivenotes.test.mathPython` (Gradle passes `VIVE_TEST_MATH_PYTHON`) names a packaged interpreter
 * with the locked packages instead, such as the Flatpak runtime's: the same tests then check the
 * engine on the Python a package ships, through [BundledMathRuntime].
 */
internal object MathRuntimeFixture {
    private val root = File(System.getProperty("vivenotes.test.mathRuntime") ?: "build/math-engine-test")
    private val packagedPython = System.getProperty("vivenotes.test.mathPython")?.takeIf(String::isNotBlank)

    /** Whether the engine is a packaged interpreter rather than uv's install. */
    val packaged: Boolean get() = packagedPython != null

    private val installed: Result<MathEngineRuntime?> by lazy {
        runCatching {
            if (packagedPython != null) {
                val runtime = BundledMathRuntime(File(packagedPython), File(root, "packaged-project"))
                val checked = runBlocking { withTimeout(60_000) { runtime.state.first { it !is MathRuntimeState.Checking } } }
                check(checked is MathRuntimeState.Ready) { "the packaged Python at $packagedPython cannot run the math engine: $checked" }
                return@runCatching runtime
            }
            if (UvLookup.find() is UvLookup.Unavailable) return@runCatching null
            val runtime = UvMathRuntime(root, DebugLog())
            runBlocking {
                val checked = withTimeout(60_000) { runtime.state.first { it !is MathRuntimeState.Checking } }
                if (checked !is MathRuntimeState.Ready) {
                    runtime.install()
                    val done = withTimeout(INSTALL_TIMEOUT_MS) {
                        runtime.state.first {
                            it is MathRuntimeState.Ready || it is MathRuntimeState.Failed ||
                                it is MathRuntimeState.UvUnavailable
                        }
                    }
                    check(done is MathRuntimeState.Ready) { "uv could not install the math engine for the tests: $done" }
                }
            }
            runtime
        }
    }

    /** The installed runtime, or a skipped test where uv is missing. A failed install fails the test. */
    fun runtime(): MathEngineRuntime {
        val runtime = installed.getOrThrow()
        assumeTrue("uv is not installed on this machine, so the math engine cannot be", runtime != null)
        return runtime!!
    }

    /** The first install downloads a 33 MB interpreter. */
    private const val INSTALL_TIMEOUT_MS = 15 * 60_000L
}
