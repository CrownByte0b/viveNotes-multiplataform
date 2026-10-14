package com.vivenotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.vivenotes.ui.keyboard.ShortcutKeys

/** One window's tooltips, including delayed requests and those composed in popup owners. */
internal class TooltipDismissals {
    private val tooltips = mutableSetOf<DelayedTooltipState>()

    fun register(state: DelayedTooltipState) { tooltips += state }
    fun unregister(state: DelayedTooltipState) { tooltips -= state }

    fun dismiss(): Boolean {
        val open = tooltips.filter { it.hasOpenRequest }
        val visible = open.any { it.isVisible }
        open.forEach { it.dismiss() }
        // A delayed hint should not use up Escape when there is a panel ready to close.
        return visible
    }
}

internal val LocalTooltipDismissals = compositionLocalOf<TooltipDismissals?> { null }

/** Popup owners have a separate keyboard route from the workspace beneath them. */
@Composable
internal fun Modifier.dismissPopupOnEscape(onDismiss: () -> Unit): Modifier {
    val tooltips = LocalTooltipDismissals.current
    val keys = remember { ShortcutKeys() }
    return onPreviewKeyEvent { event ->
        keys.observe(event) || keys.dismiss(event) {
            if (tooltips?.dismiss() != true) onDismiss()
            true
        }
    }
}
