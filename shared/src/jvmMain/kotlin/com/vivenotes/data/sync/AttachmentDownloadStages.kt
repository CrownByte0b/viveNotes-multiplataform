package com.vivenotes.data.sync

import com.vivenotes.data.Log
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Sidecar leases distinguish crashed downloads from workers in this or another app process. */
internal class AttachmentDownloadStages {
    private val inspected = HashSet<String>()

    fun reconcile() = synchronized(registryLock) { inspected.clear() }

    fun create(base: File, digest: String): Stage {
        val directory = File(base.parentFile, DIRECTORY)
        return withRegistry(directory) {
            if (inspected.add(directory.absolutePath)) removeAbandoned(directory)
            val file = File(directory, "$digest-${UUID.randomUUID()}.part")
            val sidecar = File(file.path + ".lock")
            val channel = FileChannel.open(sidecar.toPath(), CREATE_NEW, WRITE)
            try {
                val lease = channel.lock()
                try {
                    check(file.createNewFile()) { "Download stage already exists" }
                    Stage(file) {
                        try {
                            withRegistry(directory) {
                                val removed = !file.exists() || file.delete()
                                lease.release()
                                channel.close()
                                if (removed) sidecar.delete()
                            }
                        } catch (failure: Exception) {
                            // A failed cleanup remains a leased-orphan candidate on the next repair.
                            Log.w(TAG, "Attachment download stage cleanup deferred", failure)
                        } finally {
                            runCatching { lease.release() }
                            runCatching { channel.close() }
                        }
                    }
                } catch (failure: Throwable) {
                    lease.release()
                    throw failure
                }
            } catch (failure: Throwable) {
                channel.close()
                sidecar.delete()
                throw failure
            }
        }
    }

    class Stage internal constructor(val file: File, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    private fun removeAbandoned(directory: File) {
        directory.listFiles().orEmpty().filter { it.name.matches(STAGE_LOCK) }.forEach { sidecar ->
            runCatching {
                FileChannel.open(sidecar.toPath(), WRITE).use { channel ->
                    val lease = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lease != null) {
                        val removed = lease.use {
                            val file = File(sidecar.path.removeSuffix(".lock"))
                            !file.exists() || file.delete()
                        }
                        // Registry ownership prevents another cleanup from opening this sidecar.
                        channel.close()
                        if (removed) sidecar.delete()
                    }
                }
            }.onFailure { Log.w(TAG, "Abandoned attachment stage cleanup deferred", it) }
        }
    }

    private companion object {
        const val TAG = "AttachmentDownloadStages"
        const val DIRECTORY = ".sync-staging"
        val STAGE_LOCK = Regex("[0-9a-f]{64}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part\\.lock")
        val registryLock = Any()

        fun <T> withRegistry(directory: File, block: () -> T): T = synchronized(registryLock) {
            directory.mkdirs()
            // This permanent small registry file is never unlinked while another process may use it.
            FileChannel.open(File(directory, ".registry.lock").toPath(), CREATE, WRITE).use { registry ->
                registry.lock().use { block() }
            }
        }
    }
}
