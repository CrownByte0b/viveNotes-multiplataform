package com.vivenotes.data.sync

import com.vivenotes.diagnostics.DebugLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Android sync engine's credential shape, without Android DataStore. */
data class SyncAccount(val serverUrl: String, val accountId: String, val deviceId: String, val token: String)

/** Sink for ported sync diagnostics; respects the desktop's opt-in debug switch. */
class SyncDebugLog(private val debug: DebugLog = DebugLog()) {
    fun i(tag: String, message: String) = debug.event("sync") { "$tag: $message" }
    fun w(tag: String, message: String) = debug.event("sync") { "$tag: $message" }
    fun e(tag: String, message: String) = debug.event("sync") { "$tag: $message" }
    fun e(tag: String, message: String, failure: Throwable) = debug.failure("sync", "$tag: $message", failure)
}

class DesktopAttachmentBytes(private val directory: File) : AttachmentBytes {
    private val mutableArrivals = MutableStateFlow(0L)
    override val arrivals: StateFlow<Long> = mutableArrivals
    override fun fileFor(id: String): File = File(directory, id)
    override fun stagingFor(id: String): File = File(directory, "$id.part")
    override fun publish(staged: File, id: String): Boolean = runCatching {
        directory.mkdirs()
        if (fileFor(id).exists()) {
            staged.delete()
            return@runCatching true
        }
        Files.move(staged.toPath(), fileFor(id).toPath(), StandardCopyOption.REPLACE_EXISTING)
        mutableArrivals.value++
        true
    }.getOrDefault(false)
}
