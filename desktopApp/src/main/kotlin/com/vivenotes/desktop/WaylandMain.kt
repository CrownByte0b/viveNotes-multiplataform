package com.vivenotes.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import com.vivenotes.App
import com.vivenotes.workspace.WorkspaceSession
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** Native Wayland host for JetBrains Runtime's WLToolkit. */
internal fun launchWayland(notes: DesktopNotes) {
    System.setProperty("compose.layers.type", "COMPONENT")
    requireNativeWaylandToolkit(Toolkit.getDefaultToolkit().javaClass.name)
    notes.start()

    SwingUtilities.invokeLater {
        val frame = JFrame("ViveNotes")
        // Disposed only once the notes are written and closed, which is when the process can end:
        // the same end as DISPOSE_ON_CLOSE, and not EXIT_ON_CLOSE's JBR shutdown-thread exception.
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) = notes.close(frame::dispose)
        })
        frame.minimumSize = Dimension(720, 540)
        frame.setSize(1440, 900)
        frame.setLocationRelativeTo(null)
        frame.contentPane.add(createWaylandComposePanel(notes.session))
        frame.isVisible = true
    }
}

internal fun requireNativeWaylandToolkit(toolkitClassName: String) {
    check(toolkitClassName == "sun.awt.wl.WLToolkit") {
        "Native Wayland needs JetBrains Runtime with WLToolkit; current toolkit is $toolkitClassName"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun createWaylandComposePanel(session: WorkspaceSession) = ComposePanel(
    renderSettings = RenderSettings.SwingGraphics(),
).apply {
    setContent { App(session) }
}
