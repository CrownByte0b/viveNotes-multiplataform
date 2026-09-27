package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.PaneGroup
import com.vivenotes.ui.components.ToolPane
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.SettingsSymbols
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.ShortcutAction

/** Semantics identifiers for the Hardware pane. */
object HardwareTags {
    const val Open = "settings-hardware-open"
    const val Shortcuts = "hardware-shortcuts"
    const val ResetAll = "hardware-shortcuts-reset-all"
    fun kind(kind: HardwareKind): String = "hardware-${kind.name.lowercase()}"
    fun shortcut(action: ShortcutAction): String = "hardware-shortcut-${action.name}"
    fun reset(action: ShortcutAction): String = "hardware-shortcut-reset-${action.name}"
}

/**
 * The devices the Hardware pane has settings for, as Android's picker names them. Stylus is shown
 * and disabled until the desktop has ink: its settings are all about drawing.
 */
enum class HardwareKind(val label: String) {
    Stylus("Stylus"),
    Keyboard("Keyboard"),
}

/**
 * Settings → Hardware, docked beside the canvas as Android's pane is: the device picker, then the
 * keyboard shortcuts in their groups. A row opens the shortcut's key capture; a changed shortcut
 * has its own reset, and Reset All asks first.
 */
@Composable
internal fun HardwarePane(
    bindings: KeyBindings,
    onEdit: (ShortcutAction) -> Unit,
    onReset: (ShortcutAction) -> Unit,
    onResetAll: () -> Unit,
    onClose: () -> Unit,
) {
    ToolPane(title = "Hardware", onClose = onClose) {
        HardwareKindPicker(selected = HardwareKind.Keyboard)
        Text("Click a shortcut to change it.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        Column(Modifier.fillMaxWidth().testTag(HardwareTags.Shortcuts)) {
            ShortcutAction.groups.forEach { (group, actions) ->
                PaneGroup(group) {
                    actions.forEachIndexed { index, action ->
                        ShortcutRow(
                            action = action,
                            chord = bindings.primary(action),
                            customized = bindings.isCustomized(action),
                            first = index == 0,
                            onEdit = { onEdit(action) },
                            onReset = { onReset(action) },
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onResetAll,
            enabled = bindings.isCustomized,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.End).padding(top = 16.dp).testTag(HardwareTags.ResetAll),
        ) { Text("Reset All…") }
    }
}

/** Stylus and Keyboard as one linked pair of buttons, the way GTK groups a view switcher. */
@Composable
private fun HardwareKindPicker(selected: HardwareKind) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(34.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        HardwareKind.entries.forEachIndexed { index, kind ->
            if (index > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val enabled = kind == HardwareKind.Keyboard
            val segment = @Composable {
                Row(
                    modifier = Modifier.fillMaxSize()
                        .background(if (kind == selected) LocalDesktopColors.current.selection else Color.Transparent)
                        .selectable(selected = kind == selected, enabled = enabled, role = Role.Tab, onClick = {})
                        .alpha(if (enabled) 1f else 0.42f)
                        .testTag(HardwareTags.kind(kind)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (kind == HardwareKind.Stylus) SettingsSymbols.Stylus else SettingsSymbols.Keyboard,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(kind.label, style = MaterialTheme.typography.labelLarge)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (enabled) segment() else HoverTooltip("Stylus settings arrive with ink") { segment() }
            }
        }
    }
}

/** One shortcut: its name, its keys (or Disabled), and a reset once it has been changed. */
@Composable
private fun ShortcutRow(
    action: ShortcutAction,
    chord: KeyChord?,
    customized: Boolean,
    first: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClickLabel = "Change shortcut", onClick = onEdit)
            .testTag(HardwareTags.shortcut(action))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (chord != null) {
            Keycap(chord.label)
        } else {
            Text("Disabled", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The slot stays when empty, so every row's keys line up.
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (customized) {
                TooltipIconButton("Reset ${action.label} shortcut", onClick = onReset,
                    modifier = Modifier.size(32.dp).testTag(HardwareTags.reset(action))) {
                    Icon(SettingsSymbols.RestartAlt, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A chord as it is printed on the keys: "Ctrl+Shift+Z" in a small raised box. */
@Composable
internal fun Keycap(label: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = if (large) 14.dp else 7.dp, vertical = if (large) 8.dp else 3.dp),
    ) {
        Text(label, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
