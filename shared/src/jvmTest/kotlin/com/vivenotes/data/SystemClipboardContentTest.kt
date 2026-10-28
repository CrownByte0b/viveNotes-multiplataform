package com.vivenotes.data

import com.sun.net.httpserver.HttpServer
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Outline
import com.vivenotes.model.decodePageDoc
import com.vivenotes.model.encode
import com.vivenotes.richtext.CanvasPasteItem
import com.vivenotes.richtext.canvasPasteItems
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemClipboardContentTest {
    @Test
    fun readsUnicodeHtmlOfferedAsAStreamWithoutAnyPlainText() = runBlocking<Unit> {
        val board = Clipboard("browser")
        val flavor = DataFlavor("text/html;class=java.io.InputStream;charset=UTF-8")
        val html = "<table><tr><th>Café</th></tr><tr><td>日本 😀</td></tr></table>"
        board.setContents(transfer(flavor) { ByteArrayInputStream(html.toByteArray()) }, null)
        val reader = SystemClipboardContent(clipboard = { board })
        assertTrue(reader.hasContent())
        val content = reader.readContent()
        assertNull(content.text)
        assertEquals(html, content.html)
        val table = assertIs<CanvasPasteItem.Table>(canvasPasteItems(content).single()).outline
        val state = WorkspaceState.demo()
        val pasted = state.insertClipboardOutlines(state.selectedPageId, listOf(table), 40f, 200f)
        val document = pasted.selectedPage!!.document
        assertEquals(document, decodePageDoc(document.encode()))
        assertEquals("日本 😀", document.outlines.filterIsInstance<Outline.Table>().single().cellAt(1, 0)!!.plainText)
    }

    @Test
    fun nativeClipboardPicturesUseTheRealAttachmentStore() = runBlocking<Unit> {
        val board = Clipboard("image")
        board.setContents(transfer(DataFlavor.imageFlavor) { BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB) }, null)
        val reader = SystemClipboardContent(clipboard = { board })
        assertTrue(reader.hasContent())
        val bytes = assertNotNull(reader.readContent().image)
        assertEquals(40, ImageIO.read(ByteArrayInputStream(bytes)).width)
        val root = Files.createTempDirectory("clipboard-picture").toFile()
        val db = NotesDatabase.create(root.resolve(NotesDatabase.FILE_NAME))
        try {
            val store = AttachmentStore(root.resolve("attachments"), db)
            val picture = assertNotNull(store.import(bytes))
            assertEquals(40 to 20, picture.pixelWidth to picture.pixelHeight)
            assertNotNull(store.bytes(picture.attachmentId))
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test
    fun anotherAppsCopyReplacesCanvasOwnership() = runBlocking<Unit> {
        val board = Clipboard("ownership")
        val reader = SystemClipboardContent(clipboard = { board })
        reader.markInternalCopy()
        assertTrue(reader.ownsInternalCopy())
        val previousCopy = board.getContents(null)
        reader.markInternalCopy()
        board.setContents(previousCopy, null)
        assertFalse(reader.ownsInternalCopy(), "a clipboard history token must not paste a different, newer object copy")
        assertFalse(reader.hasContent())
        board.setContents(StringSelection("external text"), null)
        assertFalse(reader.ownsInternalCopy())
        assertEquals("external text", reader.readContent().text)
        reader.markInternalCopy()
        assertTrue(reader.ownsInternalCopy())
    }

    @Test
    fun browserUrlFlavorsAreReadWithoutTheirTitleOrComments() = runBlocking<Unit> {
        for ((mime, value) in listOf("text/x-moz-url" to "https://example.org/image\nPicture title",
            "text/uri-list" to "# comment\r\nhttps://example.org/image\r\n")) {
            val board = Clipboard("url")
            board.setContents(transfer(DataFlavor("$mime;class=java.lang.String")) { value }, null)
            assertEquals("https://example.org/image", SystemClipboardContent(clipboard = { board }).readContent().imageUrl)
        }
    }

    @Test
    fun plainTextOfferedAsAReaderCanBePasted() = runBlocking<Unit> {
        val board = Clipboard("plain-text-reader")
        val flavor = DataFlavor("text/plain;class=java.io.Reader;charset=UTF-8")
        board.setContents(transfer(flavor) { "Café 日本 😀".reader() }, null)
        val reader = SystemClipboardContent(clipboard = { board })
        assertTrue(reader.hasContent())
        assertEquals("Café 日本 😀", reader.readContent().text)
    }

    @Test
    fun downloadsPicturesAndRejectsPagesErrorsAndUnsafeSchemes() = runBlocking<Unit> {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/picture") { response ->
            response.responseHeaders.set("Content-Type", "image/png")
            response.sendResponseHeaders(200, bytes.size.toLong())
            response.responseBody.use { it.write(bytes) }
        }
        server.createContext("/page") { response ->
            response.responseHeaders.set("Content-Type", "text/html")
            response.sendResponseHeaders(200, 4)
            response.responseBody.use { it.write("page".toByteArray()) }
        }
        server.createContext("/error") { response -> response.sendResponseHeaders(404, -1); response.close() }
        server.start()
        try {
            val reader = SystemClipboardContent()
            val base = "http://127.0.0.1:${server.address.port}"
            assertContentEquals(bytes, reader.imageBytes("$base/picture"))
            assertNull(reader.imageBytes("$base/page"))
            assertNull(reader.imageBytes("$base/error"))
            assertNull(reader.imageBytes("file:///etc/passwd"))
            assertNull(reader.imageBytes("javascript:alert(1)"))
            assertContentEquals(bytes, reader.imageBytes("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)))
            assertNull(reader.imageBytes("data:image/png;base64,bad!"))
        } finally { server.stop(0) }
    }

    @Test
    fun unavailableOwnersAndOverlongPayloadsAreHandledWithoutLoggingContent() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        val board = Clipboard("broken")
        val reader = SystemClipboardContent(DebugLog(true) { lines += it }, clipboard = { board })
        board.setContents(transfer(DataFlavor.stringFlavor) { error("private copied text") }, null)
        assertNull(reader.readContent().text)
        assertTrue(lines.none { "private copied text" in it })
        board.setContents(StringSelection("x".repeat(4 * 1024 * 1024 + 1)), null)
        assertNull(reader.readContent().text)
        assertFalse(SystemClipboardContent(clipboard = { null }).hasContent())
        assertNull(SystemClipboardContent(clipboard = { null }).readContent().image)
    }

    private fun transfer(flavor: DataFlavor, value: () -> Any): Transferable = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(flavor)
        override fun isDataFlavorSupported(requested: DataFlavor?) = requested == flavor
        override fun getTransferData(requested: DataFlavor?): Any =
            if (requested == flavor) value() else throw UnsupportedFlavorException(requested)
    }
}
