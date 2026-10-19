package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.vivenotes.ai.AiModels
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ui.ribbon.document.DocumentColorSelection
import com.vivenotes.ui.ribbon.document.DocumentTab
import com.vivenotes.ui.ribbon.document.LinkEditorRequest
import com.vivenotes.ui.ribbon.document.PictureDialog
import com.vivenotes.ui.ribbon.draw.DrawRibbon
import com.vivenotes.ui.ribbon.file.FileRibbon
import com.vivenotes.ui.ribbon.settings.SettingsRibbon
import com.vivenotes.ui.ribbon.view.ViewActions
import com.vivenotes.ui.ribbon.view.ViewTab
import com.vivenotes.ui.ribbon.view.viewActions
import com.vivenotes.workspace.EditorDefaults
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun WorkspaceRibbon(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    fileActions: FileActions?,
    onNotebookConfirmation: (String) -> Unit,
    onExportPdf: () -> Unit,
    onShapeSettingsChange: (ShapeToolSettings) -> Unit,
    onInkSettingsChange: (InkSettings) -> Unit,
    canvasDark: Boolean,
    formulaReady: Boolean,
    calculatorOpen: Boolean,
    onToggleCalculator: () -> Unit,
    applyEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    pictures: PictureLibrary?,
    canvasOrigin: CanvasOrigin,
    documentColorSelection: DocumentColorSelection,
    onLinkRequest: (LinkEditorRequest) -> Unit,
    onEditorDefaultsChange: (EditorDefaults) -> Unit,
    onPictureDialog: (PictureDialog) -> Unit,
    debugLog: DebugLog,
    view: ViewSettings,
    viewActions: ViewActions,
    onInterface: () -> Unit,
    openPane: DockedPane?,
    togglePane: (DockedPane) -> Unit,
    onViewChange: (ViewSettings) -> Unit,
    onAbout: () -> Unit,
    aiModels: AiModels?,
) {
    when (state.activeTab) {
        RibbonTab.File -> FileRibbon(notebookOpen = state.selectedSection != null,
            pageOpen = state.selectedPage != null,
            transferRunning = state.notebookTransfer.running, actions = fileActions,
            onCloseNotebook = { onNotebookConfirmation("close") },
            onDeleteNotebook = { onNotebookConfirmation("delete") }, onExportPdf = onExportPdf)
        RibbonTab.Draw -> DrawRibbon(state, onStateChange, onShapeSettingsChange, onInkSettingsChange,
            state.selectedPage?.let { if (canvasPalette(it.document.style, canvasDark).background.luminance() < .45f) Color.White else Color.Black } ?: Color.Black,
            calculatorAvailable = formulaReady, calculatorOpen = calculatorOpen, onToggleCalculator = { onToggleCalculator() })
        RibbonTab.Document -> DocumentTab(state, onStateChange, applyEditorCommand, pictures,
            { canvasOrigin.read() }, documentColorSelection, onLinkRequest = onLinkRequest,
            onEditorDefaultsChange = onEditorDefaultsChange, onPictureDialog = onPictureDialog,
            log = debugLog)
        RibbonTab.View -> ViewTab(state, view, canvasDark, viewActions,
            onDefaultRuleLines = { rule ->
                val next = state.editorDefaults.copy(ruleLines = rule)
                onStateChange { it.setEditorDefaults(next) }
                onEditorDefaultsChange(next)
            })
        RibbonTab.Settings -> SettingsRibbon(onInterface, hardwareOpen = openPane == DockedPane.Hardware,
            onHardware = { togglePane(DockedPane.Hardware) }, linkPreviews = view.linkPreviews,
            onLinkPreviewsChange = { enabled -> onViewChange(view.copy(linkPreviews = enabled)) },
            onAbout = onAbout, modelsAvailable = aiModels != null,
            modelsOpen = openPane == DockedPane.Models, onModels = { togglePane(DockedPane.Models) })
    }
}
