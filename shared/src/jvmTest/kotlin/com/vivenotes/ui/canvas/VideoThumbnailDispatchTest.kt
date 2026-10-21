package com.vivenotes.ui.canvas

import androidx.compose.ui.graphics.ImageBitmap
import com.vivenotes.data.VideoThumbnailSource
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class VideoThumbnailDispatchTest {
    @Test fun retrievalAndDecodeLeaveTheUiCallerAndPublicationReturnsToIt() = runBlocking<Unit> {
        Executors.newSingleThreadExecutor { Thread(it, "thumbnail-ui-test") }.asCoroutineDispatcher().use { ui ->
            withContext(ui) {
                val encoded = byteArrayOf(1, 2, 3)
                val image = ImageBitmap(2, 2)
                var loadThread = ""
                var decodeThread = ""
                val source = object : VideoThumbnailSource {
                    override suspend fun load(videoId: String): ByteArray {
                        assertEquals("id", videoId)
                        loadThread = Thread.currentThread().name.substringBefore(" @")
                        return encoded
                    }
                }
                assertSame(image, loadVideoThumbnail(source, "id") {
                    assertSame(encoded, it)
                    decodeThread = Thread.currentThread().name.substringBefore(" @")
                    image
                })
                assertNotEquals("thumbnail-ui-test", loadThread)
                assertNotEquals("thumbnail-ui-test", decodeThread)
                assertEquals("thumbnail-ui-test", Thread.currentThread().name.substringBefore(" @"))
            }
        }
    }

    @Test fun missingAndCorruptImagesStayTextButCancellationPropagates() = runBlocking<Unit> {
        val missing = object : VideoThumbnailSource { override suspend fun load(videoId: String): ByteArray? = null }
        assertNull(loadVideoThumbnail(missing, "missing") { error("must not decode absent bytes") })
        val source = object : VideoThumbnailSource { override suspend fun load(videoId: String) = byteArrayOf(1) }
        assertNull(loadVideoThumbnail(source, "corrupt") { error("bad image") })
        assertFailsWith<CancellationException> {
            loadVideoThumbnail(source, "cancelled") { throw CancellationException("cancelled decode") }
        }
    }
}
