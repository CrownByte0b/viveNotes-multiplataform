package com.vivenotes.ai

import com.vivenotes.localization.Strings
import com.vivenotes.diagnostics.DebugLog
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What a person decided about the formula models: the one they picked, and the ones they deleted. */
data class AiModelChoices(
    val formulaEngine: FormulaEngine? = null,
    val deletedFormulaEngines: Set<FormulaEngine> = emptySet(),
)

/** Where [AiModelStore] keeps [AiModelChoices] — Android's `ai` DataStore. */
interface AiModelPreferences {
    fun load(): AiModelChoices
    fun save(choices: AiModelChoices)
}

/** Choices held for the life of the process only: tests, and a store with nowhere to write. */
class InMemoryAiModelPreferences(private var choices: AiModelChoices = AiModelChoices()) : AiModelPreferences {
    override fun load(): AiModelChoices = synchronized(this) { choices }
    override fun save(choices: AiModelChoices) = synchronized(this) { this.choices = choices }
}

/** Opens one of the model files the app carries, or null when it does not carry it. */
fun interface BundledModelFiles {
    fun open(fileName: String): InputStream?

    companion object {
        /** The jar's `ai/` resources. */
        val Classpath = BundledModelFiles { name -> AiModelStore::class.java.getResourceAsStream("/ai/$name") }
    }
}

/**
 * Owns this device's recognition models: Android's `ai/AiModelStore.kt`, on desktop.
 *
 * OCR is bundled in the jar, so it is available offline. Each formula model is a download of over
 * 100 MB into [modelsRoot] (`ai_models` in the data directory, as on Android), installed as one
 * package — graphs plus tokenizer — only after every staged file verifies. Which packages are
 * installed is derived from those files and their verification markers, never from a preference.
 * The preferences hold only which model the user picked, if they ever did, and which ones they have
 * deleted.
 */
class AiModelStore internal constructor(
    private val modelsRoot: File,
    private val preferences: AiModelPreferences,
    ioDispatcher: CoroutineDispatcher,
    private val bundled: BundledModelFiles,
    private val log: DebugLog,
    /**
     * Whether a launch without the default formula model fetches it by itself, as Android does on
     * unmetered Wi-Fi. Off on the desktop for now: nothing reads a formula yet, and the JVM has no
     * metered-connection signal, so the Models pane's Download is the only way a model arrives.
     */
    private val autoDownload: Boolean,
    private val downloader: ArtifactDownloader,
) : AiModels, AutoCloseable {
    constructor(
        modelsRoot: File,
        preferences: AiModelPreferences = InMemoryAiModelPreferences(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        bundled: BundledModelFiles = BundledModelFiles.Classpath,
        log: DebugLog = DebugLog(),
        autoDownload: Boolean = false,
    ) : this(modelsRoot, preferences, ioDispatcher, bundled, log, autoDownload, VerifiedArtifactDownloader())

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val packages: Map<FormulaEngine, FormulaPackage> = FormulaEngine.entries.associateWith { engine ->
        FormulaPackage(
            directory = File(modelsRoot, slot(engine)),
            artifacts = AiModelCatalog.formulaArtifacts(engine),
            name = engine.displayName,
        )
    }

    private val _state = MutableStateFlow(AiModelsState())
    override val state: StateFlow<AiModelsState> = _state.asStateFlow()

    private val downloads = mutableMapOf<FormulaEngine, Job>()

    /** Read-modify-write of [preferences], one at a time, as DataStore's `edit` is. */
    private val preferencesLock = Mutex()

    /** The model the user pressed Use on, if they ever did — [formulaEngineInUse]'s `chosen`. */
    @Volatile
    private var chosenFormulaEngine: FormulaEngine? = null

    init {
        scope.launch {
            cleanupStaleDownloads()
            val textState = verifyBundledTextModel()
            val formula = FormulaEngine.entries.associateWith { verifyInstalledPackage(packages.getValue(it)) }
            val choices = runCatching { preferences.load() }
                .onFailure { log.failure("ai", "model preferences read", it) }
                .getOrDefault(AiModelChoices())
            val installed = formula.filterValues { it == AiModelInstallState.Installed }.keys
            chosenFormulaEngine = choices.formulaEngine
            _state.value = AiModelsState(
                handwritingText = textState,
                formulaLatex = formula.getValue(FormulaEngine.FormulaNetS),
                uniMerNet = formula.getValue(FormulaEngine.UniMerNetTiny),
                uniMerNetSmall = formula.getValue(FormulaEngine.UniMerNetSmall),
                formulaEngine = formulaEngineInUse(choices.formulaEngine, installed),
            )
            log.event("ai") {
                "models checked: ocr=${textState.label()}, installed=${installed.joinToString { it.name }}, " +
                    "in use=${_state.value.formulaEngine.name}"
            }
            val fetch = formulaEngineToFetch(installed, choices.deletedFormulaEngines)
            if (autoDownload && fetch != null && formula.getValue(fetch) == AiModelInstallState.NotInstalled) {
                download(fetch)
            }
        }
    }

    /** Downloads one formula model's package. Repeated requests share the active job. */
    override fun download(engine: FormulaEngine) {
        synchronized(downloads) {
            if (downloads[engine]?.isActive == true) return
            downloads[engine] = scope.launch { fetch(engine, packages.getValue(engine)) }
        }
    }

    private suspend fun fetch(engine: FormulaEngine, formulaPackage: FormulaPackage) {
        val staging = File(modelsRoot, ".${formulaPackage.directory.name}-${System.nanoTime()}.part")
        var completedBytes = 0L
        try {
            log.event("ai") { "${formulaPackage.name} download started" }
            modelsRoot.mkdirsOrThrow()
            staging.mkdirsOrThrow()
            setPackageState(engine, AiModelInstallState.Downloading(0, formulaPackage.totalBytes))

            formulaPackage.artifacts.forEach { artifact ->
                downloader.download(
                    artifact = artifact,
                    destination = File(staging, artifact.fileName),
                    onBytes = { currentFileBytes ->
                        setPackageState(
                            engine,
                            AiModelInstallState.Downloading(
                                downloadedBytes = completedBytes + currentFileBytes,
                                totalBytes = formulaPackage.totalBytes,
                            ),
                        )
                    },
                )
                completedBytes += artifact.bytes
            }

            setPackageState(engine, AiModelInstallState.Verifying)
            File(staging, VERIFIED_MARKER).writeText(formulaPackage.marker)
            installStagedDirectory(staging, formulaPackage.directory)
            _state.update { current ->
                val next = current.withFormula(engine, AiModelInstallState.Installed)
                next.copy(
                    formulaEngine = formulaEngineInUse(chosenFormulaEngine, next.installedFormulaEngines),
                )
            }
            log.event("ai") { "${formulaPackage.name} installed; in use=${_state.value.formulaEngine.name}" }
        } catch (cancelled: CancellationException) {
            staging.deleteRecursively()
            setPackageState(engine, AiModelInstallState.NotInstalled)
            log.event("ai") { "${formulaPackage.name} download cancelled" }
            throw cancelled
        } catch (failure: Exception) {
            // The pane shows one short line, and several of the ways this fails — a redirect
            // refused, a TLS chain rejected, a digest mismatch — arrive with a message too terse to
            // act on. Which hop failed, and how far it got, goes to the debug log.
            log.failure("ai", "${formulaPackage.name} download after $completedBytes bytes", failure)
            staging.deleteRecursively()
            setPackageState(
                engine,
                AiModelInstallState.Failed(failure.message?.takeIf { it.isNotBlank() } ?: Strings.download_failed),
            )
        }
    }

    /**
     * One package's state alone. A copy, not a fresh [AiModelsState]: rebuilding the whole state
     * from the fields one download knows about would reset the others on every progress tick.
     */
    private fun setPackageState(engine: FormulaEngine, install: AiModelInstallState) {
        _state.update { it.withFormula(engine, install) }
    }

    /** Which model formula recognition runs. Only an installed model can be chosen. */
    override fun selectFormulaEngine(engine: FormulaEngine) {
        if (_state.value.formula(engine) != AiModelInstallState.Installed) return
        chosenFormulaEngine = engine
        _state.update { it.copy(formulaEngine = engine) }
        log.event("ai") { "formula model in use: ${engine.name}" }
        scope.launch { editPreferences { it.copy(formulaEngine = engine) } }
    }

    /**
     * Removes an installed formula model from this device.
     *
     * The state changes before any file goes, so no new recognition starts on a package being
     * deleted. One already running holds its graphs open and finishes, which on Linux outlives the
     * unlink. A deleted model in use hands recognition to the other one if it is installed —
     * [formulaEngineAfterDelete].
     *
     * The delete is remembered, and from then on no launch fetches that model unasked.
     */
    override fun delete(engine: FormulaEngine) {
        if (_state.value.formula(engine) != AiModelInstallState.Installed) return
        _state.update { current ->
            val next = current.withFormula(engine, AiModelInstallState.NotInstalled)
            next.copy(
                formulaEngine = formulaEngineAfterDelete(
                    selected = current.formulaEngine,
                    deleted = engine,
                    stillInstalled = next.installedFormulaEngines,
                ),
            )
        }
        log.event("ai") { "${engine.name} deleted; in use=${_state.value.formulaEngine.name}" }
        scope.launch {
            packages.getValue(engine).directory.deleteRecursively()
            editPreferences { it.copy(deletedFormulaEngines = it.deletedFormulaEngines + engine) }
        }
    }

    private suspend fun editPreferences(transform: (AiModelChoices) -> AiModelChoices) {
        preferencesLock.withLock {
            try {
                preferences.save(transform(preferences.load()))
            } catch (failure: Exception) {
                log.failure("ai", "model preferences write", failure)
            }
        }
    }

    /** Returns verified FormulaNet-S files for the runtime, or null until the whole package exists. */
    fun installedFormulaFiles(): FormulaModelFiles? {
        if (_state.value.formulaLatex != AiModelInstallState.Installed) return null
        val directory = packages.getValue(FormulaEngine.FormulaNetS).directory
        return FormulaModelFiles(
            model = File(directory, AiModelCatalog.FORMULA_MODEL.fileName),
            tokenizer = File(directory, AiModelCatalog.FORMULA_TOKENIZER.fileName),
        )
    }

    /** One UniMERNet variant's verified graphs and tokenizer, or null until its whole package exists. */
    fun installedUniMerNetFiles(engine: FormulaEngine = FormulaEngine.UniMerNetTiny): UniMerNetFiles? {
        if (engine == FormulaEngine.FormulaNetS || _state.value.formula(engine) != AiModelInstallState.Installed) return null
        val formulaPackage = packages.getValue(engine)
        val directory = formulaPackage.directory
        val (encoder, decoder, tokenizer) = formulaPackage.artifacts
        return UniMerNetFiles(
            encoder = File(directory, encoder.fileName),
            decoder = File(directory, decoder.fileName),
            tokenizer = File(directory, tokenizer.fileName),
        )
    }

    /** Opens the bundled multilingual PP-OCRv6 Small recognizer. */
    fun openTextModel(): InputStream = openBundled(AiModelCatalog.TEXT_MODEL)

    /** Opens the bundled PP-OCRv6 Small CTC dictionary. */
    fun openTextDictionary(): InputStream = openBundled(AiModelCatalog.TEXT_DICTIONARY)

    /**
     * Opens the bundled PP-OCRv6 Small text detector.
     *
     * The complete 31.1 MB OCR package is bundled so text recognition works offline immediately.
     */
    fun openDetectionModel(): InputStream = openBundled(AiModelCatalog.DETECTION_MODEL)

    private fun openBundled(artifact: BundledArtifact): InputStream =
        bundled.open(artifact.fileName) ?: error(Strings.value_is_not_bundled(artifact.fileName))

    /** Cancels any download under way; a cancelled download removes its staging directory. */
    override fun close() {
        scope.cancel()
    }

    /**
     * All three bundled OCR files, verified together.
     *
     * Together because they are one capability: the detector finds the lines and the recognizer
     * reads them, and either one alone reads a picture as nothing. A half-verified pipeline reported
     * as Installed would be a promise recognition cannot keep.
     */
    private fun verifyBundledTextModel(): AiModelInstallState = try {
        AiModelCatalog.BUNDLED_TEXT.forEach { artifact ->
            openBundled(artifact).use { input ->
                val (length, sha256) = input.digest()
                require(length == artifact.bytes && sha256 == artifact.sha256) {
                    Strings.value_failed_verification(artifact.fileName)
                }
            }
        }
        log.event("ai") { "bundled OCR verified: ${AiModelCatalog.TEXT_MODEL_NAME}" }
        AiModelInstallState.Installed
    } catch (failure: Exception) {
        log.failure("ai", "bundled OCR verification", failure)
        AiModelInstallState.Failed(Strings.bundled_ocr_model_is_unavailable)
    }

    /**
     * Installed when every file is there at its pinned size, after one full SHA-256 pass whose
     * result the marker remembers. A marker for different pins — another model in the same slot —
     * is no marker, so a changed catalog verifies again rather than trusting the old files.
     */
    private fun verifyInstalledPackage(formulaPackage: FormulaPackage): AiModelInstallState {
        val directory = formulaPackage.directory
        if (!directory.isDirectory) return AiModelInstallState.NotInstalled
        return try {
            val marker = File(directory, VERIFIED_MARKER)
            if (!marker.isFile || marker.readText() != formulaPackage.marker) {
                formulaPackage.artifacts.forEach { artifact -> verifyFile(File(directory, artifact.fileName), artifact) }
                marker.writeText(formulaPackage.marker)
            } else {
                formulaPackage.artifacts.forEach { artifact ->
                    val file = File(directory, artifact.fileName)
                    require(file.isFile && file.length() == artifact.bytes) { Strings.value_is_incomplete(artifact.fileName) }
                }
            }
            AiModelInstallState.Installed
        } catch (failure: Exception) {
            log.failure("ai", "${formulaPackage.name} verification", failure)
            AiModelInstallState.NotInstalled
        }
    }

    private fun verifyFile(file: File, artifact: ModelArtifact) {
        require(file.isFile && file.length() == artifact.bytes) { Strings.value_is_incomplete(artifact.fileName) }
        val (_, sha256) = file.inputStream().use { it.digest() }
        require(sha256 == artifact.sha256) { Strings.value_failed_verification(artifact.fileName) }
    }

    private fun installStagedDirectory(staging: File, destination: File) {
        if (destination.exists()) destination.deleteRecursively()
        try {
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), destination.toPath())
        }
    }

    /** A process kill cannot run the coroutine's cleanup block, so discard its private staging dir. */
    private fun cleanupStaleDownloads() {
        val prefixes = packages.values.map { ".${it.directory.name}-" }
        modelsRoot.listFiles()
            ?.filter { file -> prefixes.any(file.name::startsWith) && file.name.endsWith(".part") }
            ?.forEach(File::deleteRecursively)
    }

    private fun File.mkdirsOrThrow() {
        require(isDirectory || mkdirs()) { Strings.cannot_create_model_directory }
    }

    /** One formula model's files: where they install, what they are, and its name in a log line. */
    private class FormulaPackage(
        val directory: File,
        val artifacts: List<ModelArtifact>,
        val name: String,
    ) {
        val marker: String = artifacts.joinToString("\n") { "${it.fileName}:${it.bytes}:${it.sha256}" }
        val totalBytes: Long = artifacts.sumOf(ModelArtifact::bytes)
    }

    data class FormulaModelFiles(val model: File, val tokenizer: File)
    data class UniMerNetFiles(val encoder: File, val decoder: File, val tokenizer: File)

    companion object {
        /** Android's model directory name, beside `notes.db` in the data directory. */
        const val DIRECTORY = "ai_models"
        private const val VERIFIED_MARKER = ".verified"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * Each package's install slot, named as on Android. A slot is **deliberately not renamed**
         * when the model inside it changes: [installStagedDirectory] deletes the destination before
         * moving the new package in, so reusing one path keeps exactly one copy on disk, and the
         * package's verification marker is what says which model is in there.
         */
        internal fun slot(engine: FormulaEngine): String = when (engine) {
            FormulaEngine.UniMerNetSmall -> "unimernet-small-v1"
            FormulaEngine.FormulaNetS -> "pp-formulanet-s-v1"
            FormulaEngine.UniMerNetTiny -> "unimernet-tiny-v1"
        }

        private fun InputStream.digest(): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                length += count
            }
            return length to digest.digest().hex()
        }

        private fun AiModelInstallState.label(): String = when (this) {
            is AiModelInstallState.Failed -> "failed"
            else -> this::class.simpleName.orEmpty().lowercase()
        }
    }
}
