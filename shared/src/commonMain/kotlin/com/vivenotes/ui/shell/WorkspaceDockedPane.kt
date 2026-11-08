package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vivenotes.ai.AiModels
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.PageStyle
import com.vivenotes.model.search.ContentHit
import com.vivenotes.search.ContentSearchService
import com.vivenotes.search.SearchRequest
import com.vivenotes.ui.ribbon.draw.*
import com.vivenotes.ui.ribbon.file.FilePaneContent
import com.vivenotes.ui.ribbon.settings.HardwarePane
import com.vivenotes.ui.ribbon.settings.ModelsPane
import com.vivenotes.ui.ribbon.view.PaperSizePane
import com.vivenotes.ui.ribbon.view.ViewActions
import com.vivenotes.ui.ribbon.view.viewActions
import com.vivenotes.ui.search.SearchPane
import com.vivenotes.workspace.insertGraph
import androidx.compose.ui.geometry.Rect
import com.vivenotes.workspace.EditorDefaults
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun WorkspaceDockedPane(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    fileActions: FileActions?,
    openPane: DockedPane?,
    onRestoreRevision: () -> Unit,
    onClosePane: () -> Unit,
    viewActions: ViewActions,
    onEditorDefaultsChange: (EditorDefaults) -> Unit,
    input: InputSettings,
    onInputChange: (InputSettings) -> Unit,
    bindings: KeyBindings,
    onEditShortcut: (ShortcutAction) -> Unit,
    onBindingsChange: (KeyBindings) -> Unit,
    onResetShortcuts: () -> Unit,
    search: ContentSearchService?,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onSearchHit: (ContentHit) -> Unit,
    searchFocusTick: Int,
    drawMath: DrawMath?,
    inkMath: InkMathRequest?,
    mathSource: String,
    mathAnchor: Rect? = null,
    canvasOrigin: CanvasOrigin,
    inkMathValid: Boolean,
    developmentBuild: Boolean,
    debugLog: DebugLog,
    onCloseInkMath: () -> Unit,
    aiModels: AiModels?,
) {
    if (state.filePane.docked && fileActions != null) {
        VerticalDivider(modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant)
        FilePaneContent(state.filePane, fileActions, onRestoreRevision = onRestoreRevision)
    } else openPane?.let { pane ->
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        when (pane) {
            DockedPane.PaperSize -> {
                val page = state.selectedPage?.takeIf { it.editable }
                PaperSizePane(
                    style = page?.document?.style ?: PageStyle(),
                    enabled = page != null,
                    actions = viewActions,
                    onClose = onClosePane,
                    defaultPaper = state.editorDefaults.paper,
                    onDefaultPaper = { paper ->
                        val next = state.editorDefaults.copy(paper = paper,
                            customPaper = if (paper == com.vivenotes.model.PaperSize.Custom)
                                state.selectedPage?.document?.style?.customPaper else state.editorDefaults.customPaper)
                        onStateChange { it.setEditorDefaults(next) }
                        onEditorDefaultsChange(next)
                    },
                )
            }
            DockedPane.Hardware -> HardwarePane(
                input = input,
                onInputChange = onInputChange,
                bindings = bindings,
                onEdit = onEditShortcut,
                onReset = { onBindingsChange(bindings.reset(it)) },
                onResetAll = onResetShortcuts,
                onClose = onClosePane,
            )
            DockedPane.Search -> search?.let { service ->
                SearchPane(service, SearchRequest(state.selectedNotebookId, searchQuery,
                    state.selectedPageId, state.selectedSectionId, state.selectedPage?.title.orEmpty(),
                    state.selectedPage?.takeIf { it.editable }?.document), searchQuery,
                    onQueryChange = onSearchQuery,
                    onHit = onSearchHit, onClose = onClosePane, focusRequest = searchFocusTick)
            }
            DockedPane.Math -> drawMath?.let { math ->
                val request = inkMath
                val origin = canvasOrigin.read()
                val pageId = state.selectedPageId
                val anchor = if (request != null) request.selection.bounds.let { Rect(it.left, it.top, it.right, it.bottom) }
                    else mathAnchor
                val place: ((com.vivenotes.math.MathGraph) -> Unit)? = if (state.selectedPage?.editable != true ||
                    state.notebookTransfer.running || state.filePane.busy) null else { graph ->
                    onStateChange { current ->
                        if (current.selectedPageId != pageId) current else current.insertGraph(graph, anchor, origin)
                    }
                    debugLog.event("graph") { "placed on page" }
                    onCloseInkMath()
                }
                if (request == null) MathPane(mathSource, math, showInterpretation = developmentBuild,
                    log = debugLog, onPlaceGraph = place, onClose = onClosePane)
                else if (inkMathValid) InkMathPane(request, math, showInterpretation = developmentBuild,
                    log = debugLog, onPlaceGraph = place, onClose = onCloseInkMath)
            }
            DockedPane.Models -> ModelsPane(aiModels, onClose = onClosePane)
        }
    }
}
