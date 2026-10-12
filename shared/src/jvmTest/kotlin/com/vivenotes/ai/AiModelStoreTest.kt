package com.vivenotes.ai

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            assertEquals(FormulaEngine.UniMerNetTiny, state.formulaEngine)
            assertFalse(state.formulaReady)
            assertNull(store.installedFormulaFiles())
            assertNull(store.installedUniMerNetFiles())
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

    /** Android's `anInstalledPackageIsNeverFetchedAgain`, after the first fetch that installed it. */
    @Test
    fun autoDownloadFetchesTheDefaultOnceAndNeverAgain() = runBlocking<Unit> {
        store(downloader = writing, autoDownload = true).use { store ->
            settle(store)
            installed(store, FormulaEngine.UniMerNetTiny)
        }
        assertEquals(AiModelCatalog.formulaArtifacts(FormulaEngine.UniMerNetTiny).map { it.fileName }, attempted.toList())

        attempted.clear()
        store(downloader = refusing, autoDownload = true).use { store ->
            val state = settle(store)
            assertEquals(AiModelInstallState.Installed, state.uniMerNet)
        }
        assertTrue("reached for $attempted despite the default being installed", attempted.isEmpty())
    }

    @Test
    fun autoDownloadHonoursADeletedDefault() = runBlocking<Unit> {
        val deleted = InMemoryAiModelPreferences(AiModelChoices(deletedFormulaEngines = setOf(FormulaEngine.UniMerNetTiny)))
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
    fun aFailedDownloadLeavesNothingBehindAndRetryInstalls() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val lines = mutableListOf<String>()
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

    @Test
    fun useAndDeleteAreRememberedAndHandRecognitionOver() = runBlocking<Unit> {
        val root = File(temporary.root, AiModelStore.DIRECTORY)
        val preferences = InMemoryAiModelPreferences()
        store(root, downloader = writing, preferences = preferences).use { store ->
            settle(store)
            FormulaEngine.entries.forEach { store.download(it); installed(store, it) }
            // Both installed: the best one is in use until somebody picks.
            assertEquals(FormulaEngine.UniMerNetTiny, store.state.value.formulaEngine)

            store.selectFormulaEngine(FormulaEngine.FormulaNetS)
            assertEquals(FormulaEngine.FormulaNetS, store.state.value.formulaEngine)
            eventually { preferences.load().formulaEngine == FormulaEngine.FormulaNetS }

            store.delete(FormulaEngine.FormulaNetS)
            assertEquals(AiModelInstallState.NotInstalled, store.state.value.formulaLatex)
            assertEquals(FormulaEngine.UniMerNetTiny, store.state.value.formulaEngine)
            eventually { !File(root, "pp-formulanet-s-v1").exists() }
            eventually { FormulaEngine.FormulaNetS in preferences.load().deletedFormulaEngines }

            // Only an installed model can be chosen.
            store.selectFormulaEngine(FormulaEngine.FormulaNetS)
            assertEquals(FormulaEngine.UniMerNetTiny, store.state.value.formulaEngine)
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
