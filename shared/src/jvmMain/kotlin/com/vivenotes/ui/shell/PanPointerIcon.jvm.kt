package com.vivenotes.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

private val panCursors by lazy { PanDirection.entries.associateWith(::createPanCursor) }

internal actual fun panPointerIcon(direction: PanDirection): PointerIcon = PointerIcon(panCursors.getValue(direction))

private fun createPanCursor(direction: PanDirection): Cursor {
    val fallback = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    if (GraphicsEnvironment.isHeadless()) return fallback
    return runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        val size = toolkit.getBestCursorSize(32, 32)
        if (size.width < 24 || size.height < 24) return fallback
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(size.width / 32.0, size.height / 32.0)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            fun arrow(dx: Int, dy: Int) {
                val tipX = 16f + dx * 12f
                val tipY = 16f + dy * 12f
                val baseX = tipX - dx * 6f
                val baseY = tipY - dy * 6f
                val wingX = -dy * 4f
                val wingY = dx * 4f
                val head = Path2D.Float().apply {
                    moveTo(tipX.toDouble(), tipY.toDouble())
                    lineTo((baseX + wingX).toDouble(), (baseY + wingY).toDouble())
                    lineTo((baseX - wingX).toDouble(), (baseY - wingY).toDouble())
                    closePath()
                }
                graphics.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                graphics.color = Color.WHITE
                graphics.drawLine(16, 16, baseX.toInt(), baseY.toInt())
                graphics.fill(head)
                graphics.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                graphics.color = Color(35, 39, 43)
                graphics.drawLine(16, 16, baseX.toInt(), baseY.toInt())
                graphics.fill(head)
            }
            val (dx, dy) = when (direction) {
                PanDirection.North -> 0 to -1
                PanDirection.NorthEast -> 1 to -1
                PanDirection.East -> 1 to 0
                PanDirection.SouthEast -> 1 to 1
                PanDirection.South -> 0 to 1
                PanDirection.SouthWest -> -1 to 1
                PanDirection.West -> -1 to 0
                PanDirection.NorthWest -> -1 to -1
                PanDirection.Center -> 0 to 0
            }
            if (direction == PanDirection.Center) {
                arrow(0, -1); arrow(1, 0); arrow(0, 1); arrow(-1, 0)
            } else arrow(dx, dy)
        } finally { graphics.dispose() }
        toolkit.createCustomCursor(image, Point(size.width / 2, size.height / 2),
            "Canvas auto-scroll ${direction.name}")
    }.getOrDefault(fallback)
}

/** Compose leaves hover mode on mouse press, so update the actual AWT render components. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun ApplyPanCursorWhilePressed(active: Boolean, direction: PanDirection) {
    val window = LocalAwtWindow.current
    DisposableEffect(window, active, direction) {
        if (active && window != null) {
            val cursorOverride = PanCursorOverride(window, panCursors.getValue(direction))
            SwingUtilities.invokeLater(cursorOverride::apply)
            onDispose { SwingUtilities.invokeLater(cursorOverride::restore) }
        } else onDispose { }
    }
}

/**
 * Overrides the host and its children, including ComposePanel's private rendering component.
 *
 * Compose owns the render component's cursor: it may already show the pan cursor when this
 * override captures it, and it sets its hover cursor again on release before [restore] runs. So
 * [restore] leaves any cursor changed since [apply], never puts a pan cursor back, and lets a
 * component that inherited its cursor inherit again.
 */
internal class PanCursorOverride(private val root: Component, private val cursor: Cursor) {
    /** Each component's own cursor before [apply]; null when it inherited its parent's. */
    private val prior = LinkedHashMap<Component, Cursor?>()

    fun apply() {
        fun capture(component: Component) {
            if (component !in prior) prior[component] = component.cursor.takeIf { component.isCursorSet }
            if (component is Container) component.components.forEach(::capture)
        }
        capture(root)
        prior.keys.forEach { it.cursor = cursor }
    }

    fun restore() {
        prior.forEach { (component, before) ->
            if (component.cursor === cursor) component.cursor = before?.takeUnless { it.isPanCursor() }
        }
        prior.clear()
    }

    private fun Cursor.isPanCursor() = this === cursor || this in panCursors.values
}
