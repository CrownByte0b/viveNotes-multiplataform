package com.vivenotes.desktop

import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JLayeredPane

/**
 * The native Wayland window's content: [content], the Compose panel, filling it, and above it the
 * component layers Compose opens popups in — menus, tooltips, dialogs. The panel is told to use
 * this pane as its `windowContainer`.
 *
 * Compose renders inside Swing's paint on this host, and a frame that closes a popup removes that
 * popup's layer from the pane being painted. `JComponent.paintChildren` walks the children by index,
 * so when a frame closed two popups at once — a menu with one of its tooltips showing — it asked for
 * a child that was gone and threw `No such child`. A child removed while this pane paints is
 * therefore hidden at once, which leaves the indices alone, and removed as soon as the paint ends.
 */
internal class PopupLayerHost(private val content: JComponent) : JLayeredPane() {
    private var painting = 0
    private val removedWhilePainting = mutableListOf<Component>()

    init {
        add(content, DEFAULT_LAYER)
    }

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
    }

    override fun getPreferredSize(): Dimension = content.preferredSize

    override fun getMinimumSize(): Dimension = content.minimumSize

    override fun paint(g: Graphics) {
        painting++
        try {
            super.paint(g)
        } finally {
            painting--
            if (painting == 0) finishRemovals()
        }
    }

    /** `remove(Component)` comes here too, which is how Compose closes a layer. */
    override fun remove(index: Int) {
        if (painting == 0) return super.remove(index)
        val child = getComponent(index)
        child.isVisible = false
        removedWhilePainting += child
    }

    private fun finishRemovals() {
        if (removedWhilePainting.isEmpty()) return
        val pending = removedWhilePainting.toList()
        removedWhilePainting.clear()
        pending.forEach { if (it.parent === this) remove(it) }
        revalidate()
        repaint()
    }
}
