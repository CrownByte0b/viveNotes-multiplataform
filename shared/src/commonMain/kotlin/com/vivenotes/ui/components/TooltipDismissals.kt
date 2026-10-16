package com.vivenotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.vivenotes.ui.keyboard.ShortcutKeys
import com.vivenotes.diagnostics.DebugLog

/** One window's tooltips, including delayed requests and those composed in popup owners. */
internal class TooltipDismissals(private val log: DebugLog = DebugLog()) {
    private val tooltips = mutableMapOf<DelayedTooltipState, Any?>()
    private val popups = mutableMapOf<Any, String>()
    var hasOpenPopup by mutableStateOf(false)
        private set

    fun register(state: DelayedTooltipState, owner: Any? = null) {
        tooltips[state] = owner
        state.suppressed = popups.isNotEmpty() && owner != popups.keys.last()
    }
    fun unregister(state: DelayedTooltipState) { tooltips.remove(state) }

    fun openPopup(owner: Any, kind: String) {
        popups[owner] = kind
        hasOpenPopup = true
        updateSuppression()
        log.event("popup") { "Opened $kind" }
    }

    fun closePopup(owner: Any) {
        val kind = popups.remove(owner) ?: return
        hasOpenPopup = popups.isNotEmpty()
        updateSuppression()
        log.event("popup") { "Closed $kind" }
    }

    private fun updateSuppression() {
        val active = popups.keys.lastOrNull()
        tooltips.forEach { (state, owner) -> state.suppressed = active != null && owner != active }
    }

    fun dismiss(): Boolean {
        val open = tooltips.keys.filter { it.hasOpenRequest }
        val visible = open.any { it.isVisible }
        open.forEach { it.dismiss() }
        // A delayed hint should not use up Escape when there is a panel ready to close.
        return visible
    }
}

internal val LocalTooltipDismissals = compositionLocalOf<TooltipDismissals?> { null }
internal val LocalTooltipOwner = compositionLocalOf<Any?> { null }

/** Only the foremost menu may show hints; delayed anchor requests cannot reopen behind it. */
@Composable
internal fun PopupTooltipScope(kind: String, content: @Composable () -> Unit) {
    val dismissals = LocalTooltipDismissals.current
    val owner = remember { Any() }
    DisposableEffect(dismissals, owner) {
        dismissals?.openPopup(owner, kind)
        onDispose { dismissals?.closePopup(owner) }
    }
    CompositionLocalProvider(LocalTooltipOwner provides owner, content = content)
}

/** Popup owners have a separate keyboard route from the workspace beneath them. */
@Composable
internal fun Modifier.dismissPopupOnEscape(onDismiss: () -> Unit): Modifier {
    val tooltips = LocalTooltipDismissals.current
    val keys = remember { ShortcutKeys() }
    return onPreviewKeyEvent { event ->
        keys.observe(event) || keys.dismiss(event) {
            tooltips?.dismiss()
            onDismiss()
            true
        }
    }
}
