package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.DrawSymbols
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonToggle
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
    RibbonBar {
        RibbonToggle(
            label = "Select",
            selected = !state.textToolArmed && !state.objectLassoArmed,
            onClick = { onStateChange { it.selectPointer() } },
            modifier = Modifier.testTag(DrawRibbonTags.PointerTool),
            icon = { Icon(DrawSymbols.Select, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        PendingRibbonAction("Pen") {
            Icon(DrawSymbols.Pen, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        PendingRibbonAction("Highlighter") {
            Icon(DrawSymbols.Highlighter, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        PendingRibbonAction("Eraser") {
            Icon(DrawSymbols.Eraser, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        RibbonToggle(
            label = "Lasso",
            selected = state.objectLassoArmed,
            onClick = { onStateChange { it.toggleObjectLasso() } },
            modifier = Modifier.testTag(DrawRibbonTags.ObjectLasso),
            icon = { Icon(DrawSymbols.Lasso, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        PendingRibbonAction("Shape") {
            Icon(DrawSymbols.Shape, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        PendingRibbonAction("Ruler") {
            Icon(DrawSymbols.Ruler, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
