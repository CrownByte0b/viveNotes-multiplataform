package com.vivenotes.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetContext
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drops on either desktop host reach Compose as copies, so neither Windows' "Move" label nor a
 * source deleting its original after a finished move can follow from dropping a file on the page.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CopyOnlyDropsTest {

    @Test
    fun aMoveOfferedByTheSourceReachesComposeAsACopyInTheStandardWindow() = withWindow(standard = true) { host ->
        assertCopies(host)
    }

    @Test
    fun aMoveOfferedByTheSourceReachesComposeAsACopyInTheWaylandPanel() = withWindow(standard = false) { host ->
        assertCopies(host)
    }

    @Test
    fun aSourceThatCannotCopyKeepsItsOwnAction() = withWindow(standard = true) { host ->
        val drag = host.drag(DnDConstants.ACTION_MOVE, sourceActions = DnDConstants.ACTION_MOVE)
        assertEquals(DnDConstants.ACTION_MOVE, drag.accepted)
        val drop = host.drop(DnDConstants.ACTION_MOVE, sourceActions = DnDConstants.ACTION_MOVE)
        assertEquals(true, drop.completed)
        assertEquals(DragAndDropTransferAction.Move, host.dropped)
    }

    @Test
    fun installingAgainLeavesTheWindowAsItWas() = withWindow(standard = true) { host ->
        var again = -1
        SwingUtilities.invokeAndWait { again = CopyOnlyDrops.install(host.window) }
        assertEquals(0, again, "already copying")
        assertCopies(host)
    }

    /**
     * Regression: a copying target that offered only copies broke JBR's Wayland toolkit, which
     * throws when the move Shift asks for is not among the target's actions.
     */
    @Test
    fun theTargetStillOffersEveryActionComposeDid() = withWindow(standard = false) { host ->
        assertEquals(DnDConstants.ACTION_COPY_OR_MOVE or DnDConstants.ACTION_LINK, host.targetActions)
    }

    private fun assertCopies(host: Host) {
        val drag = host.drag(DnDConstants.ACTION_MOVE, sourceActions = DnDConstants.ACTION_COPY_OR_MOVE)
        assertEquals(DnDConstants.ACTION_COPY, drag.accepted, "the drag is answered as a copy")
        val drop = host.drop(DnDConstants.ACTION_MOVE, sourceActions = DnDConstants.ACTION_COPY_OR_MOVE)
        assertEquals(DnDConstants.ACTION_COPY, drop.accepted, "and so is the drop")
        assertEquals(true, drop.completed)
        assertEquals(DragAndDropTransferAction.Copy, host.dropped, "Compose sees a copy too")
    }

    private fun withWindow(standard: Boolean, test: (Host) -> Unit) {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var host: Host
        SwingUtilities.invokeAndWait { host = Host(standard) }
        // Compose places its content on its first frames; a drop before that has nothing to land on.
        Thread.sleep(500)
        try { test(host) } finally { SwingUtilities.invokeAndWait { host.window.dispose() } }
    }

    private class Host(standard: Boolean) {
        @Volatile var dropped: DragAndDropTransferAction? = null
        val window: Window
        private val target: DropTarget

        private val content: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dropped = event.action
                    return true
                }
            }))
        }

        init {
            window = if (standard) ComposeWindow().apply { setContent { content() } }
            else JFrame().apply { contentPane.add(ComposePanel(renderSettings = RenderSettings.SwingGraphics()).apply { setContent { content() } }) }
            window.setSize(400, 300)
            window.isVisible = true
            assertTrue(CopyOnlyDrops.install(window) >= 1, "Compose's drop target was found")
            target = copying(window) ?: error("no copying drop target")
        }

        fun drag(action: Int, sourceActions: Int): DragEvent {
            val event = DragEvent(target.dropTargetContext, action, sourceActions)
            SwingUtilities.invokeAndWait {
                target.dragEnter(event)
                target.dragOver(event)
            }
            return event
        }

        val targetActions: Int get() = target.defaultActions

        fun drop(action: Int, sourceActions: Int): DropEvent =
            DropEvent(target.dropTargetContext, action, sourceActions).also { SwingUtilities.invokeAndWait { target.drop(it) } }

        private fun copying(component: Component): DropTarget? =
            component.dropTarget?.takeIf { it.javaClass != DropTarget::class.java && it.javaClass.name.contains("CopyOnlyDrops") }
                ?: (component as? Container)?.components?.firstNotNullOfOrNull(::copying)
    }

    private class DragEvent(context: DropTargetContext, action: Int, sourceActions: Int) :
        DropTargetDragEvent(context, Point(100, 100), action, sourceActions) {
        var accepted: Int? = null
        override fun getTransferable(): Transferable = StringSelection("dragged")
        override fun acceptDrag(dragOperation: Int) { accepted = dragOperation }
        override fun rejectDrag() { accepted = DnDConstants.ACTION_NONE }
    }

    private class DropEvent(context: DropTargetContext, action: Int, sourceActions: Int) :
        DropTargetDropEvent(context, Point(100, 100), action, sourceActions) {
        var accepted: Int? = null
        var completed: Boolean? = null
        override fun getTransferable(): Transferable = StringSelection("dropped")
        override fun acceptDrop(dropAction: Int) { accepted = dropAction }
        override fun rejectDrop() { completed = false }
        override fun dropComplete(success: Boolean) { completed = success }
    }
}
