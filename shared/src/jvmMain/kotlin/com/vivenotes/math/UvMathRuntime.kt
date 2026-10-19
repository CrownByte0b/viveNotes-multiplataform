package com.vivenotes.math

import com.vivenotes.localization.Strings
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * The math engine's runtime on the desktop: a real CPython with SymPy, which uv installs into
 * [root] — `math_engine` in the data directory — from the lock the app carries.
 *
 * Android bundles its interpreter in the APK through Chaquopy, so SymPy is simply there. Here uv
 * does that job, and does it **by default**: a launch that finds the engine missing installs it in
 * the background, unless the person removed it — then it stays removed until they press Install.
 * uv downloads the exact CPython `.python-version` names (its own python-build-standalone build,
 * never the system's, so every machine runs the same interpreter) and installs the locked packages
 * beside it. Nothing is installed into the user's own Python, uv's shared directories, or `PATH`:
 *
 * - `project/` — the bundled uv project, written out ([MathEngineProject])
 * - `python/` — the uv-managed CPython
 * - `venv/` — the project environment, with SymPy and its bytecode compiled at install
 * - `installed.properties` — what the last verified install was
 * - `removed` — left by Remove, and alone: the person's no to installing by default
 *
 * uv is needed to install, not to run: an installed engine keeps working if uv goes away.
 */
class UvMathRuntime internal constructor(
    private val root: File,
    private val log: DebugLog,
    private val project: MathEngineProject,
    /** Finds uv, or says why it cannot be used. */
    private val findUv: () -> UvLookup,
    ioDispatcher: CoroutineDispatcher,
    private val windows: Boolean,
    /** Whether a launch that finds the engine missing installs it unasked — see the class. */
    autoInstall: Boolean,
) : MathRuntime, AutoCloseable {
    constructor(root: File, log: DebugLog = DebugLog()) : this(
        root, log, MathEngineProject(), { UvLookup.find() }, Dispatchers.IO,
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        autoInstall = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Installs and removals, one at a time. */
    private val operations = Mutex()
    private var installing: Job? = null

    private val projectDirectory = File(root, "project")
    private val interpreters = File(root, "python")
    private val environment = File(root, "venv")
    private val cache = File(root, "cache")
    private val marker = File(root, "installed.properties")
    private val removedByUser = File(root, "removed")
    private val python = File(environment, if (windows) "Scripts/python.exe" else "bin/python")

    private val _state = MutableStateFlow<MathRuntimeState>(MathRuntimeState.Checking)
    override val state: StateFlow<MathRuntimeState> = _state.asStateFlow()

    private val sympy = SympyMathEngine(launch = { launchServer(requireReady = true) }, log)

    /** The math engine, answering once [state] is [MathRuntimeState.Ready]. */
    val engine: MathEngine get() = sympy

    /** Another engine over this runtime, with a process of its own: for tests that watch one. */
    internal fun newEngine(log: DebugLog = this.log): SympyMathEngine =
        SympyMathEngine(launch = { launchServer(requireReady = true) }, log)

    /** The installed interpreter and the directory `math_server.py` runs in. */
    internal val pythonExecutable: File get() = python
    internal val serverDirectory: File get() = projectDirectory

    init {
        // Counted as an install from the start, so an Install press meanwhile is not a second one
        // and Remove can stop it. Checking goes straight to Installing: the pane never offers an
        // Install button for the moment between them.
        synchronized(this) {
            installing = scope.launch {
                operations.withLock {
                    val checked = currentState()
                    if (autoInstall && checked == MathRuntimeState.NotInstalled && !removedByUser.exists()) {
                        log.event("math") { "runtime checked: not installed; installing by default" }
                        runInstall()
                    } else {
                        _state.value = checked
                        log.event("math") {
                            "runtime checked: ${checked.describe()}" + if (removedByUser.exists()) " (removed by the user)" else ""
                        }
                    }
                }
            }
        }
    }

    override fun install() {
        synchronized(this) {
            if (installing?.isActive == true) return
            installing = scope.launch { operations.withLock { runInstall() } }
        }
    }

    /**
     * Stops the engine, deletes what the install put here and leaves [removedByUser], so launches
     * stop installing it by default until [install] is pressed again.
     */
    override fun remove() {
        val pending = synchronized(this) { installing.also { it?.cancel() } }
        scope.launch {
            pending?.join()
            operations.withLock {
                sympy.stop()
                if (!root.deleteRecursively()) log.event("math") { "remove left files behind" }
                try {
                    root.mkdirs()
                    removedByUser.writeText("Removed from Settings → Models; Install brings the math engine back.\n")
                } catch (failure: Exception) {
                    log.failure("math", "remembering the removal", failure)
                }
                log.event("math") { "removed" }
                _state.value = currentState()
            }
        }
    }

    /** Stops the engine and any install under way. */
    override fun close() {
        scope.cancel()
        sympy.close()
    }

    private suspend fun runInstall() {
        // Asking for it again takes back the earlier Remove, whether or not this install succeeds.
        removedByUser.delete()
        val uv = when (val lookup = findUv()) {
            is UvLookup.Found -> lookup
            is UvLookup.Unavailable -> {
                log.event("math") { "install refused: ${lookup.reason}" }
                _state.value = MathRuntimeState.UvUnavailable(lookup.reason)
                return
            }
        }
        log.event("math") { "install started with uv ${uv.version}" }
        try {
            _state.value = MathRuntimeState.Installing(MathInstallStep.Preparing)
            sympy.stop()
            // Gone until the end, so an install that fails partway never reads as installed.
            marker.delete()
            project.extract(projectDirectory)

            _state.value = MathRuntimeState.Installing(MathInstallStep.DownloadingPython)
            uv(uv, "python", "install", project.pythonVersion, "--managed-python", "--no-bin", "--no-registry")

            _state.value = MathRuntimeState.Installing(MathInstallStep.InstallingPackages)
            uv(uv, "sync", "--frozen", "--managed-python", "--no-python-downloads", "--compile-bytecode",
                "--link-mode", "copy")

            _state.value = MathRuntimeState.Installing(MathInstallStep.Checking)
            val (pythonVersion, sympyVersion) =
                SympyMathEngine(launch = { launchServer(requireReady = false) }, log).use { it.versions() }
            writeMarker(pythonVersion, sympyVersion)
            cache.deleteRecursively()
            _state.value = MathRuntimeState.Ready(pythonVersion, sympyVersion)
            log.event("math") { "installed: Python $pythonVersion, SymPy $sympyVersion" }
        } catch (cancelled: CancellationException) {
            _state.value = currentState()
            log.event("math") { "install cancelled" }
            throw cancelled
        } catch (failure: Exception) {
            log.failure("math", "install", failure)
            _state.value = MathRuntimeState.Failed(
                failure.message?.takeIf(String::isNotBlank) ?: Strings.the_math_engine_could_not_be_installed,
            )
        }
    }

    /** Runs one uv command in the project with this runtime's directories and nobody else's settings. */
    private suspend fun uv(uv: UvLookup.Found, vararg arguments: String) {
        val command = listOf(uv.file.path) + arguments + listOf("--no-config", "--color", "never", "--no-progress")
        val builder = ProcessBuilder(command).directory(projectDirectory).redirectErrorStream(true)
        builder.environment().apply {
            // A user's own uv settings, or an activated environment, must not redirect the install.
            keys.removeAll { it.startsWith("UV_") || it in INHERITED_PYTHON_SETTINGS }
            put("UV_PYTHON_INSTALL_DIR", interpreters.absolutePath)
            put("UV_PROJECT_ENVIRONMENT", environment.absolutePath)
            put("UV_CACHE_DIR", cache.absolutePath)
        }
        val outcome = runProcess(builder) { line -> log.event("math") { "uv: $line" } }
        if (outcome.exitCode != 0) {
            throw IOException(outcome.lastLine?.take(MAX_MESSAGE) ?: Strings.uv_exited_with_code_value(outcome.exitCode))
        }
    }

    private fun launchServer(requireReady: Boolean): Process {
        if (requireReady && _state.value !is MathRuntimeState.Ready || !python.isFile) {
            throw MathEngineUnavailableException(Strings.the_math_engine_is_not_installed_install_it_in_settings_models)
        }
        // Every start, so the sources the process runs are always this app's.
        project.extract(projectDirectory)
        return ProcessBuilder(python.path, "-E", "-s", "-B", "-X", "utf8", "-u", MathEngineProject.SERVER)
            .directory(projectDirectory)
            .start()
    }

    private fun currentState(): MathRuntimeState {
        installedVersions()?.let { (python, sympy) -> return MathRuntimeState.Ready(python, sympy) }
        return when (val lookup = findUv()) {
            is UvLookup.Found -> MathRuntimeState.NotInstalled
            is UvLookup.Unavailable -> MathRuntimeState.UvUnavailable(lookup.reason)
        }
    }

    /** The versions the last verified install recorded, if it installed this app's environment. */
    private fun installedVersions(): Pair<String, String>? {
        if (!marker.isFile || !python.isFile) return null
        val recorded = runCatching { Properties().apply { marker.inputStream().use(::load) } }.getOrNull() ?: return null
        if (recorded.getProperty(ENVIRONMENT) != project.environmentDigest) return null
        val pythonVersion = recorded.getProperty(PYTHON) ?: return null
        val sympyVersion = recorded.getProperty(SYMPY) ?: return null
        return pythonVersion to sympyVersion
    }

    private fun writeMarker(pythonVersion: String, sympyVersion: String) {
        val staging = File(root, ".installed.properties.part")
        staging.outputStream().use { output ->
            Properties().apply {
                setProperty(ENVIRONMENT, project.environmentDigest)
                setProperty(PYTHON, pythonVersion)
                setProperty(SYMPY, sympyVersion)
            }.store(output, "viveNotes math engine")
        }
        try {
            Files.move(staging.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Beside `notes.db` and `ai_models` in the data directory. */
        const val DIRECTORY = "math_engine"

        private const val ENVIRONMENT = "environment"
        private const val PYTHON = "python"
        private const val SYMPY = "sympy"
        private const val MAX_MESSAGE = 200
        private val INHERITED_PYTHON_SETTINGS = setOf("VIRTUAL_ENV", "CONDA_PREFIX", "PYTHONHOME", "PYTHONPATH")

        private fun MathRuntimeState.describe(): String = when (this) {
            is MathRuntimeState.Ready -> "ready (Python $python, SymPy $sympy)"
            is MathRuntimeState.UvUnavailable -> "uv unavailable"
            else -> this::class.simpleName.orEmpty()
        }
    }
}

/** Where uv is, or why it cannot be used. */
sealed interface UvLookup {
    data class Found(val file: File, val version: UvVersion) : UvLookup
    data class Unavailable(val reason: String) : UvLookup

    companion object {
        /** uv 0.8 added `python install --no-bin` and `--no-registry`, which the install passes. */
        val MINIMUM = UvVersion(0, 8, 0)

        /**
         * An explicit path — the `vivenotes.uv` property or `VIVE_UV` — else the first `uv` on
         * `PATH`, else where uv's own installers put it, which a desktop launcher's `PATH` often
         * lacks. A set-but-wrong explicit path is reported as such rather than searched past.
         */
        fun find(
            explicit: String? = System.getProperty("vivenotes.uv")?.takeIf(String::isNotBlank)
                ?: System.getenv("VIVE_UV")?.takeIf(String::isNotBlank),
            path: String? = System.getenv("PATH"),
            home: File = File(System.getProperty("user.home")),
            windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
            version: (File) -> String? = ::versionOutput,
        ): UvLookup {
            val name = if (windows) "uv.exe" else "uv"
            val file = if (explicit != null) {
                File(explicit).takeIf { it.isFile && it.canExecute() }
                    ?: return Unavailable(Strings.no_uv_at_value(explicit))
            } else {
                path.orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank).map { File(it, name) }
                    .plus(listOf(File(home, ".local/bin/$name"), File(home, ".cargo/bin/$name")))
                    .firstOrNull { it.isFile && it.canExecute() }
                    ?: return Unavailable(Strings.installing_the_math_engine_needs_uv_get_it_from_docs_astral)
            }
            val found = version(file)?.let(UvVersion::parse)
                ?: return Unavailable(Strings.uv_at_value_did_not_report_its_version(file.path))
            return if (found < MINIMUM) {
                Unavailable(Strings.uv_value_is_too_old_the_math_engine_needs_uv_value(found, MINIMUM))
            } else {
                Found(file, found)
            }
        }

        private fun versionOutput(file: File): String? = runCatching {
            val process = ProcessBuilder(file.path, "--version").redirectErrorStream(true).start()
            val output = process.inputReader(Charsets.UTF_8).use { it.readText() }
            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                output.takeIf { process.exitValue() == 0 }
            }
        }.getOrNull()

        private const val VERSION_TIMEOUT_SECONDS = 15L
    }
}

data class UvVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<UvVersion> {
    override fun compareTo(other: UvVersion): Int =
        compareValuesBy(this, other, UvVersion::major, UvVersion::minor, UvVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""\buv (\d+)\.(\d+)\.(\d+)""")

        /** Reads `uv 0.12.19 (…)`, what `uv --version` prints. */
        fun parse(output: String): UvVersion? = PATTERN.find(output)?.destructured?.let { (major, minor, patch) ->
            UvVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/** How a finished process went: its exit code and the last thing it printed. */
internal data class ProcessOutcome(val exitCode: Int, val lastLine: String?)

/**
 * Runs [builder] to the end, handing each line it prints to [onLine]. Cancelling kills it and
 * everything it started — uv compiles bytecode in Python workers of its own. The reading side hands
 * back its outcome rather than throwing, so a pipe the kill closed cannot outrank the cancellation.
 */
internal suspend fun runProcess(builder: ProcessBuilder, onLine: (String) -> Unit): ProcessOutcome = coroutineScope {
    val process = builder.start()
    val finished = async(Dispatchers.IO) {
        runCatching {
            var last: String? = null
            process.inputReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        last = trimmed
                        onLine(trimmed)
                    }
                }
            }
            ProcessOutcome(process.waitFor(), last)
        }
    }
    try {
        finished.await()
    } catch (cancelled: CancellationException) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        throw cancelled
    }.getOrThrow()
}
