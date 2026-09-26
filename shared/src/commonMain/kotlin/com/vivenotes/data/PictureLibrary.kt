package com.vivenotes.data

/** A stored picture, as Android's `AttachmentStore.import` reports it: the id and its pixel size. */
data class ImportedPicture(val attachmentId: String, val pixelWidth: Int, val pixelHeight: Int)

/**
 * Pictures kept outside the document, the way Android keeps them: the page holds an
 * `Outline.Image` naming an attachment, and the bytes live in the attachment store.
 *
 * The desktop implements it over the stored files and the platform's file dialog; tests fake it.
 */
interface PictureLibrary {

    /** Lets the user choose a picture and stores it. Null when they cancel or it is not a picture. */
    suspend fun choose(): ImportedPicture?

    /** The stored bytes of [attachmentId], or null when there is no such file. */
    suspend fun bytes(attachmentId: String): ByteArray?
}
