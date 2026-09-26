package com.vivenotes.desktop

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

internal data class MonitorArea(val bounds: Rectangle, val workArea: Rectangle)

/** The primary monitor in AWT's display-scaled coordinates. */
internal fun primaryMonitorArea(): MonitorArea {
    val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = configuration.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    return MonitorArea(bounds, usableWorkArea(bounds, insets))
}

internal fun usableWorkArea(bounds: Rectangle, insets: Insets): Rectangle = Rectangle(
    bounds.x + insets.left,
    bounds.y + insets.top,
    (bounds.width - insets.left - insets.right).coerceAtLeast(1),
    (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
)

/** Three quarters of the screen, clamped to its usable area so panels and taskbars stay visible. */
internal fun initialWindowSize(monitor: MonitorArea): Dimension = Dimension(
    preferredLength(monitor.bounds.width, monitor.workArea.width, minimum = 720),
    preferredLength(monitor.bounds.height, monitor.workArea.height, minimum = 540),
)

private fun preferredLength(screen: Int, available: Int, minimum: Int): Int =
    (screen * 0.75f).roundToInt().coerceIn(minimum.coerceAtMost(available), available)
