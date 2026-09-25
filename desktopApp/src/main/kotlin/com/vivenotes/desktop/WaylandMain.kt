package com.vivenotes.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import com.vivenotes.App
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** Native Wayland host for JetBrains Runtime's WLToolkit. */
internal fun launchWayland() {
    System.setProperty("compose.layers.type", "COMPONENT")
    requireNativeWaylandToolkit(Toolkit.getDefaultToolkit().javaClass.name)

    SwingUtilities.invokeLater {
        val frame = JFrame("ViveNotes")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.minimumSize = Dimension(720, 540)
        frame.setSize(1440, 900)
        frame.setLocationRelativeTo(null)
        frame.contentPane.add(createWaylandComposePanel())
        frame.isVisible = true
    }
}

internal fun requireNativeWaylandToolkit(toolkitClassName: String) {
    check(toolkitClassName == "sun.awt.wl.WLToolkit") {
        "Native Wayland needs JetBrains Runtime with WLToolkit; current toolkit is $toolkitClassName"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun createWaylandComposePanel() = ComposePanel(
    renderSettings = RenderSettings.SwingGraphics(),
).apply {
    setContent { App() }
}
