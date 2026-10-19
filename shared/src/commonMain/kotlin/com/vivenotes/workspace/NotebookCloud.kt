package com.vivenotes.workspace

import kotlinx.coroutines.flow.StateFlow

/**
 * Moving a closed notebook's contents to the server and bringing them back: Android's
 * `SyncAccounts.moveNotebookToCloud` and `bringNotebookBack`, as the File tab's Closed Notebooks
 * pane reaches them. The platform supplies it with the account layer.
 */
interface NotebookCloud {

    /** Whether a server is connected. Without one there is no cloud to move anything to. */
    val connected: StateFlow<Boolean>

    /**
     * Moves [notebookId]'s contents to the server ([toCloud]) or downloads them again. Returns the
     * sentence to show when it did not simply work, or null when it did — the list has then already
     * moved the notebook from one group to the other, which is a better report than a sentence.
     */
    suspend fun move(notebookId: String, toCloud: Boolean): String?
}
