package com.vivenotes.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.theme.LocalDesktopColors

/** A compact in-window action dialog, avoiding a second native popup layer on Wayland. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 440.dp,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    backdropTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().semantics { paneTitle = title }.onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                onDismiss()
                true
            } else false
        },
    ) {
        Box(
            Modifier.matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = onDismiss)
                .then(if (backdropTag != null) Modifier.testTag(backdropTag) else Modifier),
        )
        Surface(
            modifier = Modifier.align(Alignment.Center).widthIn(max = maxWidth).fillMaxWidth(0.9f)
                .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(0.dp))
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .then(modifier),
            shape = MaterialTheme.shapes.large,
            color = LocalDesktopColors.current.dialog,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    content()
                }
                Spacer(Modifier.size(20.dp))
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions)
            }
        }
    }
}
