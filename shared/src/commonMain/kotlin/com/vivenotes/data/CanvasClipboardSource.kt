package com.vivenotes.data

data class ClipboardContent(
    val text: String? = null,
    val html: String? = null,
    val image: ByteArray? = null,
    val imageUrl: String? = null,
)

/** Desktop clipboard access, including browser HTML and native copied pictures. */
interface CanvasClipboardSource {
    fun hasContent(): Boolean
    suspend fun readContent(): ClipboardContent
    suspend fun imageBytes(address: String): ByteArray?

    /** Claims the system clipboard for an in-app object copy, without exposing document contents. */
    fun markInternalCopy()
    fun ownsInternalCopy(): Boolean
}
