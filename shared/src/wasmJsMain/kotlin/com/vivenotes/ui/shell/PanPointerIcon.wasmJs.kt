package com.vivenotes.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon

internal actual fun panPointerIcon(direction: PanDirection): PointerIcon = PointerIcon.Hand

@Composable
internal actual fun ApplyPanCursorWhilePressed(active: Boolean, direction: PanDirection) = Unit
