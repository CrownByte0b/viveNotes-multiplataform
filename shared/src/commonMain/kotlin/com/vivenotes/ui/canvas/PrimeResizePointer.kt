package com.vivenotes.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

internal enum class PrimeResizeDiagonal(val keyword: String) {
    NorthWestSouthEast("nwse-resize"),
    NorthEastSouthWest("nesw-resize"),
}

internal fun primeResizeDiagonal(corner: Int): PrimeResizeDiagonal {
    require(corner in 0..3)
    return when (corner) {
        0, 3 -> PrimeResizeDiagonal.NorthWestSouthEast
        else -> PrimeResizeDiagonal.NorthEastSouthWest
    }
}

internal fun Modifier.primeResizePointer(corner: Int): Modifier = composed {
    val diagonal = primeResizeDiagonal(corner)
    val cursor = rememberPrimeResizeCursor(diagonal)
    DisposableEffect(cursor) { onDispose(cursor::release) }
    pointerHoverIcon(primeResizePointerIcon(diagonal))
        .pointerInput(cursor) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // The press is the handle's: a click on it, or a release after the handle went
                // away (the selection locked mid-press), is not a page tap that clears the selection.
                down.consume()
                if (down.type == PointerType.Mouse && currentEvent.buttons.isPrimaryPressed) cursor.press()
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.id == down.id && it.pressed })
                } finally {
                    cursor.release()
                }
            }
        }
}

internal expect fun primeResizePointerIcon(diagonal: PrimeResizeDiagonal): PointerIcon

internal interface PrimeResizeCursor {
    fun press()
    fun release()
}

internal object NoPrimeResizeCursor : PrimeResizeCursor {
    override fun press() = Unit
    override fun release() = Unit
}

@Composable
internal expect fun rememberPrimeResizeCursor(diagonal: PrimeResizeDiagonal): PrimeResizeCursor

@Composable
internal expect fun primeResizeCursorProviders(): Array<ProvidedValue<*>>
