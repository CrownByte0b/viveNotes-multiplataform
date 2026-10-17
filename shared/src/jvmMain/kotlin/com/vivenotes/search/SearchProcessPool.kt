package com.vivenotes.search

import com.vivenotes.localization.Strings
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import kotlin.concurrent.thread

/** Bounded, persistent JVM processes shared by projection, matching and whole-image OCR. */
class SearchProcessPool internal constructor(
    val parallelism: Int,
    private val log: DebugLog,
    private val timeoutMillis: Long = 120_000,
    private val launch: () -> Process,
) : AutoCloseable {
    constructor(parallelism: Int = defaultParallelism(), log: DebugLog = DebugLog()) : this(
        parallelism, log, 120_000, { startWorker() },
    )

    init { require(parallelism in 2..8) }
    private val slots = List(parallelism) { Slot() }
    private val available = Channel<Slot>(parallelism).apply { slots.forEach { trySend(it) } }
    @Volatile private var closed = false
    private val shutdown = Thread({ slots.forEach { it.stop() } }, "search-workers-shutdown")
    init { Runtime.getRuntime().addShutdownHook(shutdown) }

    internal suspend fun execute(request: WorkerRequest): WorkerReply {
        check(!closed) { Strings.search_has_been_closed }
        val slot = available.receive()
        try {
            return withTimeout(timeoutMillis) {
                coroutineScope {
                    val reply = async(Dispatchers.IO) {
                        runCatching {
                            val worker = slot.start()
                            worker.input.write(Json.encodeToString(request))
                            worker.input.newLine()
                            worker.input.flush()
                            val line = worker.output.readLine() ?: throw IOException(Strings.search_worker_exited)
                            Json.decodeFromString<WorkerReply>(line)
                        }
                    }
                    try {
                        reply.await()
                    } catch (cancelled: CancellationException) {
                        slot.stop()
                        throw cancelled
                    }.getOrThrow()
                }
            }
        } catch (failure: Exception) {
            slot.stop()
            if (failure is TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                log.event("search") { "worker timed out" }
                throw IOException(Strings.search_worker_timed_out, failure)
            }
            // The exception or a worker's stderr can contain document contents: never log either.
            if (failure !is CancellationException) log.event("search") { "worker failed" }
            throw failure
        } finally {
            if (!closed) available.trySend(slot)
        }
    }

    override fun close() {
        closed = true
        available.close()
        slots.forEach { it.stop() }
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        log.event("search") { "workers closed" }
    }

    private inner class Slot {
        private var worker: Worker? = null
        @Synchronized fun start(): Worker {
            check(!closed) { Strings.search_has_been_closed }
            return worker?.takeIf { it.process.isAlive } ?: Worker(launch()).also {
                worker = it
                log.event("search") { "worker started" }
            }
        }
        @Synchronized fun stop() {
            worker?.process?.destroyForcibly()
            worker = null
        }
    }

    private class Worker(val process: Process) {
        val input = process.outputWriter(Charsets.UTF_8)
        val output = process.inputReader(Charsets.UTF_8)
        init {
            thread(isDaemon = true, name = "search-worker-stderr") {
                runCatching { process.errorReader(Charsets.UTF_8).useLines { it.forEach { _ -> } } }
            }
        }
    }

    companion object {
        /** At least two processes, with a cap on native activation/model memory. */
        fun defaultParallelism(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
            (cores / 2).coerceIn(2, 4)

        internal fun workerClasspath(): String {
            val inherited = System.getProperty("java.class.path").split(File.pathSeparator)
            // Gradle tests load the application through a child URLClassLoader; installed apps use
            // the regular application classpath. Include both without relying on Gradle paths.
            val loaded = generateSequence(SearchWorker::class.java.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
                .filter { it.protocol == "file" }.map { File(it.toURI()).absolutePath }.toList()
            return (inherited + loaded).distinct().joinToString(File.pathSeparator)
        }

        private fun startWorker(): Process {
            val windows = System.getProperty("os.name").startsWith("Windows", true)
            val java = File(System.getProperty("java.home"), "bin/${if (windows) "java.exe" else "java"}")
            return ProcessBuilder(java.absolutePath, "-Xmx384m", "--enable-native-access=ALL-UNNAMED",
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", workerClasspath(),
                SearchWorker::class.java.name).start()
        }
    }
}
