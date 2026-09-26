package com.vivenotes.desktop

import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JLayeredPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Popups closing while the Wayland window paints.
 *
 * Compose renders inside Swing's paint on the native Wayland host, so a frame that closes popups
 * removes their layer components from the pane that is painting them. Closing a menu while one of
 * its tooltips showed removed two at once and threw `No such child` out of `paintChildren`.
 */
class PopupLayerHostTest {

    /** Stands in for the Compose panel: its paint runs a frame that closes [closing]. */
    private class Content(private val host: () -> JLayeredPane) : JComponent() {
        var closing: List<JComponent> = emptyList()

        override fun paintComponent(g: Graphics) {
            closing.forEach(host()::remove)
            closing = emptyList()
        }
    }

    private fun popup() = object : JComponent() {}.apply { isOpaque = false }

    private fun paint(pane: JLayeredPane) {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            pane.paint(graphics)
        } finally {
            graphics.dispose()
        }
    }

    /** Menu and tooltip on top of the content, as Compose adds them: popup layer, first position. */
    private fun JLayeredPane.withPopups(vararg popups: JComponent) = apply {
        setSize(200, 100)
        popups.forEach { add(it.apply { setBounds(0, 0, 200, 100) }, JLayeredPane.POPUP_LAYER, 0) }
        doLayout()
    }

    @Test
    fun aPlainLayeredPaneFailsWhenTwoPopupsCloseDuringItsPaint() {
        lateinit var pane: JLayeredPane
        val content = Content { pane }
        pane = JLayeredPane().apply { add(content.apply { setBounds(0, 0, 200, 100) }, JLayeredPane.DEFAULT_LAYER) }
        val menu = popup()
        val tooltip = popup()
        pane.withPopups(menu, tooltip)
        content.closing = listOf(menu, tooltip)

        assertFailsWith<ArrayIndexOutOfBoundsException> { paint(pane) }
    }

    @Test
    fun popupsClosedDuringAPaintAreHiddenAtOnceAndRemovedAfterIt() {
        lateinit var host: PopupLayerHost
        val content = Content { host }
        host = PopupLayerHost(content)
        val menu = popup()
        val tooltip = popup()
        host.withPopups(menu, tooltip)
        content.closing = listOf(menu, tooltip)

        paint(host)

        assertEquals(listOf(content), host.components.toList())
        assertTrue(menu.parent == null && tooltip.parent == null)
    }

    @Test
    fun outsideAPaintPopupsAreRemovedAtOnce() {
        val host = PopupLayerHost(Content { JLayeredPane() })
        val menu = popup()
        host.withPopups(menu)

        host.remove(menu)

        assertTrue(menu.parent == null)
        assertEquals(1, host.componentCount)
    }

    @Test
    fun theContentFillsTheHost() {
        val content = Content { JLayeredPane() }
        val host = PopupLayerHost(content)

        host.setSize(640, 480)
        host.doLayout()

        assertEquals(java.awt.Rectangle(0, 0, 640, 480), content.bounds)
    }
}
