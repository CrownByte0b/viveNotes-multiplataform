package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.shell.WorkspaceTestTags

@Composable
internal fun SelectionGroupButton(grouped: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = if (grouped) Strings.ungroup else Strings.group
    val foreground = Color(0xffe8eaed)
    HoverTooltip(label, position = TooltipAnchorPosition.Above) {
        TextButton(onClick = onClick, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground,
                disabledContentColor = foreground.copy(alpha = .38f)),
            contentPadding = PaddingValues(horizontal = 7.dp),
            modifier = Modifier.height(32.dp).testTag(WorkspaceTestTags.InkGroup)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
