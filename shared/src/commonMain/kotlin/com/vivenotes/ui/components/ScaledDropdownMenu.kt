package com.vivenotes.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
    CompositionLocalProvider(LocalDensity provides layerDensity) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
            CompositionLocalProvider(LocalDensity provides contentDensity) { content() }
        }
    }
}
