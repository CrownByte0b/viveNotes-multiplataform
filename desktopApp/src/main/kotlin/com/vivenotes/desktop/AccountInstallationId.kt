package com.vivenotes.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Non-secret, stable installation identity for POST /v1/devices token rotation. */
internal fun accountInstallationId(file: File): String {
    val saved = file.takeIf(File::isFile)?.readText()?.trim()
    if (saved != null && runCatching { UUID.fromString(saved) }.isSuccess) return saved
    file.parentFile.mkdirs()
    val id = UUID.randomUUID().toString()
    val staged = File.createTempFile("account-installation-id-", ".tmp", file.parentFile)
    try {
        staged.writeText(id)
        Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staged.delete()
    }
    return id
}
