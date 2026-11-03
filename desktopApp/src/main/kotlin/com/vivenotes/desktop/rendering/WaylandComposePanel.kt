package com.vivenotes.desktop.rendering

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import com.vivenotes.diagnostics.DebugLog

/** Keeps Compose's Swing input/IME/accessibility host while presenting Skia directly through EGL. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun waylandComposePanel(log: DebugLog): ComposePanel =
    ComposePanel(renderSettings = RenderSettings.SwingGraphics()).also { WaylandRendering.observe(it, log) }
