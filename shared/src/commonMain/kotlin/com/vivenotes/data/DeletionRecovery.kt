package com.vivenotes.data

/**
 * What a delete did with the row it was given.
 *
 * The distinction is the user's, not the database's: one of these can be taken back and the other
 * never happened as far as anything downstream is concerned.
 */
enum class DeletionOutcome {
    /** Tombstoned: listed in Deleted Items for the retention window, and pushed as a tombstone. */
    Tombstoned,

    /** Held nothing, so nothing was kept. The rows are gone, and there is nothing to restore. */
    Flushed,
}

/** The hierarchy row represented by one entry in the app-wide Deleted Items pane. */
enum class DeletedItemKind {
    Notebook,
    Section,
    Page,
}

/** Stable identity carried by a transient Undo action without retaining a database projection. */
data class DeletedItemKey(
    val id: String,
    val kind: DeletedItemKind,
)

/**
 * One recoverable hierarchy root.
 *
 * A deleted child is deliberately absent while one of its ancestors is deleted: the ancestor is
 * the action that made that whole branch unreachable. Restoring it reveals any older child
 * tombstones as independent entries instead of silently clearing them too.
 */
data class DeletedItem(
    val key: DeletedItemKey,
    val name: String,
    val notebookName: String? = null,
    val sectionName: String? = null,
    val deletedAt: Long,
    val sectionCount: Int = 0,
    val pageCount: Int = 0,
)

