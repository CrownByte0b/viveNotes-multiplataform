package com.vivenotes.data.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAttachmentBytesTest {
    @Test
    fun publishingAStagedBlobNeverReplacesAnExistingDigest() {
        val directory = Files.createTempDirectory("attachment-bytes").toFile()
        try {
            val bytes = DesktopAttachmentBytes(directory)
            val existing = bytes.fileFor("digest")
            existing.writeBytes(byteArrayOf(1, 2, 3))
            val staged = bytes.stagingFor("digest")
            staged.writeBytes(byteArrayOf(9))

            assertTrue(bytes.publish(staged, "digest"))

            assertContentEquals(byteArrayOf(1, 2, 3), existing.readBytes())
            assertFalse(staged.exists())
            assertEquals(0L, bytes.arrivals.value)
        } finally { directory.deleteRecursively() }
    }
}
