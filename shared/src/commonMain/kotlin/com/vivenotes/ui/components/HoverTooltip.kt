package com.vivenotes.ui.components

import androidx.compose.foundation.MutatorMutex
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Shows [label] in a Material 3 plain tooltip while the mouse is over [content].
 *
 * The app's rule for every control whose only visible label is an icon or a swatch: the words a
 * screen reader hears are the words the mouse finds, so pass the control's accessibility label.
 * Touch and keyboard users get the same tooltip from Material's long press and focus handling.
 *
 * Each tooltip has its own [MutatorMutex] rather than Material's app-wide one. A click focuses the
 * button it lands on, and Material shows that button's tooltip at keyboard-focus priority for 1.5 s;
 * through the shared mutex that refused every hover tooltip meanwhile — the swatches of the menu
 * the click had just opened, or the next ribbon button the mouse reached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoverTooltip(
    label: String,
    modifier: Modifier = Modifier,
    position: TooltipAnchorPosition = TooltipAnchorPosition.Below,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(position),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(mutatorMutex = remember { MutatorMutex() }),
        modifier = modifier,
        content = content,
    )
}

/**
 * An [IconButton] whose accessibility label is also its hover tooltip, so the two cannot differ.
 * The icon inside should have a null content description: this button carries [label].
 */
@Composable
fun TooltipIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    tooltipPosition: TooltipAnchorPosition = TooltipAnchorPosition.Below,
    icon: @Composable () -> Unit,
) {
    HoverTooltip(label, position = tooltipPosition) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = colors,
            modifier = modifier.semantics { contentDescription = label },
            content = icon,
        )
    }
}
