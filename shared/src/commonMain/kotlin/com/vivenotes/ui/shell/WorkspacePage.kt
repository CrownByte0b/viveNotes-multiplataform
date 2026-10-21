package com.vivenotes.ui.shell

import com.vivenotes.diagnostics.DebugLog
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEvent
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.search.ContentHit
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.ribbon.document.LinkEditorRequest
import com.vivenotes.workspace.InputSettings
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun WorkspacePage(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    searchHit: ContentHit?,
    searchRevealTick: Int,
    pictures: PictureLibrary?,
    thumbnails: VideoThumbnailSource?,
    canvasOrigin: CanvasOrigin,
    canvasControl: CanvasViewControl,
    view: ViewSettings,
    input: InputSettings,
    canvasDark: Boolean,
    showMargins: Boolean,
    onZoomChange: (Float) -> Unit,
    onTextShortcut: (KeyEvent) -> Boolean,
    editorFocusRequester: FocusRequester,
    canvasFocusRequester: FocusRequester,
    modifier: Modifier,
    requestEditorFocus: () -> Unit,
    formulaReady: Boolean,
    onInkMath: (InkSelection) -> Unit,
    applyEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onLinkRequest: (LinkEditorRequest) -> Unit,
    debugLog: DebugLog = DebugLog(),
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val selectionScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val selectionWorker = remember(selectionScope) { CanvasSelectionWorker(selectionScope) }
    val selectionContext = CanvasSelectionContext(state.selectedPageId, state.objectLassoArmed,
        state.textToolArmed, state.shapeToolArmed, state.inkTool, state.drawPlacement,
        state.activePenIndex, view.zoom, input.drawWithFinger, density.density, density.fontScale)
    SideEffect { selectionWorker.updateContext(selectionContext) }
    DisposableEffect(selectionWorker) { onDispose { selectionWorker.close() } }
    PageCanvas(
        state = state,
        searchHit = searchHit,
        searchRevealTick = searchRevealTick,
        pictures = pictures,
        thumbnails = thumbnails,
        canvasOrigin = canvasOrigin,
        canvasControl = canvasControl,
        zoom = view.zoom,
        drawWithFinger = input.drawWithFinger,
        onZoomChange = onZoomChange,
        canvasDark = canvasDark,
        showMargins = showMargins,
        onTextShortcut = onTextShortcut,
        editorFocusRequester = editorFocusRequester,
        canvasFocusRequester = canvasFocusRequester,
        modifier = modifier,
        onTitleChange = { title -> onStateChange { it.updateSelectedPage(title = title) } },
        onFocusTextBox = { id ->
            onStateChange { it.focusTextBox(id) }
            requestEditorFocus()
        },
        onClearCanvasFocus = { onStateChange { it.clearCanvasFocus() } },
        onInkEdit = { edit, rendered -> onStateChange { it.applyInkEdit(edit, rendered) } },
        onSelectInk = { selection -> onStateChange { it.selectInk(selection) } },
        onInkSelectionChange = { change -> onStateChange { it.applyInkSelectionChange(change) } },
        onInkGrouping = { change -> onStateChange { it.applyInkGrouping(change) } },
        inkMathAvailable = formulaReady,
        onInkMath = onInkMath,
        onInkRecolor = { source, argb, followsTheme, change -> onStateChange { current ->
            if (current.selectedInk !== source) current
            else if (change == null) current.colorSelectedObjects(argb, followsTheme)
            else current.applyInkSelectionChange(change, recolorOutlinesArgb = argb,
                recolorFollowsTheme = followsTheme)
        } },
        onPreparedPaste = { before, prepared -> onStateChange { it.applyPreparedCanvasPaste(before, prepared) } },
        onCreateShape = { sx, sy, ex, ey -> onStateChange { it.createShape(sx, sy, ex, ey) } },
        onStraightenStroke = { line, pen -> onStateChange { it.createStraightLine(line, pen) } },
        onCreateTextBox = { x, y ->
            // Decided on what is on screen: a box placed there is the one that types next.
            val focusMoves = state.createTextBox(x, y).focusedTextOutlineId != state.focusedTextOutlineId
            onStateChange { it.createTextBox(x, y) }
            if (focusMoves) requestEditorFocus()
        },
        onMoveTextBox = { id, dx, dy, history -> onStateChange { it.moveTextBox(id, dx, dy, history) } },
        onMoveSelection = { dx, dy, history -> onStateChange { it.moveSelectedObjects(dx, dy, history) } },
        onResizeTextBox = { id, width, height, history ->
            onStateChange { it.resizeTextBox(id, width, height, history) }
        },
        onDeleteTextBox = { id -> onStateChange { it.deleteTextBox(id) } },
        onPasteCanvas = { x, y -> onStateChange { it.pasteCanvasAt(x, y) } },
        onSelectObject = { id -> onStateChange { it.selectObject(id) } },
        onMoveObject = { id, dx, dy, history ->
            onStateChange { current ->
                val selected = if (id in current.selectedObjectIds) current else current.selectObject(id)
                selected.moveSelectedObjects(dx, dy, history)
            }
        },
        onResizeObjects = { ax, ay, sx, sy, history ->
            onStateChange { it.resizeSelectedObjects(ax, ay, sx, sy, history) }
        },
        onCopyObjects = { onStateChange { it.copySelectedObjects() } },
        onDeleteObjects = { onStateChange { it.deleteSelectedObjects() } },
        onToggleObjectLock = { onStateChange { it.toggleObjectLock() } },
        onColorObjects = { argb, followsTheme -> onStateChange { it.colorSelectedObjects(argb, followsTheme) } },
        onShapeLineType = { type -> onStateChange { it.setSelectedShapeLineType(type) } },
        onShapeWidth = { width -> onStateChange { it.setSelectedShapeBorderWidth(width) } },
        onShapeFill = { argb -> onStateChange { it.setSelectedShapeFill(argb) } },
        onMoveShapeEnd = { id, atEnd, x, y, history ->
            onStateChange { it.moveShapeEnd(id, atEnd, x, y, history) }
        },
        onMoveShapeArm = { id, arm, along, history ->
            onStateChange { it.moveShapeArm(id, arm, along, history) }
        },
        onDrawObject = { x, y -> onStateChange { it.placeDrawObject(x, y) } },
        onSpaceInsertion = { insertion -> onStateChange { it.applySpaceInsertion(insertion) } },
        onTableEdit = { edit ->
            edit.logLine?.let { line -> debugLog.event("table") { line } }
            onStateChange { it.editTable(edit) }
        },
        onEquationChange = { equation -> onStateChange { it.updateSelectedEquation(equation.latex, equation.width, equation.height) } },
        onImageBackground = { argb -> onStateChange { it.setSelectedImageBackground(argb) } },
        onSelectObjectsInLasso = { path, heights ->
            val captured = latestState
            if (captured.preparesCanvasLassoOffThread(path.size))
                selectionWorker.submit(captured, path, heights) { command -> latestOnStateChange(command) }
            else {
                selectionWorker.cancel()
                latestOnStateChange { it.selectObjectsInLasso(path, heights) }
            }
        },
        onSelectObjectsInRect = { l, t, r, b ->
            onStateChange { it.selectObjectsInRect(l, t, r, b) }
        },
        onToggleTodo = { outlineId, blockId -> onStateChange { it.toggleTodo(outlineId, blockId) } },
        onTextCommand = applyEditorCommand,
        onTextLinkRequest = { id, selection, target ->
            onLinkRequest(LinkEditorRequest(selection, target) { label, url, captured ->
                applyEditorCommand { current ->
                    current.focusTextBox(id).selectText(captured ?: selection).insertLink(label, url)
                }
            })
        },
        onOpenTextMenu = { id, selection ->
            onStateChange { current ->
                (if (current.focusedTextOutlineId == id) current else current.focusTextBox(id)).selectText(selection)
            }
        },
        onEditTextAt = { id, selection ->
            onStateChange { current ->
                (if (current.focusedTextOutlineId == id) current else current.focusTextBox(id)).selectText(selection)
            }
            requestEditorFocus()
        },
        onBodyChange = { id, value ->
            onStateChange { current ->
                val focused = if (current.focusedTextOutlineId == id) current else current.focusTextBox(id)
                focused.editSelectedText(
                    value.text,
                    TextSelection(value.selection.start, value.selection.end),
                    value.composition?.let { TextSelection(it.start, it.end) },
                )
            }
        },
    )
}
