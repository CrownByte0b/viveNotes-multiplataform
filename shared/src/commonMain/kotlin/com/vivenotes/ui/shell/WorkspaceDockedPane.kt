package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vivenotes.ai.AiModels
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.PageStyle
import com.vivenotes.model.ink.InkSelection
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
    inkMathSelection: InkSelection?,
    mathSource: String,
    inkMathValid: Boolean,
    developmentBuild: Boolean,
    debugLog: DebugLog,
    onCloseInkMath: () -> Unit,
    aiModels: AiModels?,
) {
    if (state.filePane.pane != null && fileActions != null) {
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
                val selection = inkMathSelection
                if (selection == null) MathPane(mathSource, math, showInterpretation = developmentBuild,
                    log = debugLog, onClose = onClosePane)
                else if (inkMathValid) InkMathPane(selection, math, showInterpretation = developmentBuild,
                    log = debugLog, onClose = onCloseInkMath)
            }
            DockedPane.Models -> ModelsPane(aiModels, onClose = onClosePane)
        }
    }
}
