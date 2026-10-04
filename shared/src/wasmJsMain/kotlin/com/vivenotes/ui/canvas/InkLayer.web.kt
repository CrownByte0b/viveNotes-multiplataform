package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import com.vivenotes.data.InkEdit
import com.vivenotes.model.ink.InkPage
import com.vivenotes.workspace.InkTool

@Composable
internal actual fun PlatformInkLayer(
    page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)?,
    pageId: String, tool: InkTool?, titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit,
    zoom: Float,
) { /* Ink authoring and rendering are desktop services. */ }
