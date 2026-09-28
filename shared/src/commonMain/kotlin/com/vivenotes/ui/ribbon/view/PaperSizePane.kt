package com.vivenotes.ui.ribbon.view

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
internal fun paperSizeLabel(paper: PaperSize): String = if (paper == PaperSize.Auto) "Infinite" else paper.name

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
    ToolPane(title = "Paper Size", onClose = onClose) {
        PaneGroup("Paper size") {
            PaneRow("Size", first = true) {
                PaneChoice("Size", style.paper, PaperSize.entries, ::paperSizeLabel, actions.setPaperSize,
                    enabled, defaultPaper, onDefaultPaper)
            }
            PaneRow("Orientation") {
                PaneChoice("Orientation", style.orientation, Orientation.entries, { it.name },
                    actions.setOrientation, enabled && style.paper != PaperSize.Auto)
            }
            PaneRow("Width") {
                PaneMeasure("Width", inches.widthInches, { actions.setCustomPaper(inches.copy(widthInches = it)) },
                    paperRange, enabled && custom)
            }
            PaneRow("Height") {
                PaneMeasure("Height", inches.heightInches, { actions.setCustomPaper(inches.copy(heightInches = it)) },
                    paperRange, enabled && custom)
            }
        }
        PaneGroup("Print margins") {
            PaneRow("Top", first = true) {
                PaneMeasure("Top", style.margins.topInches,
                    { actions.setMargins(style.margins.copy(topInches = it)) }, marginRange, enabled)
            }
            PaneRow("Bottom") {
                PaneMeasure("Bottom", style.margins.bottomInches,
                    { actions.setMargins(style.margins.copy(bottomInches = it)) }, marginRange, enabled)
            }
            PaneRow("Left") {
                PaneMeasure("Left", style.margins.leftInches,
                    { actions.setMargins(style.margins.copy(leftInches = it)) }, marginRange, enabled)
            }
            PaneRow("Right") {
                PaneMeasure("Right", style.margins.rightInches,
                    { actions.setMargins(style.margins.copy(rightInches = it)) }, marginRange, enabled)
            }
        }
    }
}
