package com.vivenotes.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.focusable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity

/** Places a desktop popup in host-window pixels while sizing its content at the chosen UI DPI. */
@Composable
internal fun ScaledDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentDensity = LocalDensity.current
    val layerDensity = LocalPopupLayerDensity.current ?: contentDensity
    val motionScheme = MaterialTheme.motionScheme
    val popupMotion = remember(motionScheme) { ImmediatePopupMotionScheme(motionScheme) }
    val menuFocus = remember { FocusRequester() }
    CompositionLocalProvider(LocalDensity provides layerDensity) {
        MaterialTheme(motionScheme = popupMotion) {
            DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
                modifier = modifier.dismissPopupOnEscape(onDismissRequest).focusRequester(menuFocus).focusable()) {
                LaunchedEffect(Unit) { menuFocus.requestFocus() }
                PopupTooltipScope("dropdown") {
                    CompositionLocalProvider(LocalDensity provides contentDensity) {
                        MaterialTheme(motionScheme = motionScheme) { content() }
                    }
                }
            }
        }
    }
}
