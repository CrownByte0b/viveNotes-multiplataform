package com.vivenotes.data

/** Derived YouTube preview data. A null result leaves the original URL readable. */
interface VideoThumbnailSource {
    suspend fun load(videoId: String): ByteArray?
}
