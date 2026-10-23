package com.vivenotes.ai

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The model store on desktop paths: what is installed is read from the files, never remembered;
 * a package is installed whole or not at all; and the formula-model rules decide what recognition
 * runs. Android's `AiModelStoreTest` cases are here as well, with the network replaced by fakes.
 */
class AiModelStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** Every artifact the store reached for. */
    private val attempted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Records and refuses, as Android's test does: the network is not available to these tests. */
    private val refusing = ArtifactDownloader { artifact, _, _ ->
        attempted += artifact.fileName
        throw IOException("the network is not available to this test")
    }

    /**
     * Writes each artifact as a sparse file of its pinned size — what a verified download leaves,
     * without 113 MB of traffic. The store trusts its downloader's verification, so this installs.
     */
    private val writing = ArtifactDownloader { artifact, destination, onBytes ->
        attempted += artifact.fileName
        RandomAccessFile(destination, "rw").use { it.setLength(artifact.bytes) }
        onBytes(artifact.bytes)
    }

    private fun store(
        root: File = File(temporary.root, AiModelStore.DIRECTORY),
        downloader: ArtifactDownloader = refusing,
        autoDownload: Boolean = false,
        preferences: AiModelPreferences = InMemoryAiModelPreferences(),
        bundled: BundledModelFiles = BundledModelFiles.Classpath,
        log: DebugLog = DebugLog(),
    ) = AiModelStore(root, preferences, Dispatchers.IO, bundled, log, autoDownload, downloader)

    private suspend fun settle(store: AiModelStore): AiModelsState {
        val state = withTimeout(SETTLE_TIMEOUT_MS) {
            store.state.first { state ->
                state.handwritingText !is AiModelInstallState.Verifying &&
                    FormulaEngine.entries.none { state.formula(it) is AiModelInstallState.Verifying }
            }
        }
        // The eager branch runs just after the state it decides from is published, so give it the
        // chance to be wrong before concluding that it wasn't.
        delay(SETTLE_GRACE_MS)
        return state
    }

    private suspend fun installed(store: AiModelStore, engine: FormulaEngine) = withTimeout(SETTLE_TIMEOUT_MS) {
        store.state.first { it.formula(engine) == AiModelInstallState.Installed }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(SETTLE_TIMEOUT_MS) {
        while (!condition()) delay(20)
    }

    @Test
    fun aFreshStoreHasTheBundledOcrAndNoFormulaModels() = runBlocking<Unit> {
        store().use { store ->
            val state = settle(store)

            assertEquals(AiModelInstallState.Installed, state.handwritingText)
            assertEquals(AiModelInstallState.NotInstalled, state.formulaLatex)
            assertEquals(AiModelInstallState.NotInstalled, state.uniMerNet)
            assertEquals(AiModelInstallState.NotInstalled, state.uniMerNetSmall)
            assertEquals(FormulaEngine.UniMerNetSmall, state.formulaEngine)
            assertFalse(state.formulaReady)
            assertNull(store.installedFormulaFiles())
            assertNull(store.installedUniMerNetFiles())
            assertNull(store.installedUniMerNetFiles(FormulaEngine.UniMerNetSmall))
            // The bundled graphs open from the jar.
            assertEquals(AiModelCatalog.TEXT_MODEL.bytes, store.openTextModel().use { it.readBytes().size.toLong() })
        }
    }

    /** Android's: "off" cannot quietly stop meaning off. */
    @Test
    fun aStoreBuiltWithoutAutoDownloadNeverReachesForTheNetwork() = runBlocking<Unit> {
        store().use { settle(it) }

        assertTrue("reached for $attempted with auto-download off", attempted.isEmpty())
    }

    @Test
    fun bundledSmallVerificationLogsItsIdentityOnlyWhenDebugIsEnabled() = runBlocking<Unit> {
        for (enabled in listOf(false, true)) {
            val lines = Collections.synchronizedList(mutableListOf<String>())
            store(root = File(temporary.root, "ocr-$enabled"), log = DebugLog(enabled) { lines += it }).use { store ->
                assertEquals(AiModelInstallState.Installed, settle(store).handwritingText)
            }
            if (enabled) {
                assertTrue(lines.toString(), lines.any { it.contains("bundled OCR verified: PP-OCRv6 Small") })
            } else {
                assertTrue(lines.toString(), lines.isEmpty())
            }
        }
    }

    /** Android's `anInstalledPackageIsNeverFetchedAgain`, after the first fetch that installed it. */
    @Test
    fun autoDownloadFetchesTheDefaultOnceAndNeverAgain() = runBlocking<Unit> {
        store(downloader = writing, autoDownload = true).use { store ->
            settle(store)
            installed(store, FormulaEngine.UniMerNetSmall)
        }
        assertEquals(AiModelCatalog.formulaArtifacts(FormulaEngine.UniMerNetSmall).map { it.fileName }, attempted.toList())

        attempted.clear()
        store(downloader = refusing, autoDownload = true).use { store ->
            val state = settle(store)
            assertEquals(AiModelInstallState.Installed, state.uniMerNetSmall)
        }
        assertTrue("reached for $attempted despite the default being installed", attempted.isEmpty())
    }

    @Test
    fun aFailedFirstRunSmallDownloadRetriesOnTheNextLaunch() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        store(root, downloader = refusing, autoDownload = true).use { store ->
            val state = withTimeout(SETTLE_TIMEOUT_MS) {
                store.state.first { it.uniMerNetSmall is AiModelInstallState.Failed }
            }
            assertFalse(state.formulaReady)
            assertNull(store.installedUniMerNetFiles(FormulaEngine.UniMerNetSmall))
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        }
        assertEquals(listOf(AiModelCatalog.UNIMERNET_SMALL_ENCODER.fileName), attempted.toList())

        attempted.clear()
        store(root, downloader = writing, autoDownload = true).use { store ->
            val state = installed(store, FormulaEngine.UniMerNetSmall)
            assertEquals(FormulaEngine.UniMerNetSmall, state.formulaEngine)
            assertTrue(state.formulaReady)
            assertEquals(AiModelInstallState.NotInstalled, state.uniMerNet)
            assertEquals(AiModelInstallState.NotInstalled, state.formulaLatex)
        }
        assertEquals(AiModelCatalog.formulaArtifacts(FormulaEngine.UniMerNetSmall).map { it.fileName }, attempted.toList())
    }

    @Test
    fun deletingAutomaticallyInstalledSmallStopsDownloadsAcrossRelaunch() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val preferences = InMemoryAiModelPreferences()
        store(root, downloader = writing, autoDownload = true, preferences = preferences).use { store ->
            installed(store, FormulaEngine.UniMerNetSmall)
            store.delete(FormulaEngine.UniMerNetSmall)
            eventually { FormulaEngine.UniMerNetSmall in preferences.load().deletedFormulaEngines }
        }
        attempted.clear()
        store(root, downloader = refusing, autoDownload = true, preferences = preferences).use { store ->
            assertEquals(AiModelInstallState.NotInstalled, settle(store).uniMerNetSmall)
        }
        assertTrue("fetched $attempted after deleting Small", attempted.isEmpty())
    }

    @Test
    fun autoDownloadHonoursADeletedDefault() = runBlocking<Unit> {
        val deleted = InMemoryAiModelPreferences(AiModelChoices(deletedFormulaEngines = setOf(FormulaEngine.UniMerNetSmall)))
        store(autoDownload = true, preferences = deleted).use { settle(it) }

        assertTrue("fetched $attempted after the user deleted it", attempted.isEmpty())
    }

    @Test
    fun aDownloadInstallsTheWholePackageAndHandsRecognitionToIt() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        store(root, downloader = writing).use { store ->
            settle(store)
            store.download(FormulaEngine.FormulaNetS)
            val state = installed(store, FormulaEngine.FormulaNetS)

            // The only installed model is the one in use, though the default is another.
            assertEquals(FormulaEngine.FormulaNetS, state.formulaEngine)
            assertTrue(state.formulaReady)
            val files = store.installedFormulaFiles()!!
            assertEquals(AiModelCatalog.FORMULA_MODEL.bytes, files.model.length())
            assertEquals(AiModelCatalog.FORMULA_TOKENIZER.bytes, files.tokenizer.length())
            assertEquals(File(root, "pp-formulanet-s-v1"), files.model.parentFile)
            assertTrue(File(files.model.parentFile, ".verified").isFile)
            assertTrue("staging left behind", root.listFiles()!!.none { it.name.endsWith(".part") })
        }
    }

    @Test
    fun smallAndTinyInstallIndependentlyAndDeletingSmallPreservesTiny() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val preferences = InMemoryAiModelPreferences()
        store(root, downloader = writing, preferences = preferences).use { store ->
            settle(store)
            for (engine in listOf(FormulaEngine.UniMerNetTiny, FormulaEngine.UniMerNetSmall)) {
                store.download(engine)
                installed(store, engine)
            }
            val small = store.installedUniMerNetFiles(FormulaEngine.UniMerNetSmall)!!
            val tiny = store.installedUniMerNetFiles(FormulaEngine.UniMerNetTiny)!!
            assertEquals(File(root, "unimernet-small-v1"), small.encoder.parentFile)
            assertEquals(AiModelCatalog.UNIMERNET_SMALL_ENCODER.bytes, small.encoder.length())
            assertEquals(AiModelCatalog.UNIMERNET_SMALL_DECODER.bytes, small.decoder.length())
            assertEquals(AiModelCatalog.UNIMERNET_SMALL_TOKENIZER.bytes, small.tokenizer.length())
            assertEquals(File(root, "unimernet-tiny-v1"), tiny.encoder.parentFile)
            assertEquals(FormulaEngine.UniMerNetSmall, store.state.value.formulaEngine)
            assertNull(store.installedUniMerNetFiles(FormulaEngine.FormulaNetS))

            store.delete(FormulaEngine.UniMerNetSmall)
            assertEquals(FormulaEngine.UniMerNetTiny, store.state.value.formulaEngine)
            assertNull(store.installedUniMerNetFiles(FormulaEngine.UniMerNetSmall))
            assertTrue(tiny.encoder.isFile && tiny.decoder.isFile)
            eventually { FormulaEngine.UniMerNetSmall in preferences.load().deletedFormulaEngines }
            eventually { !small.encoder.parentFile.exists() }
        }
        store(root, preferences = preferences).use { store ->
            assertEquals(FormulaEngine.UniMerNetTiny, settle(store).formulaEngine)
            assertNull(store.installedUniMerNetFiles(FormulaEngine.UniMerNetSmall))
            assertTrue(store.installedUniMerNetFiles(FormulaEngine.UniMerNetTiny)!!.encoder.isFile)
        }
    }

    @Test
    fun aFailedSmallDownloadPreservesTheInstalledTinyPackageAndReportsProgress() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val failing = ArtifactDownloader { artifact, destination, onBytes ->
            if (artifact == AiModelCatalog.UNIMERNET_SMALL_DECODER) throw IOException("Small decoder interrupted")
            writing.download(artifact, destination, onBytes)
        }
        store(root, downloader = failing, log = DebugLog(enabled = true) { lines += it }).use { store ->
            settle(store)
            store.download(FormulaEngine.UniMerNetTiny)
            installed(store, FormulaEngine.UniMerNetTiny)
            store.download(FormulaEngine.UniMerNetSmall)
            val state = withTimeout(SETTLE_TIMEOUT_MS) {
                store.state.first { it.uniMerNetSmall is AiModelInstallState.Failed }
            }
            assertEquals(AiModelInstallState.Failed("Small decoder interrupted"), state.uniMerNetSmall)
            assertEquals(AiModelInstallState.Installed, state.uniMerNet)
            assertEquals(FormulaEngine.UniMerNetTiny, state.formulaEngine)
            assertFalse(File(root, "unimernet-small-v1").exists())
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
            assertTrue(lines.toString(), lines.any {
                it.contains("UniMERNet Small download after ${AiModelCatalog.UNIMERNET_SMALL_ENCODER.bytes} bytes failed")
            })
            // The decoder failed before its first byte, so the tokenizer after it never started.
            assertFalse("$attempted", AiModelCatalog.UNIMERNET_SMALL_TOKENIZER.fileName in attempted)
        }
    }

    @Test
    fun installingSmallKeepsAnExplicitTinyChoiceAcrossRelaunch() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val preferences = InMemoryAiModelPreferences()
        store(root, downloader = writing, preferences = preferences).use { store ->
            settle(store)
            store.download(FormulaEngine.UniMerNetTiny)
            installed(store, FormulaEngine.UniMerNetTiny)
            store.selectFormulaEngine(FormulaEngine.UniMerNetTiny)
            eventually { preferences.load().formulaEngine == FormulaEngine.UniMerNetTiny }
            store.download(FormulaEngine.UniMerNetSmall)
            installed(store, FormulaEngine.UniMerNetSmall)
            assertEquals(FormulaEngine.UniMerNetTiny, store.state.value.formulaEngine)
        }
        store(root, preferences = preferences).use { store ->
            assertEquals(FormulaEngine.UniMerNetTiny, settle(store).formulaEngine)
            store.selectFormulaEngine(FormulaEngine.UniMerNetSmall)
            eventually { preferences.load().formulaEngine == FormulaEngine.UniMerNetSmall }
        }
        store(root, preferences = preferences).use { store ->
            assertEquals(FormulaEngine.UniMerNetSmall, settle(store).formulaEngine)
        }
    }

    @Test
    fun aFailedDownloadLeavesNothingBehindAndRetryInstalls() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        var fail = true
        val flaky = ArtifactDownloader { artifact, destination, onBytes ->
            if (fail && artifact == AiModelCatalog.UNIMERNET_DECODER) throw IOException("Chain validation failed")
            writing.download(artifact, destination, onBytes)
        }
        store(root, downloader = flaky, log = DebugLog(enabled = true) { lines += it }).use { store ->
            settle(store)
            store.download(FormulaEngine.UniMerNetTiny)
            val failed = withTimeout(SETTLE_TIMEOUT_MS) {
                store.state.first { it.uniMerNet is AiModelInstallState.Failed }
            }

            assertEquals(AiModelInstallState.Failed("Chain validation failed"), failed.uniMerNet)
            assertFalse(File(root, "unimernet-tiny-v1").exists())
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
            // The log says how far it got, which the pane's one line cannot.
            assertTrue(lines.toString(), lines.any {
                it.contains("UniMERNet-T download after ${AiModelCatalog.UNIMERNET_ENCODER.bytes} bytes failed: IOException")
            })

            fail = false
            store.download(FormulaEngine.UniMerNetTiny)
            installed(store, FormulaEngine.UniMerNetTiny)
        }
    }

    /**
     * Retry pressed the moment the pane shows a failure downloads again: the failed job has
     * published its state by then but has not returned, and the press is not taken for it.
     */
    @Test
    fun aRetryPressedAsTheFailureShowsDownloadsAgain() = runBlocking<Unit> {
        val failures = AtomicInteger()
        val failingOnce = ArtifactDownloader { artifact, destination, onBytes ->
            if (artifact == AiModelCatalog.UNIMERNET_DECODER && failures.getAndIncrement() == 0) {
                throw IOException("Chain validation failed")
            }
            writing.download(artifact, destination, onBytes)
        }
        store(downloader = failingOnce).use { store ->
            settle(store)
            // Unconfined, so the retry runs on the download's own thread as it publishes the failure.
            launch(Dispatchers.Unconfined) {
                store.state.first { it.uniMerNet is AiModelInstallState.Failed }
                store.download(FormulaEngine.UniMerNetTiny)
            }
            store.download(FormulaEngine.UniMerNetTiny)

            val retried = withTimeoutOrNull(10_000) {
                store.state.first { it.uniMerNet == AiModelInstallState.Installed }
            }
            assertEquals(AiModelInstallState.Installed, retried?.uniMerNet)
            assertEquals(2, failures.get())
        }
    }

    @Test
    fun useAndDeleteAreRememberedAndHandRecognitionOver() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val preferences = InMemoryAiModelPreferences()
        store(root, downloader = writing, preferences = preferences).use { store ->
            settle(store)
            FormulaEngine.entries.forEach { store.download(it); installed(store, it) }
            // All installed: the best one is in use until somebody picks.
            assertEquals(FormulaEngine.UniMerNetSmall, store.state.value.formulaEngine)

            store.selectFormulaEngine(FormulaEngine.FormulaNetS)
            assertEquals(FormulaEngine.FormulaNetS, store.state.value.formulaEngine)
            eventually { preferences.load().formulaEngine == FormulaEngine.FormulaNetS }

            store.delete(FormulaEngine.FormulaNetS)
            assertEquals(AiModelInstallState.NotInstalled, store.state.value.formulaLatex)
            assertEquals(FormulaEngine.UniMerNetSmall, store.state.value.formulaEngine)
            eventually { !File(root, "pp-formulanet-s-v1").exists() }
            eventually { FormulaEngine.FormulaNetS in preferences.load().deletedFormulaEngines }

            // Only an installed model can be chosen.
            store.selectFormulaEngine(FormulaEngine.FormulaNetS)
            assertEquals(FormulaEngine.UniMerNetSmall, store.state.value.formulaEngine)
        }
        // The choice survives a relaunch while its model is installed.
        store(root, preferences = InMemoryAiModelPreferences(AiModelChoices(FormulaEngine.UniMerNetTiny))).use { store ->
            assertEquals(FormulaEngine.UniMerNetTiny, settle(store).formulaEngine)
        }
    }

    /**
     * A relaunch trusts its own marker and checks sizes; a marker for other pins — another model in
     * the slot — sends it back to a full SHA-256 pass, which these sparse files fail.
     */
    @Test
    fun aRelaunchTrustsAMatchingMarkerButVerifiesAnyOther() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        store(root, downloader = writing).use { store ->
            settle(store)
            store.download(FormulaEngine.UniMerNetTiny)
            installed(store, FormulaEngine.UniMerNetTiny)
        }
        store(root).use { assertEquals(AiModelInstallState.Installed, settle(it).uniMerNet) }

        File(root, "unimernet-tiny-v1/.verified").writeText("some other model")
        store(root).use { assertEquals(AiModelInstallState.NotInstalled, settle(it).uniMerNet) }
    }

    @Test
    fun aTruncatedFileIsNotAnInstall() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        store(root, downloader = writing).use { store ->
            settle(store)
            store.download(FormulaEngine.FormulaNetS)
            installed(store, FormulaEngine.FormulaNetS)
        }
        RandomAccessFile(File(root, "pp-formulanet-s-v1/pp-formulanet-s.onnx"), "rw").use { it.setLength(1_000) }

        store(root).use { assertEquals(AiModelInstallState.NotInstalled, settle(it).formulaLatex) }
    }

    /** A process killed mid-download cannot clean up after itself, so the next launch does. */
    @Test
    fun staleStagingIsRemovedAtLaunch() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val stale = File(root, ".unimernet-tiny-v1-12345.part").apply { mkdirs() }
        File(stale, "unimernet-tiny-decoder-int8.onnx").writeText("half")
        val unrelated = File(root, "keep-me").apply { mkdirs() }

        store(root).use { settle(it) }

        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun closingCancelsADownloadAndRemovesItsStaging() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hanging = ArtifactDownloader { _, destination, _ ->
            destination.writeText("partial")
            started.complete(Unit)
            awaitCancellation()
        }
        val store = store(root, downloader = hanging)
        settle(store)
        store.download(FormulaEngine.UniMerNetTiny)
        withTimeout(SETTLE_TIMEOUT_MS) { started.await() }

        store.close()

        eventually { root.listFiles().orEmpty().none { it.name.endsWith(".part") } }
        assertFalse(File(root, "unimernet-tiny-v1").exists())
    }

    /** The detector, recognizer and dictionary are one capability: any of them missing fails it. */
    @Test
    fun aMissingOrAlteredBundledFileFailsTheOcrModel() = runBlocking<Unit> {
        val missingDictionary = BundledModelFiles { name ->
            if (name == AiModelCatalog.TEXT_DICTIONARY.fileName) null else BundledModelFiles.Classpath.open(name)
        }
        store(bundled = missingDictionary).use { store ->
            assertEquals(AiModelInstallState.Failed("Bundled OCR model is unavailable"), settle(store).handwritingText)
        }

        val alteredDetector = BundledModelFiles { name ->
            if (name == AiModelCatalog.DETECTION_MODEL.fileName) ByteArrayInputStream(ByteArray(10)) else BundledModelFiles.Classpath.open(name)
        }
        store(root = File(temporary.root, "other"), bundled = alteredDetector).use { store ->
            assertEquals(AiModelInstallState.Failed("Bundled OCR model is unavailable"), settle(store).handwritingText)
        }
    }

    private companion object {
        const val SETTLE_TIMEOUT_MS = 30_000L
        const val SETTLE_GRACE_MS = 300L
    }
}
