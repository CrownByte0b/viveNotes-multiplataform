package com.vivenotes.math

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The desktop's half of the bridge: one process per engine, started on the first call, killed when
 * a call is cancelled or the engine closes, and started afresh after either. And the server's half:
 * nothing reaches `math_engine` but the calls on its list, with string arguments.
 */
class SympyMathEngineProcessTest {
    private val runtime by lazy { MathRuntimeFixture.runtime() }

    /** A Python that reads one request and then never answers: a computation that does not end. */
    private fun hangingServer(): Process = ProcessBuilder(
        runtime.pythonExecutable.path, "-E", "-s", "-B", "-c",
        "import sys, time; sys.stdin.readline(); time.sleep(600)",
    ).start()

    private fun realServer(): Process =
        ProcessBuilder(runtime.pythonExecutable.path, "-E", "-s", "-B", "-X", "utf8", "-u", MathEngineProject.SERVER)
            .directory(runtime.serverDirectory)
            .start()

    @Test
    fun aCancelledCallKillsItsProcessAndTheNextCallStartsAnother() = runBlocking {
        val launched = mutableListOf<Process>()
        val engine = SympyMathEngine(launch = {
            (if (launched.isEmpty()) hangingServer() else realServer()).also(launched::add)
        })
        engine.use {
            try {
                withTimeout(2_000) { engine.analyze("x^2") }
                fail("a call to a server that never answers returned")
            } catch (_: TimeoutCancellationException) {
            }
            assertTrue("the cancelled call's process is still running",
                launched.first().waitFor(10, TimeUnit.SECONDS))

            val analysis = engine.analyze("x^2")

            assertNull(analysis.error)
            assertEquals(2, launched.size)
            assertTrue(launched.last().isAlive)
        }
        assertTrue("closing the engine left its process running", launched.last().waitFor(10, TimeUnit.SECONDS))
    }

    @Test
    fun aServerThatDiesFailsTheCallAndTheNextCallStartsAnother() = runBlocking {
        val launched = mutableListOf<Process>()
        val engine = SympyMathEngine(launch = {
            val process = if (launched.isEmpty()) {
                ProcessBuilder(runtime.pythonExecutable.path, "-E", "-s", "-B", "-c",
                    "import sys; sys.stdin.readline(); sys.exit(3)").start()
            } else {
                realServer()
            }
            process.also(launched::add)
        })
        engine.use {
            val failure = runCatching { engine.execute("x^2", "expand") }.exceptionOrNull()
            assertTrue("$failure", failure is MathEngineUnavailableException)
            assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().startsWith("The math engine stopped unexpectedly."))

            assertEquals("x^{2}", engine.execute("x^2", "expand").latex)
            assertEquals(2, launched.size)
        }
    }

    @Test
    fun anEngineThatCannotStartIsUnavailable() = runBlocking {
        val engine = SympyMathEngine(launch = { throw IOException("no such interpreter") })

        val failure = runCatching { engine.analyze("x") }.exceptionOrNull()

        assertTrue("$failure", failure is MathEngineUnavailableException)
        assertEquals("The math engine could not start.", failure!!.message)
    }

    @Test
    fun aClosedEngineRefusesEveryCall() = runBlocking {
        val engine = runtime.newEngine(DebugLog())
        assertNull(engine.analyze("x").error)
        engine.close()

        val failure = runCatching { engine.analyze("x") }.exceptionOrNull()

        assertTrue("$failure", failure is MathEngineUnavailableException)
    }

    /** The server end of the pipe, spoken to directly: only its listed calls, only with strings. */
    @Test
    fun theServerAnswersOnlyItsOwnCalls() {
        val server = realServer()
        try {
            val requests = server.outputWriter(Charsets.UTF_8)
            val replies = server.inputReader(Charsets.UTF_8)
            /** Sends one line; returns the reply's `result` or `error` text. */
            fun exchange(line: String, field: String): String? {
                requests.write(line)
                requests.newLine()
                requests.flush()
                val reply = Json.parseToJsonElement(replies.readLine() ?: error("the server closed its output")).jsonObject
                return reply[field]?.jsonPrimitive?.contentOrNull
            }

            val versions = Json.parseToJsonElement(
                exchange("""{"id":1,"call":"versions","args":[]}""", "result")!!).jsonObject
            // uv installs the pinned interpreter; a packaged one reports its own, as the launch check read it.
            val python = if (MathRuntimeFixture.packaged) (runtime.state.value as MathRuntimeState.Ready).python else "3.13.15"
            assertEquals(python, versions["python"]?.jsonPrimitive?.content)
            assertEquals("1.14.0", versions["sympy"]?.jsonPrimitive?.content)
            assertEquals("Unknown call.", exchange("""{"id":2,"call":"__import__","args":["os"]}""", "error"))
            assertEquals("Unknown call.", exchange("""{"id":3,"call":"_execute","args":["x","simplify"]}""", "error"))
            assertEquals("Arguments must be strings.", exchange("""{"id":4,"call":"analyze","args":[["x"]]}""", "error"))
            assertEquals("Wrong number of arguments.", exchange("""{"id":5,"call":"execute","args":["x"]}""", "error"))
            assertEquals("The request is not JSON.", exchange("print('hi')", "error"))
            assertEquals("The request has no id.", exchange("""{"call":"analyze","args":["x"]}""", "error"))
            // Still serving after all of that.
            assertTrue(exchange("""{"id":6,"call":"analyze","args":["x+1"]}""", "result").orEmpty().contains("Expression"))

            // End of input ends the server, so an app that dies does not leave one behind.
            requests.close()
            assertTrue("the server outlived its input", server.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, server.exitValue())
        } finally {
            server.destroyForcibly()
        }
        assertFalse(server.isAlive)
    }
}
