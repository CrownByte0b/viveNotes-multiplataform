package com.vivenotes.data

import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class SystemClipboardContent(
    private val log: DebugLog = DebugLog(),
    private val clipboard: () -> Clipboard? = { runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() },
    private val download: suspend (String) -> ByteArray? = { PictureHttp(allowHttp = true).image(it, MAX_BYTES) },
) : CanvasClipboardSource, ClipboardHtml {
    private val html = SystemClipboardHtml(log, clipboard = clipboard)
    @Volatile private var token: String? = null

    override fun read(): String? = html.read()

    override fun hasContent(): Boolean = safely(false) {
        clipboard()?.availableDataFlavors?.any {
            it == DataFlavor.imageFlavor || it == DataFlavor.stringFlavor ||
                it.isMimeTypeEqual("text/plain") || it.isMimeTypeEqual("text/html") ||
                it.isMimeTypeEqual("text/uri-list") || it.isMimeTypeEqual("text/x-moz-url")
        } == true
    }

    override suspend fun readContent(): ClipboardContent = withContext(Dispatchers.IO) {
        val board = safely(null) { clipboard() } ?: return@withContext ClipboardContent()
        val contents = safely<Transferable?>(null) { board.getContents(null) } ?: return@withContext ClipboardContent()
        fun string(flavor: DataFlavor): String? = safely(null) {
            flavor.getReaderForText(contents).use { reader -> reader.readTextBounded() }
        }
        val flavors = contents.transferDataFlavors
        val textFlavor = flavors.firstOrNull { it == DataFlavor.stringFlavor }
            ?: flavors.firstOrNull { it.isMimeTypeEqual("text/plain") }
        val htmlFlavor = listOf(DataFlavor.allHtmlFlavor, DataFlavor.fragmentHtmlFlavor, DataFlavor.selectionHtmlFlavor)
            .firstOrNull { contents.isDataFlavorSupported(it) }
            ?: flavors.firstOrNull { it.isMimeTypeEqual("text/html") }
        val urlFlavor = flavors.firstOrNull { it.isMimeTypeEqual("text/x-moz-url") || it.isMimeTypeEqual("text/uri-list") }
        val image = if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) safely(null) {
            (contents.getTransferData(DataFlavor.imageFlavor) as? Image)?.pngBytes()
        } else null
        ClipboardContent(textFlavor?.let(::string), htmlFlavor?.let(::string), image,
            urlFlavor?.let(::string)?.lineSequence()?.firstOrNull { it.isNotBlank() && !it.startsWith('#') })
            .also { log.event("clipboard") { "canvas paste: text=${it.text != null}, html=${it.html != null}, image=${it.image != null}, url=${it.imageUrl != null}" } }
    }

    override suspend fun imageBytes(address: String): ByteArray? = withContext(Dispatchers.IO) {
        if (address.startsWith("data:image/", true)) {
            return@withContext safely(null) {
                val comma = address.indexOf(',')
                if (comma < 0 || !address.substring(0, comma).endsWith(";base64", true) || address.length > MAX_BYTES * 2) null
                else Base64.getDecoder().decode(address.substring(comma + 1)).takeIf { it.size <= MAX_BYTES }
            }
        }
        val uri = safely<URI?>(null) { URI(address) } ?: return@withContext null
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrEmpty()) return@withContext null
        safelySuspend { download(address) }
    }

    override fun markInternalCopy() {
        val copiedToken = UUID.randomUUID().toString()
        token = copiedToken
        safely(Unit) {
            clipboard()?.setContents(object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(INTERNAL_FLAVOR)
                override fun isDataFlavorSupported(flavor: DataFlavor?) = flavor == INTERNAL_FLAVOR
                override fun getTransferData(flavor: DataFlavor?): Any =
                    if (flavor == INTERNAL_FLAVOR) copiedToken else throw UnsupportedFlavorException(flavor)
            }, null)
        }
    }

    override fun ownsInternalCopy(): Boolean = safely(false) {
        val expected = token ?: return@safely false
        val board = clipboard()
        board?.isDataFlavorAvailable(INTERNAL_FLAVOR) == true && board.getData(INTERNAL_FLAVOR) == expected
    }

    private fun Image.pngBytes(): ByteArray? {
        val width = getWidth(null)
        val height = getHeight(null)
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try { graphics.drawImage(this, 0, 0, null) } finally { graphics.dispose() }
        }
        return ByteArrayOutputStream().use { out ->
            if (ImageIO.write(image, "png", out) && out.size() <= MAX_BYTES) out.toByteArray() else null
        }
    }

    private fun java.io.Reader.readTextBounded(): String? {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return out.toString()
            if (out.length + count > MAX_CHARS) return null
            out.append(buffer, 0, count)
        }
    }

    private inline fun <T> safely(fallback: T, read: () -> T): T = try { read() } catch (error: Exception) {
        // Clipboard owners and URLs can contain document content; log only the failure's type.
        log.event("clipboard") { "clipboard operation failed: ${error.javaClass.simpleName}" }
        fallback
    }

    private suspend fun safelySuspend(read: suspend () -> ByteArray?): ByteArray? = try { read() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            log.event("clipboard") { "picture fetch failed: ${error.javaClass.simpleName}" }
            null
        }

    companion object {
        internal const val MAX_BYTES = 64 * 1024 * 1024
        private const val MAX_CHARS = 4 * 1024 * 1024
        private const val MAX_PIXELS = 32L * 1024 * 1024
        private val INTERNAL_FLAVOR = DataFlavor("application/x-vivenotes-canvas-copy;class=java.lang.String")
    }
}
