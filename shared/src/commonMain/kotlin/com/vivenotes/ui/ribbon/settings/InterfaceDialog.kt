package com.vivenotes.ui.ribbon.settings

import com.vivenotes.localization.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.icons.ViewSymbols
import kotlin.math.roundToInt

object InterfaceTags {
    const val Open = "settings-interface-open"
    const val Dialog = "settings-interface-dialog"
    const val Backdrop = "settings-interface-backdrop"
    const val DisplayScale = "settings-display-scale"
    const val UiScale = "settings-ui-scale"
    const val FontScale = "settings-font-scale"
    const val ThemeSwitch = "settings-theme-switch"
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
    val darkTheme = settings.darkTheme ?: (MaterialTheme.colorScheme.background.luminance() < 0.45f)
    DesktopDialogFrame(
        title = Strings.interface_label,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(InterfaceTags.Dialog),
        backdropTag = InterfaceTags.Backdrop,
        maxWidth = 540.dp,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(ViewSymbols.WbSunny, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Switch(
                        checked = darkTheme,
                        onCheckedChange = { onChange(settings.copy(darkTheme = it)) },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag(InterfaceTags.ThemeSwitch)
                            .semantics {
                                contentDescription = Strings.app_theme
                                stateDescription = if (darkTheme) Strings.dark else Strings.light
                            },
                    )
                    Icon(ViewSymbols.DarkMode, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                Text(Strings.adjust_the_workspace_for_your_monitor_changes_appear_while_you_move,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScaleSlider(Strings.display_scale_dpi, Strings.scales_the_whole_app_for_low_or_4k_resolution_monitors,
                    settings.displayScale, InterfaceSettings.DisplayScaleRange, InterfaceTags.DisplayScale) {
                    onChange(settings.copy(displayScale = it))
                }
                ScaleSlider(Strings.ui_size, Strings.changes_controls_and_spacing, settings.uiScale,
                    InterfaceSettings.UiScaleRange, InterfaceTags.UiScale) {
                    onChange(settings.copy(uiScale = it))
                }
                ScaleSlider(Strings.font_size, Strings.changes_text_independently_of_ui_size, settings.fontScale,
                    InterfaceSettings.FontScaleRange, InterfaceTags.FontScale) {
                    onChange(settings.copy(fontScale = it))
                }
                Surface(color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium) {
                    Text(Strings.the_quick_brown_fox_jumps_over_the_lazy_dog,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.displayScale * settings.fontScale))
                }
            }
        },
        actions = {
            TextButton(onClick = { onChange(InterfaceSettings()) }, modifier = Modifier.testTag(InterfaceTags.Reset)) {
                Text(Strings.reset)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(InterfaceTags.Cancel)) { Text(Strings.cancel) }
            Button(onClick = onApply, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(InterfaceTags.Apply)) { Text(Strings.apply) }
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
            Text(Strings.value_value_b8d94b(label, (value * 100).roundToInt()), style = MaterialTheme.typography.titleSmall)
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
