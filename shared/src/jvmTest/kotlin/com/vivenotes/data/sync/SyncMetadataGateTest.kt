package com.vivenotes.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncMetadataGateTest {
    @Test
    fun aQueuedPipelineCannotRunAfterItsSourceInvalidatesTheActiveTransfer() = runBlocking {
        val gate = SyncMetadataGate()
        val pipeline = Mutex()
        val entered = CompletableDeferred<Unit>()
        val bytes = CompletableDeferred<Unit>()
        var staleBlockRan = false
        val active = async {
            gate.withTransferPipeline(pipeline) {
                gate.transfer { entered.complete(Unit); bytes.await() }
            }
        }
        entered.await()
        // The free metadata lease is captured immediately; this coroutine then suspends on the
        // pipeline that the active transfer still owns.
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withTransferPipeline(pipeline) { staleBlockRan = true }
        }
        gate.locked { gate.invalidate() }
        withTimeout(5_000) { active.join(); queued.join() }
        assertTrue(active.isCancelled)
        assertTrue(queued.isCancelled)
        assertFalse(staleBlockRan)
        assertFalse(bytes.isCompleted)
        assertFalse(pipeline.isLocked)
        assertEquals(17, gate.withTransferPipeline(pipeline) { 17 })
    }

    @Test
    fun currentSourcePipelineWaitersKeepTheirOrderAndMetadataLease() = runBlocking {
        val gate = SyncMetadataGate()
        val pipeline = Mutex()
        pipeline.lock()
        val order = ArrayList<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withTransferPipeline(pipeline) { order += 1; gate.transfer { 21 } }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withTransferPipeline(pipeline) { order += 2; 22 }
        }
        assertEquals(5, gate.locked { 5 }, "Waiting for the pipeline cannot occupy the metadata lease")
        pipeline.unlock()
        assertEquals(21, withTimeout(5_000) { first.await() })
        assertEquals(22, withTimeout(5_000) { second.await() })
        assertEquals(listOf(1, 2), order)
        assertFalse(pipeline.isLocked)
    }

    @Test
    fun cancellingAQueuedPipelineLeavesBothLocksAvailable() = runBlocking {
        val gate = SyncMetadataGate()
        val pipeline = Mutex()
        pipeline.lock()
        var blockRan = false
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withTransferPipeline(pipeline) { blockRan = true }
        }
        withTimeout(5_000) { queued.cancelAndJoin() }
        assertFalse(blockRan)
        assertEquals(8, gate.locked { 8 })
        pipeline.unlock()
        assertEquals(9, gate.withTransferPipeline(pipeline) { 9 })
        assertFalse(pipeline.isLocked)
    }

    @Test
    fun cancellingAnActiveTransferLetsTheCurrentSourcePipelineWaiterContinue() = runBlocking {
        val gate = SyncMetadataGate()
        val pipeline = Mutex()
        val entered = CompletableDeferred<Unit>()
        val bytes = CompletableDeferred<Unit>()
        val active = async {
            gate.withTransferPipeline(pipeline) { gate.transfer { entered.complete(Unit); bytes.await() } }
        }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withTransferPipeline(pipeline) { 31 }
        }
        withTimeout(5_000) { active.cancelAndJoin() }
        assertEquals(31, withTimeout(5_000) { queued.await() })
        assertFalse(bytes.isCompleted)
        assertFalse(pipeline.isLocked)
        assertEquals(32, gate.locked { 32 })
    }

    @Test
    fun metadataContinuesWhileBytesWaitAndCancellationReturnsTheLease() = runBlocking {
        val gate = SyncMetadataGate()
        val entered = CompletableDeferred<Unit>()
        val bytes = CompletableDeferred<Unit>()
        val transferring = async {
            gate.locked { gate.transfer { entered.complete(Unit); bytes.await() } }
        }
        entered.await()
        assertEquals(42, withTimeout(5_000) { gate.locked { 42 } })
        withTimeout(5_000) { transferring.cancelAndJoin() }
        assertEquals(43, withTimeout(5_000) { gate.locked { 43 } })
        assertFalse(bytes.isCompleted)
    }

    @Test
    fun sourceInvalidationCancelsTheTransferWithoutWaitingForBytes() = runBlocking {
        val gate = SyncMetadataGate()
        val entered = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        var committed = false
        val transferring = async {
            gate.locked {
                gate.transfer { entered.complete(Unit); waiting.await() }
                committed = true
            }
        }
        entered.await()
        gate.locked { gate.invalidate() }
        withTimeout(5_000) { transferring.join() }
        assertTrue(transferring.isCancelled)
        assertFalse(committed)
        assertEquals(7, gate.locked { 7 })
    }

    @Test
    fun evenAnUncancellableOldSourceCannotCommitAfterSwitchingBack() = runBlocking {
        val gate = SyncMetadataGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var committed = false
        val transferring = async {
            gate.locked {
                gate.transfer { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
                committed = true
            }
        }
        entered.await()
        gate.locked { gate.invalidate(); gate.invalidate() }
        release.complete(Unit)
        withTimeout(5_000) { transferring.join() }
        assertTrue(transferring.isCancelled)
        assertFalse(committed)
    }
}
