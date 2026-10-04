package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.DrawSymbols
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonToggle
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.ShapeToolSettings

object DrawRibbonTags {
    const val PointerTool = "workspace-pointer-tool"
    const val PenTool = "workspace-pen-tool"
    const val HighlighterTool = "workspace-highlighter-tool"
    const val EraserTool = "workspace-eraser-tool"
    const val ObjectLasso = "workspace-object-lasso"
}

/** The Draw tab's shape tool shares the canvas selection and clipboard with other Prime Objects. */
@Composable
internal fun DrawRibbon(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onShapeSettingsChange: (ShapeToolSettings) -> Unit = {},
) {
    var shapeMenuOpen by remember { mutableStateOf(false) }
    RibbonBar {
        RibbonToggle(
            label = "Select",
            selected = state.inkTool == null && !state.textToolArmed && !state.objectLassoArmed && !state.shapeToolArmed,
            onClick = { onStateChange { it.selectPointer() } },
            modifier = Modifier.testTag(DrawRibbonTags.PointerTool),
            icon = { Icon(DrawSymbols.Select, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        RibbonToggle(
            label = "Pen", selected = state.inkTool == InkTool.Pen,
            enabled = state.selectedPage?.let { it.editable && it.inkReady } == true &&
                !state.notebookTransfer.running && !state.filePane.busy,
            onClick = { onStateChange { it.toggleInkTool(InkTool.Pen) } },
            modifier = Modifier.testTag(DrawRibbonTags.PenTool),
            icon = { Icon(DrawSymbols.Pen, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        RibbonToggle(
            label = "Highlighter", selected = state.inkTool == InkTool.Highlighter,
            enabled = state.selectedPage?.let { it.editable && it.inkReady } == true &&
                !state.notebookTransfer.running && !state.filePane.busy,
            onClick = { onStateChange { it.toggleInkTool(InkTool.Highlighter) } },
            modifier = Modifier.testTag(DrawRibbonTags.HighlighterTool),
            icon = { Icon(DrawSymbols.Highlighter, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        RibbonToggle(
            label = "Eraser", selected = state.inkTool == InkTool.Eraser,
            enabled = state.selectedPage?.let { it.editable && it.inkReady } == true &&
                !state.notebookTransfer.running && !state.filePane.busy,
            onClick = { onStateChange { it.toggleInkTool(InkTool.Eraser) } },
            modifier = Modifier.testTag(DrawRibbonTags.EraserTool),
            icon = { Icon(DrawSymbols.Eraser, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        RibbonToggle(
            label = "Lasso",
            selected = state.objectLassoArmed,
            onClick = { onStateChange { it.toggleObjectLasso() } },
            modifier = Modifier.testTag(DrawRibbonTags.ObjectLasso),
            icon = { Icon(DrawSymbols.Lasso, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        Box {
            RibbonToggle(
                label = "Shape",
                selected = state.shapeToolArmed,
                onClick = {
                    if (state.shapeToolArmed) shapeMenuOpen = !shapeMenuOpen
                    else onStateChange { it.toggleShapeTool() }
                },
                modifier = Modifier.testTag(ShapeMenuTags.ShapeTool),
                icon = { Icon(DrawSymbols.Shape, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            ShapeMenu(shapeMenuOpen, state.shapeSettings, onDismiss = { shapeMenuOpen = false },
                onChange = { settings ->
                    onStateChange { it.setShapeSettings(settings) }
                    onShapeSettingsChange(settings)
                })
        }
        PendingRibbonAction("Ruler") {
            Icon(DrawSymbols.Ruler, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
