package com.vivenotes.ui.ribbon.view

import com.vivenotes.localization.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.DefaultChoiceItem
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.icons.pageColorGlyph
import com.vivenotes.ui.icons.rememberViewRibbonIcons
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonCommand
import com.vivenotes.ui.ribbon.RibbonDivider
import com.vivenotes.ui.ribbon.RibbonIcon
import com.vivenotes.ui.ribbon.RibbonStyle
import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import kotlin.math.roundToInt

/** Semantics identifiers for the View tab's controls and their menus. */
internal object ViewRibbonTags {
    const val TabsLayout = "view-tabs-layout"
    fun tabsLayout(layout: com.vivenotes.workspace.TabsLayout): String = "view-tabs-layout-${layout.name}"
    const val Zoom = "view-zoom"
    fun zoomStep(step: Float): String = "view-zoom-step-${(step * 100).roundToInt()}"
    const val ZoomIn = "view-zoom-in"
    const val ZoomOut = "view-zoom-out"
    const val ActualSize = "view-actual-size"
    const val PageWidth = "view-page-width"
    const val Paper = "view-paper"
    fun ruleLines(rule: RuleLines): String = "view-paper-${rule.name}"
    const val PageColor = "view-page-color"
    fun pageColor(argb: Int): String = "view-page-color-$argb"
    const val NoPageColor = "view-page-color-none"
    const val PaperSize = "view-paper-size"
    const val HideTitle = "view-hide-title"
    const val SwitchBackground = "view-switch-background"
}

/**
 * What the View tab can do. The ribbon holds no workspace reference — it is handed values and these
 * callbacks, as Android's `ViewActions` does — so its wiring is tested on its own.
 */
@Immutable
internal data class ViewActions(
    val setRuleLines: (RuleLines) -> Unit,
    val setPageColor: (Int?) -> Unit,
    val setHideTitle: (Boolean) -> Unit,
    val setPaperSize: (PaperSize) -> Unit,
    val setOrientation: (Orientation) -> Unit,
    val setCustomPaper: (PaperDimensions) -> Unit,
    val setMargins: (PrintMargins) -> Unit,
    val setZoom: (Float) -> Unit,
    val zoomIn: () -> Unit,
    val zoomOut: () -> Unit,
    val zoomToPageWidth: () -> Unit,
    val setTabsLayout: (TabsLayout) -> Unit,
    val setCanvasDark: (Boolean) -> Unit,
    /** Opens the docked Paper Size pane, or closes it when it is already open. */
    val togglePaperSizePane: () -> Unit,
)

/** Android's page colours, named for screen readers and hover. */
internal val PageColors get() = listOf(
    0xFFFFFFFF to Strings.white, 0xFFFFF8E7 to Strings.cream, 0xFFFDF1F4 to Strings.blush, 0xFFEFF5FC to Strings.sky,
    0xFFEFF7EF to Strings.mint, 0xFFF5F0FA to Strings.lavender, 0xFFF7F3EC to Strings.linen, 0xFFECF6F6 to Strings.aqua,
    0xFF1F1F1F to Strings.charcoal, 0xFF17232E to Strings.navy, 0xFF1D2A1D to Strings.forest, 0xFF2A1E2A to Strings.plum,
).map { (argb, name) -> argb.toInt() to name }

/** The ruling choices Android offers, in its order; the divider goes before Dotted. */
internal val RuleLineChoices get() = listOf(
    RuleLines.None to Strings.none,
    RuleLines.Standard to Strings.standard_ruled,
    RuleLines.Wide to Strings.wide_ruled,
    RuleLines.Dotted to Strings.dotted_paper,
    RuleLines.Hexagonal to Strings.hexagonal_paper,
    RuleLines.GridMedium to Strings.medium_grid,
    RuleLines.GridLarge to Strings.large_grid,
)

/**
 * The View tab, in the Android tab's order: Tabs Layout; the zoom group; Paper, Page Color,
 * Paper Size and Hide Page Title for the open page; Switch Background for the canvas.
 */
@Composable
internal fun ViewRibbon(
    style: PageStyle,
    pageOpen: Boolean,
    settings: ViewSettings,
    /** What the canvas currently is: what Switch Background flips. */
    canvasDark: Boolean,
    actions: ViewActions,
    defaultRuleLines: RuleLines = RuleLines.GridMedium,
    onDefaultRuleLines: (RuleLines) -> Unit = {},
) {
    val (idle, active) = rememberViewRibbonIcons(RibbonStyle.normalText, RibbonStyle.activeText,
        RibbonStyle.accent, Color(0xFFE94C4F))
    RibbonBar {
        TabsLayoutMenu(settings.tabsLayout, idle.tabsLayout, actions.setTabsLayout)
        RibbonDivider()
        ZoomPicker(settings.zoom, actions.setZoom)
        RibbonIcon(ViewSymbols.ZoomIn, Strings.zoom_in, enabled = settings.zoom < ViewSettings.MAX_ZOOM,
            tag = ViewRibbonTags.ZoomIn, onClick = actions.zoomIn)
        RibbonIcon(ViewSymbols.ZoomOut, Strings.zoom_out, enabled = settings.zoom > ViewSettings.MIN_ZOOM,
            tag = ViewRibbonTags.ZoomOut, onClick = actions.zoomOut)
        RibbonCommand(Strings.label_100, onClick = { actions.setZoom(1f) },
            modifier = Modifier.testTag(ViewRibbonTags.ActualSize)) { MonoIcon(ViewSymbols.Article) }
        RibbonCommand(Strings.page_width, onClick = actions.zoomToPageWidth, enabled = pageOpen,
            modifier = Modifier.testTag(ViewRibbonTags.PageWidth)) { TwoToneIcon(idle.pageWidth) }
        RibbonDivider()
        RuleLinesMenu(style.ruleLines, defaultRuleLines, pageOpen, idle.ruleLines, active.ruleLines,
            actions.setRuleLines, onDefaultRuleLines)
        PageColorMenu(style.backgroundArgb, pageOpen, canvasDark, actions.setPageColor)
        // A pane rather than a menu: six fields in two groups, which has to stay open while the
        // page changes shape beside it.
        RibbonCommand(Strings.paper_size, onClick = actions.togglePaperSizePane,
            active = style.paper != PaperSize.Auto, enabled = pageOpen,
            modifier = Modifier.testTag(ViewRibbonTags.PaperSize)) {
            TwoToneIcon(if (style.paper != PaperSize.Auto) active.paperSize else idle.paperSize)
        }
        RibbonCommand(Strings.hide_page_title, onClick = { actions.setHideTitle(!style.hideTitle) },
            active = style.hideTitle, enabled = pageOpen, modifier = Modifier.testTag(ViewRibbonTags.HideTitle)) {
            TwoToneIcon(if (style.hideTitle) active.hidePageTitle else idle.hidePageTitle)
        }
        RibbonCommand(Strings.switch_background, onClick = { actions.setCanvasDark(!canvasDark) },
            modifier = Modifier.testTag(ViewRibbonTags.SwitchBackground)) { MonoIcon(ViewSymbols.WbSunny) }
    }
}

@Composable
private fun MonoIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = LocalContentColor.current,
        modifier = Modifier.size(18.dp))
}

@Composable
private fun TwoToneIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
}

@Composable
private fun ZoomPicker(zoom: Float, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HoverTooltip(Strings.zoom_level) {
            RibbonCommand(Strings.value((zoom * 100).roundToInt()), onClick = { open = true },
                dropdown = true, modifier = Modifier.testTag(ViewRibbonTags.Zoom)
                    .semantics { contentDescription = Strings.zoom_level })
        }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ViewSettings.ZOOM_STEPS.forEach { step ->
                DropdownMenuItem(
                    text = { Text(Strings.value((step * 100).roundToInt())) },
                    onClick = {
                        open = false
                        onPick(step)
                    },
                    modifier = Modifier.testTag(ViewRibbonTags.zoomStep(step)),
                )
            }
        }
    }
}

@Composable
private fun TabsLayoutMenu(current: TabsLayout, icon: ImageVector, onPick: (TabsLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonCommand(Strings.tabs_layout, onClick = { open = true }, dropdown = true,
            modifier = Modifier.testTag(ViewRibbonTags.TabsLayout)) { TwoToneIcon(icon) }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(TabsLayout.Vertical to Strings.vertical_tabs, TabsLayout.Horizontal to Strings.horizontal_tabs)
                .forEach { (layout, label) ->
                    CheckableItem(label, layout == current, ViewRibbonTags.tabsLayout(layout)) {
                        open = false
                        onPick(layout)
                    }
                }
        }
    }
}

@Composable
private fun RuleLinesMenu(
    current: RuleLines,
    default: RuleLines,
    pageOpen: Boolean,
    idle: ImageVector,
    active: ImageVector,
    onPick: (RuleLines) -> Unit,
    onSetDefault: (RuleLines) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonCommand(Strings.paper, onClick = { open = true }, active = current != RuleLines.None,
            enabled = pageOpen, dropdown = true, modifier = Modifier.testTag(ViewRibbonTags.Paper)) {
            TwoToneIcon(if (current != RuleLines.None) active else idle)
        }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RuleLineChoices.forEach { (rule, label) ->
                if (rule == RuleLines.Dotted) HorizontalDivider()
                DefaultChoiceItem(label, rule == current, rule == default, ViewRibbonTags.ruleLines(rule),
                    onChoose = { open = false; onPick(rule) },
                    onSetDefault = { open = false; onSetDefault(rule) })
            }
        }
    }
}

@Composable
private fun PageColorMenu(current: Int?, pageOpen: Boolean, canvasDark: Boolean, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val neutral = RibbonStyle.normalText
    // With no colour of its own, the page shows the canvas's; the bar says so.
    val swatch = current?.let(::Color) ?: if (canvasDark) Color(0xFF1F1F1F) else Color.White
    val icon = remember(neutral, swatch) { pageColorGlyph(neutral, swatch) }
    Box {
        RibbonCommand(Strings.page_color, onClick = { open = true }, enabled = pageOpen, dropdown = true,
            modifier = Modifier.testTag(ViewRibbonTags.PageColor)) { TwoToneIcon(icon) }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(8.dp)) {
                PageColors.chunked(4).forEach { row ->
                    Row {
                        row.forEach { (argb, name) ->
                            val chosen = argb == current
                            HoverTooltip(name) {
                                Box(
                                    Modifier.padding(3.dp).size(28.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(argb))
                                        .border(if (chosen) 2.dp else 1.dp,
                                            if (chosen) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(5.dp))
                                        .clickable(role = Role.Button) {
                                            open = false
                                            onPick(argb)
                                        }
                                        .semantics {
                                            contentDescription = name
                                            selected = chosen
                                        }
                                        .testTag(ViewRibbonTags.pageColor(argb)),
                                )
                            }
                        }
                    }
                }
                CheckableItem(Strings.no_color, current == null, ViewRibbonTags.NoPageColor) {
                    open = false
                    onPick(null)
                }
            }
        }
    }
}

/** A menu row showing whether it is the current choice; the tick's slot is kept either way. */
@Composable
private fun CheckableItem(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (selected) {
                Icon(ViewSymbols.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
            } else {
                Spacer(Modifier.width(18.dp))
            }
        },
        onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { this.selected = selected },
    )
}
