package com.vivenotes.ui.canvas

import com.vivenotes.workspace.InkSettings
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import com.vivenotes.data.InkEdit
import com.vivenotes.model.ink.InkPage
import com.vivenotes.workspace.InkTool

internal const val INK_LAYER_TAG = "workspace-ink-layer"

/** Desktop supplies the real engine; dormant web targets keep this platform boundary. */
@Composable
internal fun InkLayer(
    page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)? = null,
    pageId: String = page?.pageId.orEmpty(), tool: InkTool? = null,
    titleFloorDp: Float = 0f, onEdit: (InkEdit, InkPage) -> Unit = { _, _ -> },
    zoom: Float = 1f,
    eraserMode: com.vivenotes.data.EraserMode = com.vivenotes.data.EraserMode.Normal,
    settings: InkSettings = InkSettings(),
    selection: com.vivenotes.model.ink.InkSelection? = null,
    selectionOffset: Offset = Offset.Zero,
    penIndex: Int = 0,
    ruler: com.vivenotes.model.ink.Ruler? = null,
    onStraighten: (com.vivenotes.model.ink.StraightLine, com.vivenotes.workspace.InkPenSettings) -> Unit = { _, _ -> },
) = PlatformInkLayer(page, canvasSize, canvasInk, visibleWindow, pageId, tool, titleFloorDp, onEdit, zoom, eraserMode, settings, selection, selectionOffset, penIndex, ruler, onStraighten)

@Composable
internal expect fun PlatformInkLayer(
    page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)?,
    pageId: String, tool: InkTool?, titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit,
    zoom: Float,
    eraserMode: com.vivenotes.data.EraserMode,
    settings: InkSettings,
    selection: com.vivenotes.model.ink.InkSelection?,
    selectionOffset: androidx.compose.ui.geometry.Offset,
    penIndex: Int,
    ruler: com.vivenotes.model.ink.Ruler?,
    onStraighten: (com.vivenotes.model.ink.StraightLine, com.vivenotes.workspace.InkPenSettings) -> Unit,
)

internal fun InkPage.contentEdge(): Offset = geometry?.let { Offset(it.rightDp, it.bottomDp) }
    ?: Offset(strokes.flatMap { it.samples }.maxOfOrNull { it.x } ?: 0f,
        strokes.flatMap { it.samples }.maxOfOrNull { it.y } ?: 0f)
