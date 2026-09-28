package com.vivenotes.ui.ribbon.view

import androidx.compose.runtime.Composable
import com.vivenotes.model.PageStyle
import com.vivenotes.model.RuleLines
import com.vivenotes.ui.shell.CanvasViewControl
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.setCustomPaper
import com.vivenotes.workspace.setHideTitle
import com.vivenotes.workspace.setMargins
import com.vivenotes.workspace.setOrientation
import com.vivenotes.workspace.setPageColor
import com.vivenotes.workspace.setPaperSize
import com.vivenotes.workspace.setRuleLines

/**
 * The View tab's commands: page settings become [WorkspaceState] transitions, device settings a
 * new [ViewSettings]. [canvas] supplies the measurements Page Width fits.
 */
internal fun viewActions(
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    settings: () -> ViewSettings,
    onSettingsChange: (ViewSettings) -> Unit,
    canvas: CanvasViewControl,
    onTogglePaperSizePane: () -> Unit,
): ViewActions {
    fun setZoom(zoom: Float) = onSettingsChange(settings().copy(zoom = zoom).normalized())
    return ViewActions(
        setRuleLines = { rule -> onStateChange { it.setRuleLines(rule) } },
        setPageColor = { argb -> onStateChange { it.setPageColor(argb) } },
        setHideTitle = { hidden -> onStateChange { it.setHideTitle(hidden) } },
        setPaperSize = { paper -> onStateChange { it.setPaperSize(paper) } },
        setOrientation = { orientation -> onStateChange { it.setOrientation(orientation) } },
        setCustomPaper = { dimensions -> onStateChange { it.setCustomPaper(dimensions) } },
        setMargins = { margins -> onStateChange { it.setMargins(margins) } },
        setZoom = ::setZoom,
        zoomIn = { setZoom(ViewSettings.zoomStepUp(settings().zoom)) },
        zoomOut = { setZoom(ViewSettings.zoomStepDown(settings().zoom)) },
        zoomToPageWidth = {
            canvas.pageWidthZoom()?.let { fit ->
                canvas.alignLeftOnNextZoom = fit != settings().zoom
                setZoom(fit)
            }
        },
        setTabsLayout = { layout -> onSettingsChange(settings().copy(tabsLayout = layout)) },
        setCanvasDark = { dark -> onSettingsChange(settings().copy(canvasDark = dark)) },
        togglePaperSizePane = onTogglePaperSizePane,
    )
}

/** The View tab for the open page; with none, its page controls are inert. */
@Composable
internal fun ViewTab(
    state: WorkspaceState,
    settings: ViewSettings,
    canvasDark: Boolean,
    actions: ViewActions,
    onDefaultRuleLines: (RuleLines) -> Unit = {},
) {
    val page = state.selectedPage?.takeIf { it.editable }
    ViewRibbon(
        style = page?.document?.style ?: PageStyle(),
        pageOpen = page != null,
        settings = settings,
        canvasDark = canvasDark,
        actions = actions,
        defaultRuleLines = state.editorDefaults.ruleLines,
        onDefaultRuleLines = onDefaultRuleLines,
    )
}
