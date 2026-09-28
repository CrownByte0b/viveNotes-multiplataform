package com.vivenotes.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopVideoThumbnailsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun validatedIdUsesYouTubeFallbackAndDiskCache() = runBlocking {
        val calls = mutableListOf<String>()
        val root = temp.newFolder("thumbs")
        val id = "dQw4w9WgXcQ"
        val source = DesktopVideoThumbnails(root) { address ->
            calls += address
            byteArrayOf(1, 2, 3).takeIf { address.endsWith("mqdefault.jpg") }
        }
        assertEquals(listOf<Byte>(1, 2, 3), source.load(id)?.toList())
        assertEquals(2, calls.size)
        assertEquals(listOf<Byte>(1, 2, 3), DesktopVideoThumbnails(root) { error("network used") }.load(id)?.toList())
        assertNull(source.load("../../etc"))
        assertEquals(2, calls.size)
    }

    @Test fun aFailedFetchIsNotRepeatedForEveryTextEdit() = runBlocking {
        var calls = 0
        val source = DesktopVideoThumbnails(temp.newFolder("missing")) { calls++; null }
        repeat(3) { assertNull(source.load("dQw4w9WgXcQ")) }
        assertEquals(2, calls)
    }
}
