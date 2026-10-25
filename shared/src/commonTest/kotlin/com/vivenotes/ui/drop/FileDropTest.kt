package com.vivenotes.ui.drop

import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sharing dropped files out among the page's handlers, and the picture handler's part. */
class FileDropTest {

    @Test
    fun eachFileGoesToTheFirstHandlerThatTakesItInTheOrderDropped() {
        val pictures = Kind("png", "jpg")
        val documents = Kind("pdf", "png")
        val routing = listOf(pictures, documents).route(files("a.png", "b.pdf", "c.txt", "d.jpg", "e.PNG"))

        assertEquals(listOf(pictures to listOf("a.png", "d.jpg", "e.PNG"), documents to listOf("b.pdf")),
            routing.deliveries.map { (handler, mine) -> handler to mine.map { it.name } })
        assertEquals(listOf("c.txt"), routing.refused.map { it.name })
        assertTrue(routing.accepted)
    }

    @Test
    fun aDropNoHandlerTakesIsRefused() {
        val routing = listOf(Kind("png")).route(files("notes.txt", "archive"))
        assertFalse(routing.accepted)
        assertEquals(2, routing.refused.size)
        assertFalse(emptyList<FileDropHandler>().route(files("a.png")).accepted, "a page without handlers takes nothing")
    }

    @Test
    fun anExtensionIsWhatFollowsTheLastDotInLowerCase() {
        assertEquals(listOf("jpeg", "gz", "", "bashrc", ""),
            files("Photo Two.JPEG", "notes.tar.gz", "README", ".bashrc", "trailing.").map { it.extension })
    }

    @Test
    fun picturesAreTakenByTheirFileExtensionsAlone() {
        val drop = PictureDrop(Pictures(), place = { _, _ -> })
        assertEquals(listOf(true, true, true, true, true, true, false, false, false),
            files("a.png", "b.JPG", "c.jpeg", "d.webp", "e.gif", "f.bmp", "g.heic", "h.svg", "png").map(drop::accepts))
    }

    @Test
    fun droppedPicturesAreStoredThenPlacedTogetherAtTheDropPoint() = runTest {
        val library = Pictures()
        val placed = mutableListOf<Pair<DropPoint, List<ImportedPicture>>>()
        val drop = PictureDrop(library, place = { at, pictures -> placed += at to pictures })
        val at = DropPoint("page-1", 40f, 300f)

        drop.receive(listOf(file("one.png", byteArrayOf(1)), file("two.jpg", byteArrayOf(2))), at)

        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), library.imported.map { it.toList() })
        assertEquals(listOf(at to listOf(library.stored(1), library.stored(2))), placed, "one placement, so one undo step")
    }

    @Test
    fun aFileThatCannotBeReadOrIsNoPictureIsLeftOutAndTheRestStillGoIn() = runTest {
        val library = Pictures(notPictures = setOf<Byte>(3))
        val placed = mutableListOf<List<ImportedPicture>>()
        val lines = mutableListOf<String>()
        val drop = PictureDrop(library, place = { _, pictures -> placed += pictures }, log = DebugLog(true) { lines += it })

        drop.receive(listOf(file("gone.png", null), file("fake.png", byteArrayOf(3)), file("real.png", byteArrayOf(4))),
            DropPoint("page-1", 0f, 0f))

        assertEquals(listOf(listOf(library.stored(4))), placed)
        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines.last().endsWith("inserted 1 of 3 dropped pictures"), lines.last())
        assertTrue(lines.none { "gone" in it || "fake" in it || "real" in it }, "file names stay out of the log: $lines")
    }

    @Test
    fun nothingIsPlacedWhenNoDroppedPictureCouldBeStored() = runTest {
        var placements = 0
        PictureDrop(Pictures(), place = { _, _ -> placements++ }).receive(listOf(file("gone.png", null)), DropPoint("p", 0f, 0f))
        assertEquals(0, placements)
    }

    @Test
    fun aPictureIsReadWithinItsSizeLimit() = runTest {
        var limit = 0L
        val drop = PictureDrop(Pictures(), place = { _, _ -> })
        drop.receive(listOf(DroppedFile("big.png") { maxBytes -> limit = maxBytes; null }), DropPoint("p", 0f, 0f))
        assertEquals(PictureDrop.MAX_PICTURE_BYTES, limit)
    }

    private fun file(name: String, bytes: ByteArray?) = DroppedFile(name) { bytes }

    private fun files(vararg names: String) = names.map { file(it, ByteArray(0)) }

    /** A handler for some extensions that records nothing: routing is all that is under test. */
    private class Kind(vararg val extensions: String) : FileDropHandler {
        override fun accepts(file: DroppedFile) = file.extension in extensions
        override suspend fun receive(files: List<DroppedFile>, at: DropPoint) = Unit
        override fun toString() = "Kind(${extensions.joinToString()})"
    }

    /** Stores each picture under a name made from its first byte; bytes in [notPictures] are not pictures. */
    private class Pictures(private val notPictures: Set<Byte> = emptySet()) : PictureLibrary {
        val imported = mutableListOf<ByteArray>()
        fun stored(first: Byte) = ImportedPicture(first.toString().padStart(64, '0'), 10, 20)
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray): ImportedPicture? {
            imported += bytes
            return bytes.first().takeIf { it !in notPictures }?.let(::stored)
        }
        override suspend fun bytes(attachmentId: String): ByteArray? = null
    }
}
