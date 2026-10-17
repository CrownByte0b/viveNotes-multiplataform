package com.vivenotes.math

import com.vivenotes.localization.Strings
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The Android app's `SympyMathEngine`, with a process where Chaquopy was: `math_server.py` running
 * in the uv-managed CPython, spoken to one JSON line at a time. The JSON `math_engine` returns is
 * decoded here exactly as Android decodes what `callAttr` returns.
 *
 * One process serves every call, one call at a time, as Android's single interpreter does. It starts
 * on the first call and stays: SymPy's import and the ANTLR grammar's first parse are the slow part,
 * not the calls. A call that is cancelled kills the process, because a SymPy computation cannot be
 * interrupted from outside any other way, and the next call starts a fresh one.
 */
class SympyMathEngine internal constructor(
    /** Starts `math_server.py`. Throws [MathEngineUnavailableException] when it cannot. */
    private val launch: () -> Process,
    private val log: DebugLog = DebugLog(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MathEngine, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val calls = Mutex()

    @Volatile
    private var server: Server? = null

    @Volatile
    private var closed = false
    private var nextId = 1L

    override suspend fun analyze(latex: String): MathAnalysis =
        json.decodeFromString<MathAnalysis>(call("analyze", latex) ?: unexpectedNull("analyze"))

    override suspend fun execute(latex: String, actionId: String): MathOperationResult =
        json.decodeFromString<MathOperationResult>(call("execute", latex, actionId) ?: unexpectedNull("execute"))

    /** One call, so the formula is parsed and classified once rather than once per step. */
    override suspend fun calculate(latex: String, preferred: List<String>): MathOperationResult? =
        json.decodeFromString<MathOperationResult?>(
            call("calculate", latex, *preferred.toTypedArray()) ?: unexpectedNull("calculate"),
        )

    /** Starts the process, imports SymPy and parses one throwaway formula — `warm_up` in the module. */
    override suspend fun warmUp() {
        try {
            call("warm_up")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.failure("math", "warm-up", failure)
        }
    }

    /** The running interpreter's version and SymPy's, which is also proof the engine answers. */
    internal suspend fun versions(): Pair<String, String> {
        val versions = Json.parseToJsonElement(call("versions") ?: unexpectedNull("versions")).jsonObject
        fun field(name: String) = versions[name]?.jsonPrimitive?.contentOrNull
            ?: throw MathEngineUnavailableException(Strings.the_math_engine_did_not_report_its_value_version(name))
        return field("python") to field("sympy")
    }

    /**
     * Ends the process now, mid-call or not; a call it interrupts fails, and the next call starts
     * a fresh process. For an install or removal that is about to change the interpreter under it.
     */
    fun stop() {
        server?.let { running ->
            server = null
            running.end()
            log.event("math") { "engine stopped" }
        }
    }

    /** Ends the process for good; every later call fails. */
    override fun close() {
        closed = true
        stop()
    }

    private suspend fun call(name: String, vararg args: String): String? = calls.withLock {
        if (closed) throw MathEngineUnavailableException(Strings.the_math_engine_has_been_shut_down)
        val running = server?.takeIf { it.process.isAlive } ?: start()
        val id = nextId++
        val request = buildJsonObject {
            put("id", id)
            put("call", name)
            putJsonArray("args") { args.forEach { add(it) } }
        }.toString()
        val line = try {
            exchange(running, request)
        } catch (cancelled: CancellationException) {
            // The only way to stop SymPy mid-computation, and a reply could still arrive for it.
            end(running, "call cancelled")
            throw cancelled
        } catch (failure: IOException) {
            end(running, "engine exited (code ${running.exitCode()})")
            throw MathEngineUnavailableException(
                Strings.the_math_engine_stopped_unexpectedly + running.lastError()?.let { " $it" }.orEmpty(),
                failure,
            )
        }
        val reply = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (failure: Exception) {
            end(running, "unreadable reply")
            throw MathEngineUnavailableException(Strings.the_math_engine_sent_an_unreadable_reply, failure)
        }
        if (reply["id"]?.jsonPrimitive?.longOrNull != id) {
            end(running, "reply out of order")
            throw MathEngineUnavailableException(Strings.the_math_engine_answered_out_of_order)
        }
        reply.stringOrNull("error")?.let { error ->
            throw MathEngineUnavailableException(Strings.the_math_engine_could_not_run_value_value(name, error))
        }
        reply.stringOrNull("result")
    }

    /**
     * Writes [request] and reads its reply on [dispatcher]; cancelling kills the process, which
     * unblocks the read.
     *
     * The read hands back its outcome rather than throwing it: once the kill has unblocked it with
     * a closed pipe, that failure would otherwise outrank the cancellation that caused it, and a
     * caller's timeout would arrive as "the engine stopped".
     */
    private suspend fun exchange(running: Server, request: String): String = coroutineScope {
        val reply = async(dispatcher) {
            runCatching {
                running.requests.write(request)
                running.requests.newLine()
                running.requests.flush()
                running.replies.readLine() ?: throw IOException(Strings.the_math_engine_closed_its_output)
            }
        }
        try {
            reply.await()
        } catch (cancelled: CancellationException) {
            running.process.destroyForcibly()
            throw cancelled
        }.getOrThrow()
    }

    private fun start(): Server {
        val process = try {
            launch()
        } catch (unavailable: MathEngineUnavailableException) {
            throw unavailable
        } catch (failure: Exception) {
            throw MathEngineUnavailableException(Strings.the_math_engine_could_not_start, failure)
        }
        val started = Server(process)
        server = started
        log.event("math") { "engine started" }
        return started
    }

    private fun end(running: Server, reason: String) {
        if (server === running) server = null
        running.end()
        log.event("math") { "engine stopped: $reason" }
    }

    private fun unexpectedNull(call: String): Nothing =
        throw MathEngineUnavailableException(Strings.the_math_engine_returned_nothing_for_value(call))

    private fun JsonObject.stringOrNull(name: String): String? =
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    /** One running `math_server.py`, its pipes, and the end of what it wrote to stderr. */
    private class Server(val process: Process) {
        val requests: BufferedWriter = process.outputWriter(Charsets.UTF_8)
        val replies: BufferedReader = process.inputReader(Charsets.UTF_8)
        private val errors = ArrayDeque<String>()

        init {
            // Drained so a chatty library cannot fill the pipe and stall the process. Only the last
            // line is kept, for an error message; none of it goes to the debug log, because a
            // traceback can quote the formula, and formulas are the user's notes.
            thread(isDaemon = true, name = "math-engine-stderr") {
                runCatching {
                    process.errorReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(errors) {
                                if (errors.size == KEPT_ERROR_LINES) errors.removeFirst()
                                errors.addLast(line)
                            }
                        }
                    }
                }
            }
        }

        fun lastError(): String? = synchronized(errors) { errors.lastOrNull { it.isNotBlank() } }?.trim()?.take(160)

        fun exitCode(): String = if (process.isAlive) "running" else process.exitValue().toString()

        /** Closing stdin ends the server's read loop; the kill makes sure of it mid-computation. */
        fun end() {
            runCatching { requests.close() }
            process.destroyForcibly()
        }
    }

    private companion object {
        const val KEPT_ERROR_LINES = 20
    }
}
