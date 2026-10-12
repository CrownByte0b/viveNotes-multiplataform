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
 */
internal object MathRuntimeFixture {
    private val root = File(System.getProperty("vivenotes.test.mathRuntime") ?: "build/math-engine-test")

    private val installed: Result<UvMathRuntime?> by lazy {
        runCatching {
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
    fun runtime(): UvMathRuntime {
        val runtime = installed.getOrThrow()
        assumeTrue("uv is not installed on this machine, so the math engine cannot be", runtime != null)
        return runtime!!
    }

    /** The first install downloads a 33 MB interpreter. */
    private const val INSTALL_TIMEOUT_MS = 15 * 60_000L
}
