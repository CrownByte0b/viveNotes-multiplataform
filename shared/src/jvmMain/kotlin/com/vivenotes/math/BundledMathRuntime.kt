package com.vivenotes.math

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** A math runtime the desktop app can run formulas on and must close. */
interface MathEngineRuntime : MathRuntime, AutoCloseable {
    /** Answers once [state] is [MathRuntimeState.Ready]. */
    val engine: MathEngine

    /** The interpreter and the directory `math_server.py` runs in. */
    val pythonExecutable: File
    val serverDirectory: File

    /** Another engine over this runtime, with a process of its own: for tests that watch one. */
    fun newEngine(log: DebugLog): SympyMathEngine
}

/**
 * The math engine's runtime when the package ships Python with SymPy already installed, as the
 * Flatpak does: [python] has every package `uv.lock` names, so nothing is downloaded, installed
 * or removed. A launch only checks that the interpreter answers. The project's Python files are
 * written to [serverDirectory] and run there, as [UvMathRuntime] runs them.
 */
class BundledMathRuntime internal constructor(
    private val python: File,
    override val serverDirectory: File,
    private val log: DebugLog,
    private val project: MathEngineProject,
    ioDispatcher: CoroutineDispatcher,
) : MathEngineRuntime {
    constructor(python: File, serverDirectory: File, log: DebugLog = DebugLog()) :
        this(python, serverDirectory, log, MathEngineProject(), Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var checking: Job? = null

    private val _state = MutableStateFlow<MathRuntimeState>(MathRuntimeState.Checking)
    override val state: StateFlow<MathRuntimeState> = _state.asStateFlow()

    private val sympy = SympyMathEngine(launch = { launchServer(requireReady = true) }, log)
    override val engine: MathEngine get() = sympy
    override val pythonExecutable: File get() = python

    override fun newEngine(log: DebugLog): SympyMathEngine =
        SympyMathEngine(launch = { launchServer(requireReady = true) }, log)

    init {
        checkInterpreter()
    }

    /** There is nothing to install: checks the interpreter again, as after a failed launch check. */
    override fun install() = checkInterpreter()

    /** The package owns the interpreter, so there is nothing here to remove. */
    override fun remove() {
        log.event("math") { "remove ignored: the math engine is part of this package" }
    }

    override fun close() {
        scope.cancel()
        sympy.close()
    }

    private fun checkInterpreter(): Unit = synchronized(this) {
        if (checking?.isActive == true) return
        _state.value = MathRuntimeState.Checking
        checking = scope.launch {
            _state.value = try {
                sympy.stop()
                val (pythonVersion, sympyVersion) =
                    SympyMathEngine(launch = { launchServer(requireReady = false) }, log).use { it.versions() }
                log.event("math") { "bundled runtime ready: Python $pythonVersion, SymPy $sympyVersion" }
                MathRuntimeState.Ready(pythonVersion, sympyVersion)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.failure("math", "checking the bundled runtime", failure)
                MathRuntimeState.Failed(failure.message?.takeIf(String::isNotBlank) ?: Strings.the_math_engine_could_not_start)
            }
        }
    }

    private fun launchServer(requireReady: Boolean): Process {
        if (requireReady && _state.value !is MathRuntimeState.Ready) {
            throw MathEngineUnavailableException(Strings.the_math_engine_could_not_start)
        }
        // Every start, so the sources the process runs are always this app's.
        project.extract(serverDirectory)
        return ProcessBuilder(python.path, "-E", "-s", "-B", "-X", "utf8", "-u", MathEngineProject.SERVER)
            .directory(serverDirectory)
            .start()
    }

    companion object {
        /** The launcher's name for the packaged interpreter; [UvMathRuntime] runs without it. */
        const val PYTHON_PROPERTY = "vivenotes.mathPython"

        /**
         * The packaged interpreter when the launcher names one ([PYTHON_PROPERTY]), else uv's
         * install in [root] — `math_engine` in the data directory either way.
         */
        fun forThisApp(
            root: File,
            log: DebugLog,
            packagedPython: String? = System.getProperty(PYTHON_PROPERTY)?.takeIf(String::isNotBlank),
        ): MathEngineRuntime = if (packagedPython != null) {
            BundledMathRuntime(File(packagedPython), File(root, "project"), log)
        } else {
            UvMathRuntime(root, log)
        }
    }
}
