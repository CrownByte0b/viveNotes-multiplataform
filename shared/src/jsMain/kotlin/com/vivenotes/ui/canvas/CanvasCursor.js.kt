package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun canvasPointerIcon(cursor: CanvasCursor): PointerIcon = PointerIcon(cursor.keyword)

@Composable
internal actual fun ApplyCanvasCursorWhilePressed(cursor: CanvasCursor?) = Unit
