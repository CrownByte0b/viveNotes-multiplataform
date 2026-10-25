package com.vivenotes.ui.drop

/**
 * A file dragged onto the window from outside the app — from a file manager or the desktop.
 *
 * Its [name] is all a [FileDropHandler] may judge it by: the platform hands over the files only at
 * the drop, and reading one is left to the handler that takes it.
 */
class DroppedFile(val name: String, private val reader: suspend (maxBytes: Long) -> ByteArray?) {

    /** What follows the name's last dot, in lower case; empty when it has none. */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /**
     * The file's bytes, read off the UI thread. Null when it cannot be read — gone since the drop,
     * not readable, or longer than [maxBytes], which spares memory for a file no handler could use.
     */
    suspend fun read(maxBytes: Long): ByteArray? = reader(maxBytes)
}

/** Where files were dropped: page [pageId], at page point ([x], [y]) in dp. */
data class DropPoint(val pageId: String, val x: Float, val y: Float)

/**
 * One kind of file the page takes when it is dropped there. Pictures are the first kind; another —
 * a document to import, say — is one more handler in the page's list (see `rememberFileDropHandlers`).
 * Each dropped file goes to the first handler in the list that [accepts] it.
 */
interface FileDropHandler {

    /** Whether this handler takes [file]. Asked at the drop, before anything is read. */
    fun accepts(file: DroppedFile): Boolean

    /**
     * Puts [files] — every dropped file this handler accepted, in the order they were dropped — on
     * the page at [at]. Runs after the drop has been answered, so it may take its time; the page may
     * have changed meanwhile, which the edit itself has to check.
     */
    suspend fun receive(files: List<DroppedFile>, at: DropPoint)
}

/** One drop shared out: the files each handler takes, and those that none takes. */
internal class DropRouting(
    val deliveries: List<Pair<FileDropHandler, List<DroppedFile>>>,
    val refused: List<DroppedFile>,
) {
    val accepted: Boolean get() = deliveries.isNotEmpty()
}

/** Gives each of [files] to the first of these handlers that accepts it, keeping the drop's order. */
internal fun List<FileDropHandler>.route(files: List<DroppedFile>): DropRouting {
    val taken = LinkedHashMap<FileDropHandler, MutableList<DroppedFile>>()
    val refused = mutableListOf<DroppedFile>()
    for (file in files) {
        val handler = firstOrNull { it.accepts(file) }
        if (handler == null) refused += file else taken.getOrPut(handler) { mutableListOf() } += file
    }
    return DropRouting(taken.map { (handler, mine) -> handler to mine.toList() }, refused)
}
