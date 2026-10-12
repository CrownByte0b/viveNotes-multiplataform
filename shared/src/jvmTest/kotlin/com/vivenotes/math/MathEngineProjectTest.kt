package com.vivenotes.math

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The uv project the app carries: everything in its source directory, and nothing else. */
class MathEngineProjectTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** Gradle runs tests in the `shared` project directory. */
    private val sources = File("src/jvmMain/python")

    @Test
    fun theAppCarriesExactlyTheProjectsFiles() {
        val inSources = sources.listFiles()!!
            .filter { it.isFile && (it.name.endsWith(".py") || it.name in setOf("pyproject.toml", "uv.lock", ".python-version")) }
            .map { it.name }
        assertEquals(inSources.sorted(), MathEngineProject.FILES.sorted())
        MathEngineProject.FILES.forEach { name ->
            val carried = javaClass.getResourceAsStream("/math-engine/$name")!!.use { it.readBytes() }
            assertArrayEquals(name, File(sources, name).readBytes(), carried)
        }
    }

    @Test
    fun theInterpreterIsPinnedExactly() {
        assertEquals("3.13.15", MathEngineProject().pythonVersion)
    }

    @Test
    fun extractingWritesEveryFileAndLeavesMatchingOnesAlone() {
        val directory = File(temporary.root, "project")
        MathEngineProject().extract(directory)
        MathEngineProject.FILES.forEach { name ->
            assertArrayEquals(name, File(sources, name).readBytes(), File(directory, name).readBytes())
        }
        val untouched = File(directory, "uv.lock").apply { setLastModified(1_000_000L) }
        File(directory, "math_engine.py").writeText("# edited")

        MathEngineProject().extract(directory)

        assertEquals(1_000_000L, untouched.lastModified())
        assertArrayEquals(File(sources, "math_engine.py").readBytes(), File(directory, "math_engine.py").readBytes())
        assertEquals(MathEngineProject.FILES.sorted(), directory.list()!!.sorted())
    }

    /** The environment is named by what uv installs from — the lock and pins — not by the scripts it runs. */
    @Test
    fun theEnvironmentDigestFollowsTheLockNotTheScripts() {
        fun project(change: String) = MathEngineProject { name ->
            javaClass.getResourceAsStream("/math-engine/$name")?.use { it.readBytes() }
                ?.let { if (name == change) it + "\n# changed".encodeToByteArray() else it }
        }
        val bundled = MathEngineProject().environmentDigest

        assertEquals(bundled, project("math_engine.py").environmentDigest)
        assertEquals(bundled, project("math_server.py").environmentDigest)
        assertNotEquals(bundled, project("uv.lock").environmentDigest)
        assertNotEquals(bundled, project("pyproject.toml").environmentDigest)
        assertNotEquals(bundled, project(".python-version").environmentDigest)
    }
}
