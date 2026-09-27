package com.vivenotes.workspace

import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines

/**
 * The View tab's page commands — Android `NotesViewModel`'s view section — as transitions of the
 * open page's [PageStyle]. A page that is not loaded refuses them, like every other document edit.
 */

/** Where content may start: below the title band, or at the very top once the title is hidden. */
val PageStyle.titleFloor: Float get() = if (hideTitle) 0f else PageStyle.TITLE_BAND_DP

fun WorkspaceState.setRuleLines(rule: RuleLines): WorkspaceState = updatePageStyle { it.copy(ruleLines = rule) }

/** Null hands the page back to the canvas colours rather than painting a light page dark. */
fun WorkspaceState.setPageColor(argb: Int?): WorkspaceState = updatePageStyle { it.copy(backgroundArgb = argb) }

fun WorkspaceState.setHideTitle(hidden: Boolean): WorkspaceState = updatePageStyle { it.copy(hideTitle = hidden) }

/**
 * Choosing Custom seeds its dimensions from the size being left, so Width and Height open on the
 * page the user is already looking at rather than on a guess.
 */
fun WorkspaceState.setPaperSize(paper: PaperSize): WorkspaceState = updatePageStyle { style ->
    val seeded = if (paper == PaperSize.Custom && style.customPaper == null) {
        style.paperInches ?: PaperDimensions.DEFAULT
    } else {
        style.customPaper
    }
    style.copy(paper = paper, customPaper = seeded)
}

fun WorkspaceState.setCustomPaper(dimensions: PaperDimensions): WorkspaceState =
    updatePageStyle { it.copy(paper = PaperSize.Custom, customPaper = dimensions) }

fun WorkspaceState.setOrientation(orientation: Orientation): WorkspaceState =
    updatePageStyle { it.copy(orientation = orientation) }

fun WorkspaceState.setMargins(margins: PrintMargins): WorkspaceState = updatePageStyle { it.copy(margins = margins) }
