package com.vivenotes.data.sync

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serial metadata commits with an explicitly suspended lease during independent byte transfers. */
internal class SyncMetadataGate {
    private val mutex = Mutex()
    private var generation = 0L
    private val transfers = LinkedHashSet<Job>()

    private class Lease(val gate: SyncMetadataGate) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }

    suspend fun <T> locked(block: suspend () -> T): T {
        val lease = Lease(this)
        mutex.lock(lease)
        try {
            return withContext(lease) { block() }
        } finally {
            mutex.unlock(lease)
        }
    }

    suspend fun <T> withTransferPipeline(pipeline: Mutex, block: suspend () -> T): T {
        val expected = locked { generation }
        return pipeline.withLock {
            locked {
                if (generation != expected) throw CancellationException("Sync account or source changed while queued")
                block()
            }
        }
    }

    /** Called with the metadata lease held, whenever its account or source changes. */
    fun invalidate() {
        generation++
        transfers.forEach { it.cancel(CancellationException("Sync account or source changed")) }
    }

    suspend fun <T> transfer(block: suspend () -> T): T = coroutineScope {
        val lease = currentCoroutineContext()[Lease]
        check(lease?.gate === this@SyncMetadataGate) { "A byte transfer requires its metadata lease" }
        val expected = generation
        val job = currentCoroutineContext().job
        transfers += job
        mutex.unlock(lease)
        val result = try {
            block()
        } finally {
            // The outer finally must own the lease even when the transfer was cancelled.
            withContext(NonCancellable) { mutex.lock(lease) }
            transfers -= job
        }
        if (generation != expected) throw CancellationException("Sync account or source changed")
        result
    }
}
