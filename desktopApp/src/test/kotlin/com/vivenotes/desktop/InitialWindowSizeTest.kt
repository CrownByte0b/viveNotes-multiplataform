package com.vivenotes.desktop

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class InitialWindowSizeTest {
    @Test
    fun commonMonitorSizesGetDifferentInitialWindows() {
        assertEquals(Dimension(1440, 810), initialWindowSize(monitor(1920, 1080)))
        assertEquals(Dimension(1920, 1080), initialWindowSize(monitor(2560, 1440)))
        assertEquals(Dimension(2880, 1620), initialWindowSize(monitor(3840, 2160)))
    }

    @Test
    fun systemScaleIsAlreadyReflectedInLogicalMonitorBounds() {
        // A 3840 × 2160 display at 200% OS scaling reports about 1920 × 1080 AWT units.
        assertEquals(Dimension(1440, 810), initialWindowSize(monitor(1920, 1080)))
    }

    @Test
    fun workAreaExcludesTaskbarsAndSupportsNonzeroMonitorOrigins() {
        assertEquals(Rectangle(-1920, 24, 1920, 1016),
            usableWorkArea(Rectangle(-1920, 0, 1920, 1080), Insets(24, 0, 40, 0)))
    }

    @Test
    fun smallScreensNeverGetAWindowLargerThanTheirWorkArea() {
        val bounds = Rectangle(0, 0, 800, 600)
        val size = initialWindowSize(MonitorArea(bounds, usableWorkArea(bounds, Insets(0, 0, 40, 0))))
        assertEquals(Dimension(720, 540), size)
        assertEquals(Dimension(400, 300), initialWindowSize(monitor(400, 300)))
    }

    private fun monitor(width: Int, height: Int) =
        MonitorArea(Rectangle(0, 0, width, height), Rectangle(0, 0, width, height))
}
