package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

object InterfaceTags {
    const val Open = "settings-interface-open"
    const val Dialog = "settings-interface-dialog"
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
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(InterfaceTags.Dialog),
        title = { Text("Interface") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Adjust the workspace for your monitor. Changes appear while you move a slider.",
                    style = MaterialTheme.typography.bodyMedium)
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
                Text("Text preview: The quick brown fox jumps over the lazy dog.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.displayScale * settings.fontScale))
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, modifier = Modifier.testTag(InterfaceTags.Apply)) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = { onChange(InterfaceSettings()) }, modifier = Modifier.testTag(InterfaceTags.Reset)) {
                Text("Reset")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(InterfaceTags.Cancel)) { Text("Cancel") }
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
    Column {
        Text("$label: ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(tag)
                .semantics { contentDescription = label })
    }
}
