package com.vivenotes.desktop.titlebar

import androidx.compose.ui.graphics.Color
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ui.theme.headerBarColor
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** The app's frame: its icon at every size a platform asks for, and its look following the app's. */
class TitleBarTest {
    @Test
    fun everyIconSizeIsShippedSquareWithTheArtworksTransparentCorners() {
        assertEquals(APP_ICON_SIZES, appIcons.map { it.width })
        for (icon in appIcons) {
            val size = icon.width
            assertEquals(size, icon.height)
            assertTrue(icon.colorModel.hasAlpha(), "$size px icon has no alpha")
            // Resampling leaves a trace of the rounded corner's edge, never anything visible.
            assertTrue(icon.getRGB(0, 0) ushr 24 < 8, "$size px icon's corner is not transparent")
            assertEquals(0xFF, icon.getRGB(size / 2, size / 2) ushr 24, "$size px icon's middle is not opaque")
        }
        assertTrue(16 in APP_ICON_SIZES && 32 in APP_ICON_SIZES && 256 in APP_ICON_SIZES)
    }

    @Test
    fun eachToolkitHasTheFrameItDraws() {
        assertEquals(FrameKind.WINDOWS, frameKindOf("sun.awt.windows.WToolkit"))
        assertEquals(FrameKind.X11, frameKindOf("sun.awt.X11.XToolkit"))
        assertEquals(FrameKind.WAYLAND, frameKindOf("sun.awt.wl.WLToolkit"))
        assertEquals(null, frameKindOf("sun.lwawt.macosx.LWCToolkit"))
    }

    @Test
    fun windowsCaptionColoursAreBlueGreenRed() {
        assertEquals(0x00563412, colorRef(Color(0xFF123456)))
        assertEquals(0x0024201E, colorRef(headerBarColor(darkTheme = true)))
        assertEquals(0x00FFFFFF, colorRef(headerBarColor(darkTheme = false)))
    }

    /** Needs a display: the build's Xvfb run, or Windows. */
    @Test
    fun iconAndLookWaitForThePeerThenFollowEachChange() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val applied = mutableListOf<Boolean>()
        onSwing {
            val frame = JFrame("Title bar test")
            try {
                val titleBar = TitleBar(frame, dark = true, DebugLog(), style = { applied += it })
                titleBar.dark = false
                assertEquals(emptyList(), applied, "nothing is applied before the window has a peer")
                assertTrue(frame.iconImages.isEmpty())

                frame.addNotify()
                assertEquals(listOf(false), applied)
                assertEquals(appIcons, frame.iconImages)
                titleBar.dark = true
                titleBar.dark = true
                assertEquals(listOf(false, true), applied)

                frame.dispose()
                titleBar.dark = false
                frame.addNotify()
                assertEquals(listOf(false, true, false), applied, "a new peer is given the look again")
            } finally {
                frame.dispose()
            }
        }
    }

    @Test
    fun aPlatformThatRefusesLeavesItsOwnFrameAndSaysWhy() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val logged = mutableListOf<String>()
        onSwing {
            val frame = JFrame("Title bar test")
            try {
                TitleBar(frame, dark = true, DebugLog(enabled = true, logged::add),
                    style = { throw UnsupportedOperationException("no DWM here") })
                frame.addNotify()
                assertEquals(appIcons, frame.iconImages, "the icon does not depend on the look")
            } finally {
                frame.dispose()
            }
        }
        assertEquals(1, logged.size, logged.toString())
        assertTrue("UnsupportedOperationException: no DWM here" in logged.single(), logged.single())
    }
}

/** Runs [block] on the Swing thread, failing the test with what it throws. */
internal fun onSwing(block: () -> Unit) {
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            block()
        } catch (thrown: Throwable) {
            failure = thrown
        }
    }
    failure?.let { throw it }
}
