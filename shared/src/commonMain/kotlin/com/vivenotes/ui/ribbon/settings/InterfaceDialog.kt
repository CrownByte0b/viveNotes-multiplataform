package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.DesktopDialogFrame
import kotlin.math.roundToInt

object InterfaceTags {
    const val Open = "settings-interface-open"
    const val Dialog = "settings-interface-dialog"
    const val Backdrop = "settings-interface-backdrop"
    const val DisplayScale = "settings-display-scale"
    const val UiScale = "settings-ui-scale"
    const val FontScale = "settings-font-scale"
    const val Apply = "settings-interface-apply"
    const val Cancel = "settings-interface-cancel"
    const val Reset = "settings-interface-reset"
}

@Composable
internal fun InterfaceDialog(
    settings: InterfaceSettings,
    onChange: (InterfaceSettings) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    DesktopDialogFrame(
        title = "Interface",
        onDismiss = onDismiss,
        modifier = Modifier.testTag(InterfaceTags.Dialog),
        backdropTag = InterfaceTags.Backdrop,
        maxWidth = 540.dp,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Adjust the workspace for your monitor. Changes appear while you move a slider.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScaleSlider("Display scale (DPI)", "Scales the whole app for low or 4K resolution monitors",
                    settings.displayScale, InterfaceSettings.DisplayScaleRange, InterfaceTags.DisplayScale) {
                    onChange(settings.copy(displayScale = it))
                }
                ScaleSlider("UI size", "Changes controls and spacing", settings.uiScale,
                    InterfaceSettings.UiScaleRange, InterfaceTags.UiScale) {
                    onChange(settings.copy(uiScale = it))
                }
                ScaleSlider("Font size", "Changes text independently of UI size", settings.fontScale,
                    InterfaceSettings.FontScaleRange, InterfaceTags.FontScale) {
                    onChange(settings.copy(fontScale = it))
                }
                Surface(color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium) {
                    Text("The quick brown fox jumps over the lazy dog.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.displayScale * settings.fontScale))
                }
            }
        },
        actions = {
            TextButton(onClick = { onChange(InterfaceSettings()) }, modifier = Modifier.testTag(InterfaceTags.Reset)) {
                Text("Reset")
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(InterfaceTags.Cancel)) { Text("Cancel") }
            Button(onClick = onApply, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(InterfaceTags.Apply)) { Text("Apply") }
        },
    )
}

@Composable
private fun ScaleSlider(
    label: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    onChange: (Float) -> Unit,
) {
    val trackAlignment = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Alignment.CenterEnd
        else Alignment.CenterStart
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("$label: ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = value, onValueChange = onChange, valueRange = range,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(tag)
                    .semantics { contentDescription = label },
                thumb = {
                    Box(Modifier.size(18.dp)
                        .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
                },
                track = { state ->
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = trackAlignment) {
                        Box(Modifier.fillMaxWidth(state.coercedValueAsFraction).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary))
                    }
                })
        }
    }
}
