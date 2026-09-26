package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.PendingRibbonNote
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.workspace.WorkspaceState

object DrawRibbonTags {
    const val PointerTool = "workspace-pointer-tool"
    const val ObjectLasso = "workspace-object-lasso"
}

/** The Draw tab: the pointer and lasso work; the ink tools arrive with the ink port. */
@Composable
internal fun DrawRibbon(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    RibbonBar(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), spacing = 8.dp) {
        FilterChip(
            selected = !state.textToolArmed && !state.objectLassoArmed,
            onClick = { onStateChange { it.selectPointer() } },
            label = { Text("Select") },
            modifier = Modifier.testTag(DrawRibbonTags.PointerTool),
        )
        PendingRibbonAction("Pen")
        PendingRibbonAction("Highlighter")
        PendingRibbonAction("Eraser")
        FilterChip(
            selected = state.objectLassoArmed,
            onClick = { onStateChange { it.toggleObjectLasso() } },
            label = { Text("Lasso") },
            modifier = Modifier.testTag(DrawRibbonTags.ObjectLasso),
        )
        PendingRibbonAction("Shape")
        PendingRibbonAction("Ruler")
        PendingRibbonNote()
    }
}
