package com.vivenotes.data

import com.vivenotes.diagnostics.DebugLog
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor

/**
 * The system clipboard's HTML through AWT: the `text/html` a browser offers on X11 and Wayland,
 * and Windows' "HTML Format", which AWT hands over without its header. Read on the thread that
 * pastes, as Compose reads the clipboard's text. HTML longer than [maxChars] — a whole site copied —
 * is left for its plain text, which pastes without holding the window up.
 */
class SystemClipboardHtml(
    private val log: DebugLog = DebugLog(),
    private val maxChars: Int = 4 * 1024 * 1024,
    private val clipboard: () -> Clipboard? = ::systemClipboard,
) : ClipboardHtml {
    override fun read(): String? {
        val board = clipboard() ?: return null
        return try {
            // On Windows "all" keeps the list or table a copied fragment sits in; elsewhere they match.
            val flavor = HtmlFlavors.firstOrNull { board.isDataFlavorAvailable(it) }
            if (flavor == null) {
                log.event("clipboard") { "paste: no html offered" }
                return null
            }
            val html = board.getData(flavor) as? String ?: return null
            if (html.length > maxChars) {
                log.event("clipboard") { "paste: html of ${html.length} chars left for its plain text" }
                null
            } else html.also { log.event("clipboard") { "paste: html of ${it.length} chars" } }
        } catch (error: Exception) {
            log.failure("clipboard", "html read", error)
            null
        }
    }
}

private val HtmlFlavors = listOf(DataFlavor.allHtmlFlavor, DataFlavor.fragmentHtmlFlavor, DataFlavor.selectionHtmlFlavor)

private fun systemClipboard(): Clipboard? = try {
    Toolkit.getDefaultToolkit().systemClipboard
} catch (_: HeadlessException) {
    null
}
