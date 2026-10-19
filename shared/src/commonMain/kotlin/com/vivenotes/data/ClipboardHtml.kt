package com.vivenotes.data

/**
 * The HTML another app put on the system clipboard beside its plain text — what a browser, Google
 * Docs or an office suite copies — where the platform can read it. Paste keeps the headings, lists
 * and marks it describes; without it, text from other apps is pasted plain.
 */
fun interface ClipboardHtml {
    /** The clipboard's HTML now, or null when it holds none or it cannot be read. */
    fun read(): String?
}
