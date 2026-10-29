package com.vivenotes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun navigationResizePointerIcon(): PointerIcon = PointerIcon("w-resize")

@Composable
internal actual fun ApplyNavigationResizeCursor(active: Boolean) = Unit
