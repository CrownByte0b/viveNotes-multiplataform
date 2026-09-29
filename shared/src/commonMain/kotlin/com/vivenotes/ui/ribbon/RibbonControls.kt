package com.vivenotes.ui.ribbon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.shell.WorkspaceTestTags
import androidx.compose.material3.LocalContentColor

/** Pieces shared by the File, Draw, Document and View ribbon tabs. */

@Composable
internal fun RibbonBar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    spacing: Dp = 6.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(46.dp)
        .background(RibbonStyle.background).ribbonBottomBorder()
        .testTag(WorkspaceTestTags.RibbonBar)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).then(modifier)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Dense icon controls keep their tooltip while sharing the flat hover and underline treatment. */
@Composable
internal fun RibbonIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    tag: String = "document-${label.lowercase().replace(' ', '-')}",
    twoTone: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = when {
        !enabled -> RibbonStyle.disabledText
        selected -> RibbonStyle.activeText
        hovered -> RibbonStyle.hoverText
        else -> RibbonStyle.normalText
    }
    HoverTooltip(label) {
        Box(
            Modifier.size(width = 40.dp, height = 46.dp)
                .background(if (hovered && enabled) RibbonStyle.hover else Color.Transparent)
                .ribbonActiveIndicator(selected)
                .hoverable(interaction, enabled = enabled)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                    role = Role.Button, onClick = onClick)
                .testTag(tag)
                .semantics { contentDescription = label; this.selected = selected },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (twoTone) Color.Unspecified else tint,
                modifier = Modifier.size(18.dp).alpha(if (enabled || !twoTone) 1f else 0.72f))
        }
    }
}

/** A labelled ribbon control, with an optional icon and active underline. */
@Composable
internal fun RibbonCommand(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    dropdown: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val textColor = when {
        !enabled -> RibbonStyle.disabledText
        active -> RibbonStyle.activeText
        hovered -> RibbonStyle.hoverText
        else -> RibbonStyle.normalText
    }
    Row(
        modifier = modifier.height(46.dp)
            .background(if (hovered && enabled) RibbonStyle.hover else Color.Transparent)
            .ribbonActiveIndicator(active)
            .hoverable(interaction, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = if (dropdown) Role.DropdownList else Role.Button, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.alpha(if (enabled) 1f else 0.72f)) {
                CompositionLocalProvider(LocalContentColor provides textColor) { icon() }
            }
            Spacer(Modifier.width(7.dp))
        }
        Text(label, style = TextStyle(fontSize = 13.sp), maxLines = 1, color = textColor)
        if (dropdown) {
            Spacer(Modifier.width(4.dp))
            Icon(DocumentSymbols.ArrowDropDown, contentDescription = null, tint = textColor,
                modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun RibbonDivider() {
    Spacer(Modifier.padding(horizontal = 3.dp).width(1.dp).height(22.dp)
        .background(RibbonStyle.divider))
}

@Composable
internal fun RibbonToggle(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    RibbonCommand(label = label, onClick = onClick, modifier = modifier, active = selected, icon = icon)
}

/** A command whose port has not landed yet: readable, but not clickable. */
@Composable
internal fun PendingRibbonAction(label: String, icon: @Composable (() -> Unit)? = null) {
    RibbonCommand(label = label, onClick = {}, enabled = false, icon = icon)
}

@Composable
internal fun PendingRibbonNote() {
    Text("Tools unlock as each port phase lands", style = TextStyle(fontSize = 12.sp),
        color = RibbonStyle.disabledText, modifier = Modifier.padding(horizontal = 8.dp))
}
