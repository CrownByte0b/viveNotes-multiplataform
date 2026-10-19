package com.vivenotes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import javax.swing.SwingUtilities

internal actual fun navigationResizePointerIcon(): PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR))

/** Compose leaves hover mode during a press; keep the host's resize cursor for the drag. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun ApplyNavigationResizeCursor(active: Boolean) {
    val window = LocalAwtWindow.current
    DisposableEffect(window, active) {
        if (active && window != null) {
            val override = NavigationResizeCursorOverride(window)
            SwingUtilities.invokeLater(override::apply)
            onDispose { SwingUtilities.invokeLater(override::restore) }
        } else onDispose { }
    }
}

internal class NavigationResizeCursorOverride(private val root: Component) {
    private val cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
    private val prior = LinkedHashMap<Component, Cursor?>()

    fun apply() {
        fun capture(component: Component) {
            prior[component] = component.cursor.takeIf { component.isCursorSet }
            if (component is Container) component.components.forEach(::capture)
        }
        capture(root)
        prior.keys.forEach { it.cursor = cursor }
    }

    fun restore() {
        prior.forEach { (component, before) ->
            if (component.cursor === cursor) component.cursor = before
        }
        prior.clear()
    }
}
