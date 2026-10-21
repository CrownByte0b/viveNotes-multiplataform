package com.vivenotes.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiModelDownloadConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun formulaArtifactsOverlapWithinTwoGlobalSlotsAndInstallOnlyAfterAllComplete() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val entered = AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val downloader = ArtifactDownloader { artifact, destination, onBytes ->
            val current = active.incrementAndGet()
            peak.accumulateAndGet(current) { first, second -> maxOf(first, second) }
            try {
                onBytes(1)
                if (entered.incrementAndGet() == 2) twoStarted.complete(Unit)
                release.await()
                RandomAccessFile(destination, "rw").use { it.setLength(artifact.bytes) }
                onBytes(artifact.bytes)
            } finally { active.decrementAndGet() }
        }
        val root = temporary.newFolder()
        AiModelStore(root, InMemoryAiModelPreferences(), Dispatchers.IO, BundledModelFiles.Classpath,
            com.vivenotes.diagnostics.DebugLog(), false, downloader).use { store ->
            withTimeout(10_000) { store.state.first { it.handwritingText != AiModelInstallState.Verifying } }
            store.download(FormulaEngine.UniMerNetSmall)
            store.download(FormulaEngine.FormulaNetS)
            withTimeout(5_000) { twoStarted.await() }
            assertEquals(2, peak.get())
            assertTrue(store.installedUniMerNetFiles(FormulaEngine.UniMerNetSmall) == null)
            release.complete(Unit)
            withTimeout(10_000) {
                store.state.first { it.uniMerNetSmall == AiModelInstallState.Installed && it.formulaLatex == AiModelInstallState.Installed }
            }
            assertEquals(2, peak.get())
            assertEquals(0, active.get())
            assertEquals(5, entered.get())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        }
    }
}
