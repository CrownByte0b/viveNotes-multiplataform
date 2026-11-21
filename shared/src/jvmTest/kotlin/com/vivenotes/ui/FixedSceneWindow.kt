package com.vivenotes.ui

import androidx.compose.ui.awt.ComposePanel
import javax.swing.JFrame

/**
 * Shows [panel] in a new frame with a fixed [width]×[height] scene; call it on the Swing thread.
 *
 * Tiling compositors (Hyprland, sway, …) choose a new window's size themselves, and a narrower
 * workspace hides the notebook pane with an animation while a test is measuring it. The panel keeps
 * its own bounds whatever size the frame is given, so the layout matches the Xvfb runs on CI. Tests
 * using this dispatch their events to the panel directly, so any part outside the window still
 * receives them.
 */
internal fun showFixedScene(title: String, panel: ComposePanel, width: Int = 1400, height: Int = 900): JFrame =
    JFrame(title).apply {
        contentPane.layout = null
        panel.setBounds(0, 0, width, height)
        contentPane.add(panel)
        setSize(width, height)
        isVisible = true
    }
