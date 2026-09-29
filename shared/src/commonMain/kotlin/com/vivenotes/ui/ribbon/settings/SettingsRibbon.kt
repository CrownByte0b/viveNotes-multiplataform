package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.icons.aboutGlyph
import com.vivenotes.ui.icons.hardwareGlyph
import com.vivenotes.ui.icons.integratedGlyph
import com.vivenotes.ui.icons.linkPreviewGlyph
import com.vivenotes.ui.ribbon.RibbonStyle
import com.vivenotes.ui.ribbon.ribbonActiveIndicator
import com.vivenotes.ui.ribbon.ribbonBottomBorder
import com.vivenotes.ui.shell.WorkspaceTestTags

internal const val LINK_PREVIEWS_TAG = "settings-link-previews"

/** Settings commands share a compact tab strip; active preferences and panes get an underline. */
@Composable
internal fun SettingsRibbon(onInterface: () -> Unit, hardwareOpen: Boolean, onHardware: () -> Unit,
    linkPreviews: Boolean, onLinkPreviewsChange: (Boolean) -> Unit, onAbout: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(46.dp)
            .background(RibbonStyle.background)
            .ribbonBottomBorder()
            .testTag(WorkspaceTestTags.RibbonBar),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Android has no Interface command; its Material sun symbol identifies display settings here.
            SettingsTab("Interface", icon = { _, _ -> ViewSymbols.WbSunny }, monochrome = true,
                tag = InterfaceTags.Open, onClick = onInterface)
            SettingsTab("Link previews", icon = ::linkPreviewGlyph,
                tag = LINK_PREVIEWS_TAG, active = linkPreviews,
                onClick = { onLinkPreviewsChange(!linkPreviews) })
            SettingsTab("Hardware", icon = ::hardwareGlyph,
                tag = HardwareTags.Open, active = hardwareOpen, onClick = onHardware)
            SettingsTab("Models", icon = ::integratedGlyph, enabled = false)
            SettingsTab("About", icon = ::aboutGlyph, tag = AboutTags.Open, onClick = onAbout)
        }
    }
}

@Composable
private fun SettingsTab(label: String, icon: (Color, Color) -> ImageVector,
    tag: String = "settings-${label.lowercase()}", active: Boolean = false,
    enabled: Boolean = true, monochrome: Boolean = false, onClick: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val textColor = when {
        active -> RibbonStyle.activeText
        hovered && enabled -> RibbonStyle.hoverText
        enabled -> RibbonStyle.normalText
        else -> RibbonStyle.disabledText
    }
    val accent = if (enabled) RibbonStyle.accent else RibbonStyle.disabledAccent
    val image = remember(icon, textColor, accent) { icon(textColor, accent) }
    Box(
        modifier = Modifier.height(46.dp)
            .background(if (hovered && enabled) RibbonStyle.hover else Color.Transparent)
            .ribbonActiveIndicator(active)
            .hoverable(interaction, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { selected = active },
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(image, contentDescription = null,
                tint = if (monochrome) textColor else Color.Unspecified,
                modifier = Modifier.size(18.dp).testTag("settings-icon-${label.lowercase().replace(' ', '-')}"))
            Text(label, color = textColor, style = TextStyle(fontSize = 13.sp), maxLines = 1)
        }
    }
}
