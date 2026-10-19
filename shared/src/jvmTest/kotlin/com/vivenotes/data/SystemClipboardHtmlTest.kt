package com.vivenotes.data

import com.vivenotes.diagnostics.DebugLog
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

/** What the system clipboard's HTML reader gives the paste, from the AWT clipboard a browser fills. */
class SystemClipboardHtmlTest {
    private val page = "<h2>Café — naïve</h2><ul><li>日本 😀</li></ul>"

    private fun clipboardWith(contents: Transferable) = Clipboard("test").apply { setContents(contents, null) }

    private fun logged(): Pair<DebugLog, MutableList<String>> {
        val lines = mutableListOf<String>()
        return DebugLog(enabled = true) { lines += it } to lines
    }

    @Test
    fun readsTheHtmlABrowserOffersBesideItsText() {
        val (log, lines) = logged()
        val reader = SystemClipboardHtml(log, clipboard = { clipboardWith(HtmlSelection(page, "Café — naïve\n日本")) })

        assertEquals(page, reader.read())
        assertEquals(listOf("ViveNotes DEBUG [clipboard] paste: html of ${page.length} chars"), lines)
        assertTrue(lines.none { "Café" in it }, "the log never holds what was copied")
    }

    @Test
    fun plainTextOnlyAndOverlongHtmlAreLeftForTheText() {
        val (log, lines) = logged()
        assertNull(SystemClipboardHtml(log, clipboard = { clipboardWith(StringSelection("just text")) }).read())
        assertNull(SystemClipboardHtml(log, maxChars = 10, clipboard = { clipboardWith(HtmlSelection(page, "x")) }).read())
        assertNull(SystemClipboardHtml(log, clipboard = { null }).read())
        assertEquals(listOf(
            "ViveNotes DEBUG [clipboard] paste: no html offered",
            "ViveNotes DEBUG [clipboard] paste: html of ${page.length} chars left for its plain text",
        ), lines)
    }

    @Test
    fun aClipboardThatFailsIsNoHtml() {
        val (log, lines) = logged()
        val broken = object : Transferable by HtmlSelection(page, "x") {
            override fun getTransferData(flavor: DataFlavor?): Any = throw IOException("owner went away")
        }

        assertNull(SystemClipboardHtml(log, clipboard = { clipboardWith(broken) }).read())
        assertEquals(listOf("ViveNotes DEBUG [clipboard] html read failed: IOException: owner went away"), lines)
    }

    /**
     * Another process owning the X11 clipboard, as a browser does: the HTML crosses the X server
     * as `text/html` and comes back intact, accents, CJK and emoji included.
     */
    @Test
    fun htmlCopiedByAnotherProcessCrossesTheDisplayIntact() {
        assumeFalse("needs a display", GraphicsEnvironment.isHeadless() || System.getenv("DISPLAY").isNullOrEmpty())
        val java = ProcessHandle.current().info().command().orElseThrow()
        val owner = ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
            ClipboardOwner::class.java.name).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val output = owner.inputStream.bufferedReader()
            fun await(marker: String) = assertTrue(generateSequence { output.readLine() }.any { it == marker }, "no \"$marker\"")
            await("owned")
            ClipboardOwner.writeTo(owner, page)
            await("set")
            assertEquals(page, SystemClipboardHtml(clipboard = { Toolkit.getDefaultToolkit().systemClipboard }).read())
        } finally {
            owner.outputStream.close()
            owner.waitFor(10, TimeUnit.SECONDS)
            owner.destroyForcibly()
        }
    }
}

/** What a browser puts on the clipboard: its HTML in every HTML flavour, and the same text plain. */
internal class HtmlSelection(private val html: String, private val text: String) : Transferable {
    private val flavors = arrayOf(DataFlavor.allHtmlFlavor, DataFlavor.fragmentHtmlFlavor, DataFlavor.selectionHtmlFlavor,
        DataFlavor.stringFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.clone()

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavors.any { it == flavor }

    override fun getTransferData(flavor: DataFlavor?): Any = when {
        flavor == DataFlavor.stringFlavor -> text
        flavors.any { it == flavor } -> html
        else -> throw UnsupportedFlavorException(flavor)
    }
}

/** A separate process that owns the system clipboard with [HtmlSelection] until its input closes. */
object ClipboardOwner {
    fun writeTo(process: Process, html: String) {
        val file = File.createTempFile("vive-clipboard", ".html").apply { deleteOnExit(); writeText(html) }
        process.outputStream.write((file.absolutePath + "\n").toByteArray())
        process.outputStream.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("owned")
        val input = System.`in`.bufferedReader()
        val path = input.readLine() ?: return
        val html = File(path).readText()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(HtmlSelection(html, "plain"), null)
        println("set")
        System.out.flush()
        input.readLine()
        System.exit(0)
    }
}
