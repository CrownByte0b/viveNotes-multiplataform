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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.ui.shell.WorkspaceTestTags

internal const val LINK_PREVIEWS_TAG = "settings-link-previews"

private val Strip = Color(0xFF292A2F)
private val Divider = Color(0xFF393B42)
private val Hover = Color(0xFF32343A)
private val NormalText = Color(0xFFA8ABB4)
private val HoverText = Color(0xFFE8EAF0)
private val ActiveText = Color(0xFFEAF4FF)
private val Indicator = Color(0xFF007FFF)

/** Settings commands share a compact tab strip; active preferences and panes get an underline. */
@Composable
internal fun SettingsRibbon(onInterface: () -> Unit, hardwareOpen: Boolean, onHardware: () -> Unit,
    linkPreviews: Boolean, onLinkPreviewsChange: (Boolean) -> Unit, onAbout: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(46.dp)
            .background(Strip)
            .drawBehind {
                drawRect(Divider, topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(size.width, 1.dp.toPx()))
            }
            .testTag(WorkspaceTestTags.RibbonBar),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTab("Interface", tag = InterfaceTags.Open, onClick = onInterface)
            SettingsTab("Appearance", enabled = false)
            SettingsTab("Link previews", tag = LINK_PREVIEWS_TAG, active = linkPreviews,
                onClick = { onLinkPreviewsChange(!linkPreviews) })
            SettingsTab("Hardware", tag = HardwareTags.Open, active = hardwareOpen, onClick = onHardware)
            SettingsTab("Models", enabled = false)
            SettingsTab("About", tag = AboutTags.Open, onClick = onAbout)
        }
    }
}

@Composable
private fun SettingsTab(label: String, tag: String = "settings-${label.lowercase()}",
    active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier.height(46.dp)
            .background(if (hovered && enabled) Hover else Color.Transparent)
            .drawBehind {
                if (active) {
                    drawRect(Indicator, topLeft = Offset(12.dp.toPx(), size.height - 2.dp.toPx()),
                        size = Size(size.width - 24.dp.toPx(), 2.dp.toPx()))
                }
            }
            .hoverable(interaction, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { selected = active },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                active -> ActiveText
                hovered && enabled -> HoverText
                enabled -> NormalText
                else -> NormalText.copy(alpha = 0.45f)
            },
            style = TextStyle(fontSize = 13.sp),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}
