package com.vivenotes.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import com.vivenotes.App
import com.vivenotes.data.PictureLibrary
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
    val monitor = primaryMonitorArea()
    val initialSize = initialWindowSize(monitor)

    SwingUtilities.invokeLater {
        val frame = JFrame("ViveNotes")
        // Disposed only once the notes are written and closed, which is when the process can end:
        // the same end as DISPOSE_ON_CLOSE, and not EXIT_ON_CLOSE's JBR shutdown-thread exception.
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) = notes.close(frame::dispose)
        })
        frame.minimumSize = Dimension(720.coerceAtMost(monitor.workArea.width),
            540.coerceAtMost(monitor.workArea.height))
        frame.size = initialSize
        frame.setLocation(monitor.workArea.x + (monitor.workArea.width - initialSize.width) / 2,
            monitor.workArea.y + (monitor.workArea.height - initialSize.height) / 2)
        frame.contentPane.add(createWaylandContent(notes, notes.pictures(frame)))
        frame.isVisible = true
    }
}

internal fun requireNativeWaylandToolkit(toolkitClassName: String) {
    check(toolkitClassName == "sun.awt.wl.WLToolkit") {
        "Native Wayland needs JetBrains Runtime with WLToolkit; current toolkit is $toolkitClassName"
    }
}

/** The Compose panel inside the [PopupLayerHost] its popups open in. */
@OptIn(ExperimentalComposeUiApi::class)
private fun createWaylandContent(notes: DesktopNotes, pictures: PictureLibrary): PopupLayerHost {
    val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
    val host = PopupLayerHost(panel)
    panel.windowContainer = host
    panel.setContent {
        App(notes.session, pictures, notes.interfaceSettings, notes::updateInterfaceSettings,
            notes.viewSettings, notes::updateViewSettings, notes.keyBindings, notes::updateKeyBindings)
    }
    return host
}
