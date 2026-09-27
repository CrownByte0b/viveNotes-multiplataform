package com.vivenotes.desktop

import com.vivenotes.workspace.NotebookTransferState
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class WaylandTransferRepaintTest {

    @Test
    fun fileDialogAndTransferTransitionsRepaintTheNativeHost() = runBlocking {
        val states = MutableStateFlow<WorkspaceState?>(WorkspaceState(emptyList(), "", "", ""))
        var repaints = 0
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            repaintOnNotebookTransfer(states) { repaints++ }
        }
        assertEquals(1, repaints)

        states.value = states.value!!.copy(notebookTransfer = NotebookTransferState(running = true))
        yield()
        assertEquals(2, repaints, "the working dialog should paint after the chooser closes")

        states.value = states.value!!.copy(notebookTransfer = NotebookTransferState(message = "Done"))
        yield()
        assertEquals(3, repaints, "completion must paint without another pointer event")

        states.value = states.value!!.copy(activeTab = RibbonTab.File)
        yield()
        assertEquals(3, repaints, "ordinary workspace edits do not need an extra host repaint")
        observer.cancelAndJoin()
    }
}
