package com.vivenotes.ui.shell

import com.vivenotes.model.Outline
import com.vivenotes.model.ink.InkSample
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class CanvasSelectionWorkerTest {
    @Test
    fun largeSelectionRunsOffUiAndPublishesTheEntireExactCleanedState() = runBlocking {
        val initial = fixture().toggleTextTool().createTextBox(600f, 600f).copy(objectLassoArmed = true)
        val emptyText = assertNotNull(initial.focusedTextOutlineId)
        val expected = initial.selectObjectsInLasso(loop)
        assertEquals(setOf("target"), expected.selectedObjectIds)
        assertTrue(expected.selectedPage!!.document.outlines.none { it.id == emptyText })
        val preparedThread = AtomicReference<String>()
        withWorker({ state, path, heights ->
            preparedThread.set(Thread.currentThread().name)
            state.selectObjectsInLasso(path, heights)
        }) { worker, ui, _ ->
            var current = initial
            val task = withContext(ui) {
                worker.submit(initial, loop, emptyMap()) { command -> current = command(current) }
            }
            task.join()
            assertTrue(preparedThread.get().startsWith("lasso-native"))
            withContext(ui) {
                assertEquals(expected, current)
                assertEquals(null, current.focusedTextOutlineId)
            }
        }
    }

    @Test
    fun uncancellableOldPreparationKeepsOneLaneAndOnlyTheLatestQueuedRequestRuns() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<Int>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        withWorker({ state, path, _ ->
            val marker = path.first().x.toInt()
            calls += marker
            val concurrent = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, concurrent) }
            try {
                if (marker == 1) withContext(NonCancellable) { entered.complete(Unit); release.await() }
                state.copy(selectedObjectIds = setOf(marker.toString()))
            } finally { active.decrementAndGet() }
        }) { worker, ui, compute ->
            val source = WorkspaceState.demo()
            var current = source
            var publications = 0
            val publish: ((WorkspaceState) -> WorkspaceState) -> Unit = { command -> publications++; current = command(current) }
            val first = withContext(ui) { worker.submit(source, listOf(InkSample(1f, 1f)), emptyMap(), publish) }
            withTimeout(5_000) { entered.await() }
            try {
                val latest = withContext(ui) {
                    worker.submit(source, listOf(InkSample(2f, 1f)), emptyMap(), publish)
                    worker.submit(source, listOf(InkSample(3f, 1f)), emptyMap(), publish)
                }
                withContext(compute) { }
                assertEquals(listOf(1), calls.toList(), "queued obsolete geometry must not start")
                release.complete(Unit)
                withTimeout(5_000) { latest.join(); first.join() }
                withContext(ui) {
                    assertEquals(listOf(1, 3), calls.toList())
                    assertEquals(1, maximum.get())
                    assertEquals(1, publications)
                    assertEquals(setOf("3"), current.selectedObjectIds)
                }
            } finally { release.complete(Unit) }
        }
    }

    @Test
    fun independentUiEditsAndDelayedPublicationCannotBeOverwritten() = runBlocking {
        val source = WorkspaceState.demo()
        withWorker({ state, _, _ -> state.copy(selectedObjectIds = setOf("prepared")) }) { worker, ui, _ ->
            val publication = CompletableDeferred<(WorkspaceState) -> WorkspaceState>()
            val task = withContext(ui) { worker.submit(source, loop, emptyMap()) { publication.complete(it) } }
            task.join()
            val command = publication.await()
            withContext(ui) {
                val edited = source.updateSelectedPage(title = "Typed while selecting")
                assertSame(edited, command(edited), "strict source identity protects all independent state")
                worker.cancel()
                assertSame(source, command(source), "a queued publisher must also honor invalidation")
            }
        }
    }

    @Test
    fun pageToolZoomDensityAndDisposalInvalidateEvenUncancellablePreparation() = runBlocking {
        val context = CanvasSelectionContext("page", true, false, false, null, null, 0, 1f, true)
        val variants = listOf(context.copy(pageId = "other"), context.copy(lasso = false), context.copy(zoom = 2f),
            context.copy(density = 2f), context.copy(fontScale = 1.5f), null)
        for (next in variants) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withWorker({ state, _, _ ->
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                state.copy(selectedObjectIds = setOf("obsolete"))
            }) { worker, ui, _ ->
                val publications = AtomicInteger()
                val task = withContext(ui) {
                    worker.updateContext(context)
                    worker.submit(WorkspaceState.demo(), loop, emptyMap()) { publications.incrementAndGet() }
                }
                withTimeout(5_000) { entered.await() }
                try {
                    withContext(ui) { if (next == null) worker.close() else worker.updateContext(next) }
                    release.complete(Unit)
                    withTimeout(5_000) { task.join() }
                    assertEquals(0, publications.get(), "context=$next")
                } finally { release.complete(Unit) }
            }
        }
    }

    @Test
    fun smallSelectionStaysSynchronousAndTheTwoLargeWorkThresholdsAreIndependent() {
        val small = WorkspaceState.demo()
        assertFalse(small.preparesCanvasLassoOffThread(255))
        assertTrue(small.preparesCanvasLassoOffThread(256))
        assertTrue(fixture().preparesCanvasLassoOffThread(4))
    }

    private suspend fun withWorker(prepare: suspend (WorkspaceState, List<InkSample>, Map<String, Float>) -> WorkspaceState,
        block: suspend (CanvasSelectionWorker, CoroutineDispatcher, CoroutineDispatcher) -> Unit) {
        Executors.newSingleThreadExecutor { Thread(it, "lasso-ui") }.asCoroutineDispatcher().use { ui ->
            Executors.newFixedThreadPool(2) { Thread(it, "lasso-native") }.asCoroutineDispatcher().use { compute ->
                val scope = CoroutineScope(SupervisorJob() + ui)
                val worker = CanvasSelectionWorker(scope, compute, prepare)
                try { block(worker, ui, compute) }
                finally { withContext(NonCancellable + ui) { worker.close(); scope.cancel() } }
            }
        }
    }

    private fun fixture(): WorkspaceState = WorkspaceState.demo().let { base ->
        base.updatePage(base.selectedPageId) { page -> page.copy(document = page.document.copy(outlines =
            listOf(Outline.Shape("target", x = 300f, y = 350f, width = 80f, height = 80f)) +
            List(128) { Outline.Shape("far-$it", x = 2000f + it * 20f, y = 350f, width = 10f, height = 10f) })) }
    }

    private val loop = listOf(InkSample(250f, 320f), InkSample(450f, 320f), InkSample(450f, 450f),
        InkSample(250f, 450f), InkSample(250f, 320f))
}
