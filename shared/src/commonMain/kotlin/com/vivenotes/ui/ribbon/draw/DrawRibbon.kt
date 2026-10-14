package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.DrawSymbols
import com.vivenotes.ui.ribbon.PendingRibbonAction
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonToggle
import com.vivenotes.ui.ribbon.RibbonCommand
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.data.EraserMode
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.workspace.InkSettings

object DrawRibbonTags {
    const val PointerTool = "workspace-pointer-tool"
    const val PenTool = "workspace-pen-tool"
    const val HighlighterTool = "workspace-highlighter-tool"
    const val EraserTool = "workspace-eraser-tool"
    const val EraserModePicker = "workspace-eraser-mode"
    fun eraserMode(mode: EraserMode) = "workspace-eraser-mode-${mode.name}"
    const val ObjectLasso = "workspace-object-lasso"
}

/** The Draw tab's shape tool shares the canvas selection and clipboard with other Prime Objects. */
@Composable
internal fun DrawRibbon(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onShapeSettingsChange: (ShapeToolSettings) -> Unit = {},
    onInkSettingsChange: (InkSettings) -> Unit = {},
) {
    var shapeMenuOpen by remember { mutableStateOf(false) }
    var eraserMenuOpen by remember { mutableStateOf(false) }
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
        if (state.inkTool == InkTool.Pen || state.inkTool == InkTool.Highlighter) key(state.inkTool) {
            InkControls(state.inkTool, state.inkSettings) { settings ->
                onStateChange { it.setInkSettings(settings) }
                onInkSettingsChange(settings.normalized())
            }
        }
        if (state.inkTool == InkTool.Eraser) Box {
            RibbonCommand("${state.eraserMode.label} eraser", onClick = { eraserMenuOpen = true }, dropdown = true,
                modifier = Modifier.testTag(DrawRibbonTags.EraserModePicker))
            ScaledDropdownMenu(eraserMenuOpen, { eraserMenuOpen = false }) {
                EraserMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(if (mode == EraserMode.Normal) "Normal — cut through ink" else "Object — whole stroke") },
                        onClick = { eraserMenuOpen = false; onStateChange { it.setEraserMode(mode) } },
                        modifier = Modifier.testTag(DrawRibbonTags.eraserMode(mode)))
                }
            }
        }
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
