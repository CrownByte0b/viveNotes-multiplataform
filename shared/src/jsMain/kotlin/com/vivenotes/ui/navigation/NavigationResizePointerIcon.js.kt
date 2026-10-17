package com.vivenotes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun navigationResizePointerIcon(): PointerIcon = PointerIcon.fromKeyword("w-resize")

@Composable
internal actual fun ApplyNavigationResizeCursor(active: Boolean) = Unit
