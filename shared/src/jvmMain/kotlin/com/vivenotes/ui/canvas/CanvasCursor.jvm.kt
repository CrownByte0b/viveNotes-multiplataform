package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.input.pointer.PointerIcon
import com.vivenotes.platform.XcursorImage
import com.vivenotes.platform.XcursorTheme
import com.vivenotes.platform.customCursorScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

private val canvasCursors = ConcurrentHashMap<Pair<CanvasCursor, Int>, Cursor>()
private val canvasIcons = ConcurrentHashMap<Cursor, PointerIcon>()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun canvasPointerIcon(cursor: CanvasCursor): PointerIcon {
    val awt = canvasAwtCursor(cursor, customCursorScale(LocalAwtWindow.current))
    return canvasIcons.computeIfAbsent(awt) { PointerIcon(it) }
}

/** The AWT cursor for [cursor] on a window drawn at [scale]. */
internal fun canvasAwtCursor(cursor: CanvasCursor, scale: Int = 1): Cursor =
    canvasCursors.computeIfAbsent(cursor to scale) { (kind, at) -> createCanvasCursor(kind, at) }

internal fun Cursor.isCanvasCursor(): Boolean = this in canvasCursors.values

private val CanvasCursor.themeNames: List<String>
    get() = when (this) {
        CanvasCursor.AllScroll -> listOf("all-scroll", "fleur")
        CanvasCursor.Grabbing -> listOf("grabbing", "closedhand")
    }

private fun createCanvasCursor(cursor: CanvasCursor, scale: Int): Cursor {
    // The four-headed Move cursor: Windows' own all-scroll shape, X11's fleur.
    val fallback = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    if (GraphicsEnvironment.isHeadless()) return fallback
    val toolkit = Toolkit.getDefaultToolkit()
    return runCatching {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val themed = if ("win" in os || "mac" in os) null
            else XcursorTheme.fromEnvironment().find(cursor.themeNames, XcursorTheme.size() * scale)
        when {
            themed != null -> {
                val (image, hotspot) = fitCursorImage(themed, toolkit.getBestCursorSize(
                    themed.width.roundUpTo(scale), themed.height.roundUpTo(scale)))
                toolkit.createCustomCursor(image, hotspot, cursor.keyword)
            }
            // Windows has no closed hand of its own; browsers there draw one too.
            cursor == CanvasCursor.Grabbing -> {
                val size = toolkit.getBestCursorSize(32, 32)
                if (size.width < 16 || size.height < 16) fallback
                else toolkit.createCustomCursor(grabbingCursorImage(size.width, size.height),
                    Point(size.width / 2, size.height * 7 / 16), cursor.keyword)
            }
            else -> fallback
        }
    }.getOrDefault(fallback)
}

private fun Int.roundUpTo(step: Int): Int = (this + step - 1) / step * step

/**
 * [image] on a canvas of [size], the size the toolkit takes unchanged: AWT stretches any other
 * image to its preferred size but leaves the hotspot where it was. A larger canvas pads the image
 * at its top left; a smaller one scales the image and its hotspot down together.
 */
internal fun fitCursorImage(image: XcursorImage, size: Dimension): Pair<BufferedImage, Point> {
    val source = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB_PRE)
    image.pixels.copyInto((source.raster.dataBuffer as DataBufferInt).data)
    val ratio = minOf(1.0, size.width / image.width.toDouble(), size.height / image.height.toDouble())
    val canvas = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = canvas.createGraphics()
    try {
        if (ratio < 1.0) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        }
        graphics.drawImage(source, 0, 0, (image.width * ratio).roundToInt().coerceAtLeast(1),
            (image.height * ratio).roundToInt().coerceAtLeast(1), null)
    } finally { graphics.dispose() }
    val hotspot = Point((image.xHot * ratio).toInt().coerceIn(0, size.width - 1),
        (image.yHot * ratio).toInt().coerceIn(0, size.height - 1))
    return canvas to hotspot
}

/** A closed hand seen from its back, for desktops without a cursor theme. */
internal fun grabbingCursorImage(width: Int = 32, height: Int = width): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.scale(width / 32.0, height / 32.0)
        val hand = Area(RoundRectangle2D.Float(8.5f, 10f, 17f, 15f, 9f, 9f)).apply {
            // Four folded fingers along the top, the thumb tucked in at the left.
            for (x in listOf(9f, 13f, 17f, 21f)) add(Area(RoundRectangle2D.Float(x, 7.5f, 4.5f, 8f, 4.5f, 4.5f)))
            add(Area(Ellipse2D.Float(5f, 13f, 7.5f, 6f)))
        }
        graphics.color = Color.WHITE
        graphics.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(hand)
        graphics.color = Color(0x20, 0x21, 0x24)
        graphics.fill(hand)
        graphics.color = Color.WHITE
        graphics.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (x in listOf(13.25f, 17.25f, 21.25f)) graphics.draw(Line2D.Float(x, 9.5f, x, 13f))
    } finally { graphics.dispose() }
    return image
}

/** Compose leaves hover mode on mouse press, so update the actual AWT render components. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun ApplyCanvasCursorWhilePressed(cursor: CanvasCursor?) {
    val window = LocalAwtWindow.current
    val awt = cursor?.let { canvasAwtCursor(it, customCursorScale(window)) }
    DisposableEffect(window, awt) {
        if (awt != null && window != null) {
            val override = CanvasCursorOverride(window, awt)
            SwingUtilities.invokeLater(override::apply)
            onDispose { SwingUtilities.invokeLater(override::restore) }
        } else onDispose { }
    }
}

/**
 * Overrides the host and its children, including ComposePanel's private rendering component.
 *
 * Compose owns the render component's cursor: it may already show the canvas cursor when this
 * override captures it, and it sets its hover cursor again on release before [restore] runs. So
 * [restore] leaves any cursor changed since [apply], never puts a canvas cursor back, and lets a
 * component that inherited its cursor inherit again.
 */
internal class CanvasCursorOverride(private val root: Component, private val cursor: Cursor) {
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
            if (component.cursor === cursor) component.cursor = before?.takeUnless { it === cursor || it.isCanvasCursor() }
        }
        prior.clear()
    }
}
