package com.vivenotes.ui.ribbon.view

import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.ToolPaneTags
import androidx.compose.runtime.Composable
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.ui.components.PaneChoice
import com.vivenotes.ui.components.PaneGroup
import com.vivenotes.ui.components.PaneMeasure
import com.vivenotes.ui.components.PaneRow
import com.vivenotes.ui.components.ToolPane

/** How the Size field names each sheet. Auto is the endless canvas every page starts on. */
internal fun paperSizeLabel(paper: PaperSize): String = when (paper) {
    PaperSize.Auto -> Strings.infinite
    PaperSize.Custom -> Strings.custom
    PaperSize.Billfold -> Strings.billfold
    else -> paper.name
}

internal fun orientationLabel(orientation: Orientation): String = when (orientation) {
    Orientation.Portrait -> Strings.portrait
    Orientation.Landscape -> Strings.landscape
}

/**
 * The Paper Size pane — Android's `PaperSizePanelContent` in a docked desktop pane.
 *
 * Width and Height are readouts for a named size and fields for Custom: a size is a width and a
 * height, and hiding them would leave "B5" meaning nothing. An infinite page has no orientation to
 * turn. With no editable page open, every field is inert.
 */
@Composable
internal fun PaperSizePane(
    style: PageStyle,
    enabled: Boolean,
    actions: ViewActions,
    onClose: () -> Unit,
    defaultPaper: PaperSize = PaperSize.Auto,
    onDefaultPaper: (PaperSize) -> Unit = {},
) {
    val custom = style.paper == PaperSize.Custom
    val inches = style.paperInches ?: PaperDimensions.DEFAULT
    val paperRange = PaperDimensions.MIN_INCHES..PaperDimensions.MAX_INCHES
    val marginRange = 0f..PrintMargins.MAX_INCHES
    ToolPane(title = Strings.paper_size, onClose = onClose) {
        PaneGroup(Strings.paper_size_957101) {
            PaneRow(Strings.size, first = true) {
                PaneChoice(ToolPaneTags.Size, style.paper, PaperSize.entries, ::paperSizeLabel, actions.setPaperSize,
                    enabled, defaultPaper, onDefaultPaper,
                    optionId = { if (it == PaperSize.Auto) ToolPaneTags.Infinite else it.name })
            }
            PaneRow(Strings.orientation) {
                PaneChoice(ToolPaneTags.Orientation, style.orientation, Orientation.entries, ::orientationLabel,
                    actions.setOrientation, enabled && style.paper != PaperSize.Auto, optionId = { it.name })
            }
            PaneRow(Strings.width) {
                PaneMeasure(ToolPaneTags.Width, inches.widthInches, { actions.setCustomPaper(inches.copy(widthInches = it)) },
                    paperRange, enabled && custom)
            }
            PaneRow(Strings.height) {
                PaneMeasure(ToolPaneTags.Height, inches.heightInches, { actions.setCustomPaper(inches.copy(heightInches = it)) },
                    paperRange, enabled && custom)
            }
        }
        PaneGroup(Strings.print_margins) {
            PaneRow(Strings.top, first = true) {
                PaneMeasure(ToolPaneTags.Top, style.margins.topInches,
                    { actions.setMargins(style.margins.copy(topInches = it)) }, marginRange, enabled)
            }
            PaneRow(Strings.bottom) {
                PaneMeasure(ToolPaneTags.Bottom, style.margins.bottomInches,
                    { actions.setMargins(style.margins.copy(bottomInches = it)) }, marginRange, enabled)
            }
            PaneRow(Strings.left) {
                PaneMeasure(ToolPaneTags.Left, style.margins.leftInches,
                    { actions.setMargins(style.margins.copy(leftInches = it)) }, marginRange, enabled)
            }
            PaneRow(Strings.right) {
                PaneMeasure(ToolPaneTags.Right, style.margins.rightInches,
                    { actions.setMargins(style.margins.copy(rightInches = it)) }, marginRange, enabled)
            }
        }
    }
}
