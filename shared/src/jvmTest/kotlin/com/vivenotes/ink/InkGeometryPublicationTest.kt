package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.InkEdit
import com.vivenotes.model.ink.InkPage
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.applyPreparedInkGeometry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.*

internal fun reclamationFixture(pageId: String = "page"): Pair<InkPage, InkPage> {
    val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 6f)
    val stroke = Stroke(tool.brush, MutableStrokeInputBatch().apply {
        add(InputToolType.MOUSE, 10f, 50f, 0L); add(InputToolType.MOUSE, 110f, 50f, 100L)
    })
    val template = tool.complete(stroke, "template", pageId, 0, 1L).row.toEntity()
    val before = InkPageReader.read(pageId, List(400) { template.copy(id = "row-$it", seq = it) }, emptyList(), emptyList())
    val removed = before.withDesktopEdit(InkEdit.EraseStrokes((0 until 256).mapTo(HashSet()) { "row-$it" }))
    return before to removed
}

class InkGeometryPublicationTest {
    @Test
    fun workerPreparationPublishesOnlyGeometryAndPreservesPendingHistoryAndUndo() = runBlocking {
        val base = WorkspaceState.demo().toggleInkTool(InkTool.Pen)
        val (original, _) = reclamationFixture(base.selectedPageId)
        val state = base.updatePage(base.selectedPageId) { it.copy(ink = original) }
        val edit = InkEdit.EraseStrokes((0 until 256).mapTo(HashSet()) { "row-$it" })
        val rendered = original.withDesktopEdit(edit)
        val deleted = state.applyInkEdit(edit, rendered)
        val source = assertNotNull(deleted.selectedPage!!.ink)
        assertTrue(source.desktopGeometry().needsProjectionReclamation)
        val callingThread = Thread.currentThread().name
        val prepared = DesktopErasePreparer().reclaim(source)
        assertNotNull(source.desktopGeometry().projectionReclamationThread)
        assertNotEquals(callingThread, source.desktopGeometry().projectionReclamationThread)
        assertEquals(1, source.desktopGeometry().projectionReclamationBuildCount)
        assertEquals(0, source.desktopGeometry().basePartitionBuildCount)
        assertEquals(144, prepared.desktopGeometry().segments().sumOf { it.projections.size })
        assertEquals(144, prepared.desktopGeometry().base.projections.size)
        assertTrue(prepared.desktopGeometry().retainedSegments.isEmpty())
        val after = deleted.applyPreparedInkGeometry(source, prepared)
        assertSame(prepared, after.selectedPage!!.ink)
        assertSame(deleted.selectedPage!!.document, after.selectedPage!!.document)
        assertSame(deleted.pendingInkEdits, after.pendingInkEdits)
        assertSame(deleted.structuralUndo, after.structuralUndo)
        assertSame(deleted.structuralRedo, after.structuralRedo)
        assertSame(source.strokes, prepared.strokes)
        assertSame(source.operations, prepared.operations)
        assertEquals(source.latestOperationAt, prepared.latestOperationAt)
        val undone = after.undoStructure()
        assertEquals(400, undone.selectedPage!!.ink!!.desktopGeometry().projections.size)
        assertEquals(144, after.selectedPage!!.ink!!.desktopGeometry().projections.size)
        assertEquals(144, source.desktopGeometry().projections.size)
        assertEquals(400, source.desktopGeometry().base.projections.size, "the held old snapshot was not mutated")
    }

    @Test
    fun staleSourceReloadUndoAndChangedLogicalInputsCannotPublish() = runBlocking {
        val base = WorkspaceState.demo()
        val (original, source) = reclamationFixture(base.selectedPageId)
        val state = base.updatePage(base.selectedPageId) { it.copy(ink = source) }
        val prepared = DesktopErasePreparer().reclaim(source)
        val reloaded = state.updatePage(state.selectedPageId) { it.copy(ink = source.copy()) }
        assertSame(reloaded, reloaded.applyPreparedInkGeometry(source, prepared))
        val restored = state.updatePage(state.selectedPageId) { it.copy(ink = original) }
        assertSame(restored, restored.applyPreparedInkGeometry(source, prepared))
        for (wrong in listOf(prepared.copy(pageId = "another"), prepared.copy(strokes = prepared.strokes.toMutableList()),
            prepared.copy(operations = prepared.operations.toMutableList()), prepared.copy(latestOperationAt = 100L),
            prepared.copy(geometry = null))) {
            assertSame(state, state.applyPreparedInkGeometry(source, wrong))
        }
        val pendingAcknowledged = state.copy(pendingInkEdits = state.pendingInkEdits.toMutableList())
        val accepted = pendingAcknowledged.applyPreparedInkGeometry(source, prepared)
        assertSame(pendingAcknowledged.pendingInkEdits, accepted.pendingInkEdits, "a storage acknowledgement is never overwritten")
        assertSame(pendingAcknowledged.structuralUndo, accepted.structuralUndo)
    }

    @Test
    fun queuedCancelledNativePreparationDoesNotBuildOrPublish() = runBlocking {
        val (_, source) = reclamationFixture()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ink-reclaim-test") }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            executor.submit { entered.countDown(); release.await() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            var published = false
            val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(start = CoroutineStart.UNDISPATCHED) {
                DesktopErasePreparer(dispatcher).reclaim(source)
                published = true
            }
            job.cancel()
            release.countDown()
            job.join()
            assertFalse(published)
            assertEquals(0, source.desktopGeometry().projectionReclamationBuildCount)
            assertNull(source.desktopGeometry().projectionReclamationThread)
            assertEquals(0, source.desktopGeometry().basePartitionBuildCount)
        }
    }
}
