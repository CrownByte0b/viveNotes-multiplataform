package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.data.EraserMode
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.DrawSymbols
import com.vivenotes.ui.ribbon.*
import com.vivenotes.workspace.*

object DrawRibbonTags {
    const val PenTool = "workspace-pen-tool"
    fun pen(index: Int) = if (index == 0) PenTool else "$PenTool-$index"
    fun penKit(index: Int) = "draw-pen-kit-$index"
    fun control(tool: String) = "$tool-control"
    fun icon(tool: String) = "$tool-icon"
    const val HighlighterTool = "workspace-highlighter-tool"
    const val HighlighterKit = "draw-highlighter-kit"
    const val EraserTool = "workspace-eraser-tool"
    const val EraserModePicker = "workspace-eraser-mode"
    fun eraserMode(mode: EraserMode) = "workspace-eraser-mode-${mode.name}"
    const val ObjectLasso = "workspace-object-lasso"
    const val ShapeKit = "draw-shape-kit"
    const val RulerTool = "draw-ruler-tool"
    const val RulerKit = "draw-ruler-kit"
}

@Composable
internal fun DrawRibbon(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onShapeSettingsChange: (ShapeToolSettings) -> Unit = {},
    onInkSettingsChange: (InkSettings) -> Unit = {},
    canvasInk: Color = Color.Black,
    calculatorAvailable: Boolean = false,
    calculatorOpen: Boolean = false,
    onToggleCalculator: () -> Unit = {},
) {
    var kit by remember(state.selectedPageId) { mutableStateOf<String?>(null) }
    val enabled = state.selectedPage?.let { it.editable && it.inkReady } == true &&
        !state.notebookTransfer.running && !state.filePane.busy
    fun settings(value: InkSettings) {
        val normalized = value.normalized()
        onStateChange { it.setInkSettings(normalized) }
        onInkSettingsChange(normalized)
    }
    fun ink(tool: InkTool, index: Int = state.activePenIndex, pane: String) {
        if (state.inkTool == tool && (tool != InkTool.Pen || state.activePenIndex == index)) kit = pane
        else { kit = null; onStateChange { it.selectInkTool(tool, index) } }
    }
    RibbonBar(spacing = 2.dp) {
        repeat(3) { index ->
            val pane = DrawRibbonTags.penKit(index)
            SplitTool("Pen ${index + 1}", DrawSymbols.Pen, state.inkTool == InkTool.Pen && state.activePenIndex == index,
                enabled, DrawRibbonTags.pen(index), pane,
                onClick = { ink(InkTool.Pen, index, pane) },
                onKit = { onStateChange { it.selectInkTool(InkTool.Pen, index) }; kit = pane },
                tip = DrawSymbols.PenTip,
                inkColor = if (state.inkSettings.pen(index).colorFollowsTheme) canvasInk else Color(state.inkSettings.pen(index).colorArgb)) {
                ScaledDropdownMenu(kit == pane, { kit = null }) { PenKit(index, state.inkSettings, canvasInk, ::settings) }
            }
        }
        SplitTool("Highlighter", DrawSymbols.Highlighter, state.inkTool == InkTool.Highlighter,
            enabled, DrawRibbonTags.HighlighterTool, DrawRibbonTags.HighlighterKit,
            onClick = { ink(InkTool.Highlighter, pane = DrawRibbonTags.HighlighterKit) },
            onKit = { onStateChange { it.selectInkTool(InkTool.Highlighter) }; kit = DrawRibbonTags.HighlighterKit },
            tip = DrawSymbols.HighlighterTip, inkColor = Color(state.inkSettings.highlighter.colorArgb).copy(alpha = 1f)) {
            ScaledDropdownMenu(kit == DrawRibbonTags.HighlighterKit, { kit = null }) { HighlighterKit(state.inkSettings, ::settings) }
        }
        SplitTool("Eraser", DrawSymbols.Eraser, state.inkTool == InkTool.Eraser,
            enabled, DrawRibbonTags.EraserTool, DrawRibbonTags.EraserModePicker,
            onClick = { ink(InkTool.Eraser, pane = DrawRibbonTags.EraserModePicker) },
            onKit = { onStateChange { it.selectInkTool(InkTool.Eraser) }; kit = DrawRibbonTags.EraserModePicker }) {
            ScaledDropdownMenu(kit == DrawRibbonTags.EraserModePicker, { kit = null }) { EraserKit(state.inkSettings, ::settings) }
        }
        RibbonToggle("Lasso", state.objectLassoArmed, enabled = enabled,
            onClick = { kit = null; onStateChange { it.toggleObjectLasso() } }, modifier = Modifier.testTag(DrawRibbonTags.ObjectLasso),
            icon = { Icon(DrawSymbols.Lasso, null, Modifier.size(18.dp)) })
        RibbonIcon(DrawSymbols.InsertSpace, "Insert Space", state.drawPlacement == DrawPlacementTool.InsertSpace,
            enabled, "draw-insert-space") { kit = null; onStateChange { it.selectDrawPlacement(DrawPlacementTool.InsertSpace) } }
        SplitTool("Shape", DrawSymbols.Shape, state.shapeToolArmed, enabled, ShapeMenuTags.ShapeTool, DrawRibbonTags.ShapeKit,
            onClick = { if (state.shapeToolArmed) kit = DrawRibbonTags.ShapeKit else { kit = null; onStateChange { it.toggleShapeTool() } } },
            onKit = { if (!state.shapeToolArmed) onStateChange { it.toggleShapeTool() }; kit = DrawRibbonTags.ShapeKit }) {
            ShapeMenu(kit == DrawRibbonTags.ShapeKit, state.shapeSettings, { kit = null }) {
                onStateChange { state -> state.setShapeSettings(it) }; onShapeSettingsChange(it)
            }
        }
        SplitTool("Table", DrawSymbols.Table, state.drawPlacement == DrawPlacementTool.Table, enabled, TableTags.Tool, TableTags.Kit,
            onClick = { if (state.drawPlacement == DrawPlacementTool.Table) kit = TableTags.Kit else {
                kit = null; onStateChange { it.selectDrawPlacement(DrawPlacementTool.Table) }
            } }, onKit = { onStateChange { it.selectDrawPlacement(DrawPlacementTool.Table) }; kit = TableTags.Kit }) {
            ScaledDropdownMenu(kit == TableTags.Kit, { kit = null }) { TableKit(state.inkSettings, ::settings) }
        }
        SplitTool("Equation", DocumentSymbols.Function, state.drawPlacement == DrawPlacementTool.Equation,
            enabled, EquationTags.Tool, EquationTags.Kit, onClick = { kit = EquationTags.Kit }, onKit = { kit = EquationTags.Kit }) {
            ScaledDropdownMenu(kit == EquationTags.Kit, { kit = null }) {
                EquationKit { equation -> kit = null; onStateChange { it.armEquation(equation) } }
            }
        }
        SplitTool("Ruler", DrawSymbols.Ruler, state.rulerVisible, enabled, DrawRibbonTags.RulerTool, DrawRibbonTags.RulerKit,
            onClick = { kit = null; onStateChange { it.toggleRuler() } },
            onKit = { onStateChange { it.copy(rulerVisible = true) }; kit = DrawRibbonTags.RulerKit }) {
            ScaledDropdownMenu(kit == DrawRibbonTags.RulerKit, { kit = null }) { RulerKit(state.inkSettings, ::settings) }
        }
        if (calculatorAvailable) RibbonIcon(DrawSymbols.Calculator, "Calculator", calculatorOpen,
            enabled, "draw-calculator", onClick = { kit = null; onToggleCalculator() })
    }
}

@Composable
private fun SplitTool(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, tag: String, kitTag: String,
                      onClick: () -> Unit, onKit: () -> Unit, tip: ImageVector? = null, inkColor: Color = Color.Unspecified,
                      popup: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = when {
        !enabled -> RibbonStyle.disabledText
        selected -> RibbonStyle.activeText
        hovered -> RibbonStyle.hoverText
        else -> RibbonStyle.normalText
    }
    Box {
        Row(Modifier.height(46.dp)
            .background(if (hovered && enabled) RibbonStyle.hover else Color.Transparent)
            .ribbonActiveIndicator(selected)
            .hoverable(interaction, enabled = enabled)
            .testTag(DrawRibbonTags.control(tag)), verticalAlignment = Alignment.CenterVertically) {
            HoverTooltip(label) {
                Box(Modifier.size(width = 36.dp, height = 46.dp)
                    .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                        role = Role.Button, onClick = onClick)
                    .testTag(tag).semantics { contentDescription = label; this.selected = selected }
                    .padding(end = 2.dp), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(18.dp).testTag(DrawRibbonTags.icon(tag))) {
                        if (tip != null) Icon(tip, null, Modifier.fillMaxSize(), tint = inkColor)
                        Icon(icon, null, Modifier.fillMaxSize(), tint = tint)
                    }
                }
            }
            HoverTooltip("$label settings") {
                Box(Modifier.size(width = 18.dp, height = 46.dp)
                    .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                        role = Role.DropdownList, onClick = onKit)
                    .testTag(kitTag).semantics { contentDescription = "$label settings" },
                    contentAlignment = Alignment.Center) {
                    Icon(DocumentSymbols.ArrowDropDown, null, Modifier.size(14.dp), tint = tint)
                }
            }
        }
        popup()
    }
}
