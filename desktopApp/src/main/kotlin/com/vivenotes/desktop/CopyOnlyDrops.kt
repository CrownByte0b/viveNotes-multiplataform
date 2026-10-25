package com.vivenotes.desktop

import com.vivenotes.diagnostics.DebugLog
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener

/**
 * Makes every drop on the window a copy. Compose's drop target takes whatever action the drag
 * offers, and with no key held AWT offers a move wherever the source allows one: Windows labels
 * such a drag "Move", and a source that honours a finished move may delete its original. The page
 * only ever copies what is dropped on it, so the drag is told exactly that.
 *
 * Compose's own [DropTarget] stays the one that handles everything; it is put behind one that
 * hands it each event with the action changed to a copy. A source that cannot copy keeps its own
 * action, rather than having its drags refused.
 */
internal object CopyOnlyDrops {

    /** Puts every Compose drop target in [root] behind a copying one; how many it found. */
    fun install(root: Component, log: DebugLog = DebugLog()): Int {
        var installed = 0
        fun visit(component: Component) {
            val target = component.dropTarget
            // AWT's own class is Compose's; Swing's text components have their TransferHandler's.
            if (target != null && target.javaClass == DropTarget::class.java) {
                CopyingDropTarget(component, target)
                installed++
            }
            (component as? Container)?.components?.forEach(::visit)
        }
        visit(root)
        log.event("drop") { "drops made copies on $installed drop targets" }
        return installed
    }

    /**
     * Marks the target as already installed, and holds the one it stands in front of. It keeps
     * Compose's target actions: JBR's Wayland toolkit offers the compositor the preferred action the
     * keys ask for (Shift, a move) among them, and throws when that is not one of them. Only the
     * accepted action changes.
     */
    private class CopyingDropTarget(component: Component, compose: DropTarget) :
        DropTarget(component, compose.defaultActions, Copying(compose), compose.isActive)

    private class Copying(private val compose: DropTarget) : DropTargetListener {
        override fun dragEnter(event: DropTargetDragEvent) = compose.dragEnter(event.asCopy())
        override fun dragOver(event: DropTargetDragEvent) = compose.dragOver(event.asCopy())
        override fun dropActionChanged(event: DropTargetDragEvent) = compose.dropActionChanged(event.asCopy())
        override fun dragExit(event: DropTargetEvent) = compose.dragExit(event)
        override fun drop(event: DropTargetDropEvent) = compose.drop(event.asCopy())
    }
}

private fun copies(sourceActions: Int) = sourceActions and DnDConstants.ACTION_COPY != 0

/** [this] drag as a copy, answering through the original event. */
internal fun DropTargetDragEvent.asCopy(): DropTargetDragEvent {
    if (!copies(sourceActions)) return this
    val original = this
    return object : DropTargetDragEvent(dropTargetContext, location, DnDConstants.ACTION_COPY, sourceActions) {
        override fun getTransferable(): Transferable = original.transferable
        override fun getCurrentDataFlavors(): Array<DataFlavor> = original.currentDataFlavors
        override fun isDataFlavorSupported(flavor: DataFlavor) = original.isDataFlavorSupported(flavor)
        override fun acceptDrag(dragOperation: Int) =
            original.acceptDrag(if (dragOperation == DnDConstants.ACTION_NONE) dragOperation else DnDConstants.ACTION_COPY)
        override fun rejectDrag() = original.rejectDrag()
    }
}

/** [this] drop as a copy, answering through the original event. */
internal fun DropTargetDropEvent.asCopy(): DropTargetDropEvent {
    if (!copies(sourceActions)) return this
    val original = this
    return object : DropTargetDropEvent(dropTargetContext, location, DnDConstants.ACTION_COPY, sourceActions, isLocalTransfer) {
        override fun getTransferable(): Transferable = original.transferable
        override fun getCurrentDataFlavors(): Array<DataFlavor> = original.currentDataFlavors
        override fun isDataFlavorSupported(flavor: DataFlavor) = original.isDataFlavorSupported(flavor)
        override fun acceptDrop(dropAction: Int) =
            original.acceptDrop(if (dropAction == DnDConstants.ACTION_NONE) dropAction else DnDConstants.ACTION_COPY)
        override fun rejectDrop() = original.rejectDrop()
        override fun dropComplete(success: Boolean) = original.dropComplete(success)
    }
}
