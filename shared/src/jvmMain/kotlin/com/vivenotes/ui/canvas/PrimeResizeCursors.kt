package com.vivenotes.ui.canvas

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.PI

private val resizeCursors by lazy { PrimeResizeDiagonal.entries.associateWith(::createResizeCursor) }

internal fun primeResizeAwtCursor(diagonal: PrimeResizeDiagonal): Cursor = resizeCursors.getValue(diagonal)

private fun createResizeCursor(diagonal: PrimeResizeDiagonal): Cursor {
    val fallback = Cursor.getPredefinedCursor(when (diagonal) {
        PrimeResizeDiagonal.NorthWestSouthEast -> Cursor.NW_RESIZE_CURSOR
        PrimeResizeDiagonal.NorthEastSouthWest -> Cursor.NE_RESIZE_CURSOR
    })
    if (GraphicsEnvironment.isHeadless()) return fallback
    val toolkit = Toolkit.getDefaultToolkit()
    val size = toolkit.getBestCursorSize(32, 32)
    if (size.width <= 0 || size.height <= 0) return fallback
    return toolkit.createCustomCursor(primeResizeCursorImage(diagonal, size.width, size.height),
        Point(size.width / 2, size.height / 2), diagonal.keyword)
}

internal fun primeResizeCursorImage(
    diagonal: PrimeResizeDiagonal,
    width: Int = 32,
    height: Int = width,
): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.translate(width / 2.0, height / 2.0)
        graphics.scale(width / 32.0, height / 32.0)
        graphics.rotate(if (diagonal == PrimeResizeDiagonal.NorthWestSouthEast) PI / 4 else -PI / 4)
        val arrow = Path2D.Float().apply {
            moveTo(-12.0, 0.0)
            lineTo(-5.0, -6.0)
            lineTo(-5.0, -2.0)
            lineTo(5.0, -2.0)
            lineTo(5.0, -6.0)
            lineTo(12.0, 0.0)
            lineTo(5.0, 6.0)
            lineTo(5.0, 2.0)
            lineTo(-5.0, 2.0)
            lineTo(-5.0, 6.0)
            closePath()
        }
        graphics.color = Color.WHITE
        graphics.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(arrow)
        graphics.color = Color(0x20, 0x21, 0x24)
        graphics.fill(arrow)
    } finally { graphics.dispose() }
    return image
}
