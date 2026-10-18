package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun primeResizePointerIcon(diagonal: PrimeResizeDiagonal): PointerIcon =
    PointerIcon.fromKeyword(diagonal.keyword)

@Composable
internal actual fun rememberPrimeResizeCursor(diagonal: PrimeResizeDiagonal): PrimeResizeCursor = NoPrimeResizeCursor

@Composable
internal actual fun primeResizeCursorProviders(): Array<ProvidedValue<*>> = emptyArray()
