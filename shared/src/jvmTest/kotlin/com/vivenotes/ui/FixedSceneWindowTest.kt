package com.vivenotes.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class FixedSceneWindowTest {
    /** Hyprland tiled the 1400×900 test frame to 933×1005; the scene must not follow it. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theSceneKeepsItsSizeWhenTheWindowManagerResizesTheFrame() {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            frame = showFixedScene("Fixed scene test", panel)
        }
        try {
            SwingUtilities.invokeAndWait {
                frame.setSize(933, 1005)
                frame.validate()
            }
            SwingUtilities.invokeAndWait { assertEquals(Dimension(1400, 900), panel.size) }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
