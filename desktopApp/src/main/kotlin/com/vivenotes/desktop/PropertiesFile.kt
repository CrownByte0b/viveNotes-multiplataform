package com.vivenotes.desktop

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** The file's properties, or null when it is missing or cannot be read. */
internal fun File.readProperties(): Properties? {
    if (!isFile) return null
    return runCatching { Properties().apply { inputStream().use(::load) } }.getOrNull()
}

/**
 * Replaces the file with [properties], atomically where the filesystem allows, so a crash mid-write
 * leaves the previous preferences rather than half a file.
 */
internal fun File.writePropertiesAtomically(properties: Properties, comment: String) {
    parentFile.mkdirs()
    val temporary = File.createTempFile("$nameWithoutExtension-", ".tmp", parentFile)
    try {
        temporary.outputStream().use { properties.store(it, comment) }
        try {
            Files.move(temporary.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}
