package com.vivenotes.ui.ribbon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.LocalDesktopColors

/**
 * Pieces every ribbon tab is built from. Each tab lives in its own package beside this file —
 * `document`, `draw`, `file`, `view`, `settings` — with its buttons and the commands they run.
 */

/** The strip under the tab row that holds one tab's controls, scrolling sideways when narrow. */
@Composable
internal fun RibbonBar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    spacing: Dp = 4.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(modifier = Modifier.testTag(WorkspaceTestTags.RibbonBar),
        color = LocalDesktopColors.current.toolbar) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .horizontalScroll(rememberScrollState())
                .then(modifier)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A ribbon icon command. Its [label] is both what a screen reader says and the hover tooltip. */
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
    val colors = MaterialTheme.colorScheme
    TooltipIconButton(
        label = label,
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) LocalDesktopColors.current.selection else Color.Transparent,
        ),
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .testTag(tag)
            .semantics { this.selected = selected },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (twoTone) Color.Unspecified else if (selected) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun RibbonDivider() {
    Spacer(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A compact desktop toggle for toolbar modes such as Select and Lasso. */
@Composable
internal fun RibbonToggle(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        color = if (selected) LocalDesktopColors.current.selection else LocalDesktopColors.current.toolbar,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

/** A command whose port has not landed yet: shown, labelled, and not clickable. */
@Composable
internal fun PendingRibbonAction(label: String) {
    OutlinedButton(onClick = {}, enabled = false, shape = MaterialTheme.shapes.small) { Text(label) }
}

/** Says why a tab's commands are disabled. */
@Composable
internal fun PendingRibbonNote() {
    Text(
        text = "Tools unlock as each port phase lands",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
