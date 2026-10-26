package com.vivenotes.math

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The runtime a package that ships its own Python uses (the Flatpak): it never downloads, installs
 * or removes anything, and answers with the interpreter it was given. The real interpreter here is
 * [MathRuntimeFixture]'s uv install standing in for the package's.
 */
class BundledMathRuntimeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun runtime(python: File, root: File = temporary.newFolder(UvMathRuntime.DIRECTORY)) =
        BundledMathRuntime(python, File(root, "project"), DebugLog(), MathEngineProject(), Dispatchers.IO)

    private suspend fun BundledMathRuntime.settled(): MathRuntimeState = withTimeout(60_000) {
        state.first { it !is MathRuntimeState.Checking }
    }

    @Test
    fun aMissingInterpreterFailsAndNothingIsInstalled() = runBlocking {
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        runtime(File(temporary.root, "no-python-here/python3"), root).use { runtime ->
            assertTrue(runtime.settled() is MathRuntimeState.Failed)

            runtime.install()
            assertTrue(runtime.settled() is MathRuntimeState.Failed)
            assertEquals(listOf("project"), root.list()!!.toList())
            assertFalse(File(root, "removed").exists())
        }
    }

    @Test
    fun thePackagedInterpreterAnswersWithItsOwnVersions() = runBlocking {
        val packaged = MathRuntimeFixture.runtime()
        runtime(packaged.pythonExecutable).use { runtime ->
            assertEquals(packaged.state.value, runtime.settled())
            assertEquals("Expression", runtime.engine.analyze("x + 1").summary)
        }
    }

    @Test
    fun removeLeavesThePackagedEngineAnswering() = runBlocking {
        val root = temporary.newFolder(UvMathRuntime.DIRECTORY)
        runtime(MathRuntimeFixture.runtime().pythonExecutable, root).use { runtime ->
            val ready = runtime.settled()
            assertTrue(ready is MathRuntimeState.Ready)

            runtime.remove()
            delay(200)
            assertEquals(ready, runtime.state.value)
            assertFalse(File(root, "removed").exists())
            assertEquals("Expression", runtime.engine.analyze("x + 1").summary)
        }
    }

    @Test
    fun theLauncherPropertyChoosesThePackagedInterpreterOverUv() {
        val packagedRoot = temporary.newFolder("packaged")
        BundledMathRuntime.forThisApp(packagedRoot, DebugLog(), packagedPython = "/app/bin/python3").use {
            assertTrue(it is BundledMathRuntime)
        }

        // A remembered Remove keeps uv from installing anything while the choice is checked.
        val uvRoot = temporary.newFolder("uv").apply { File(this, "removed").writeText("") }
        BundledMathRuntime.forThisApp(uvRoot, DebugLog(), packagedPython = null).use {
            assertTrue(it is UvMathRuntime)
        }
    }
}
