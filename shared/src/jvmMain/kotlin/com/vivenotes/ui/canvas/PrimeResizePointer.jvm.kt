@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerIconService
import androidx.compose.ui.platform.LocalPointerIconService
import java.awt.Component
import java.awt.Cursor

private val LocalPrimeResizePointerService = staticCompositionLocalOf<PrimeResizePointerService?> { null }

internal actual fun primeResizePointerIcon(diagonal: PrimeResizeDiagonal): PointerIcon =
    PointerIcon(primeResizeAwtCursor(diagonal))

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun primeResizeCursorProviders(): Array<ProvidedValue<*>> {
    val delegate = LocalPointerIconService.current
    val window = LocalAwtWindow.current
    val service = remember(delegate, window) { delegate?.let { PrimeResizePointerService(it, window) } }
    DisposableEffect(service) { onDispose { service?.releaseCurrent() } }
    return arrayOf(LocalPointerIconService provides service, LocalPrimeResizePointerService provides service)
}

@Composable
internal actual fun rememberPrimeResizeCursor(diagonal: PrimeResizeDiagonal): PrimeResizeCursor {
    val service = LocalPrimeResizePointerService.current
    return remember(service, diagonal) {
        if (service == null) NoPrimeResizeCursor else object : PrimeResizeCursor {
            override fun press() = service.hold(this, diagonal)
            override fun release() = service.release(this)
        }
    }
}

internal class PrimeResizePointerService(
    private val delegate: PointerIconService,
    private val window: Component? = null,
) : PointerIconService {
    private var hoverIcon: PointerIcon? = delegate.getIcon()
    private var owner: Any? = null
    private var heldIcon: PointerIcon? = null
    private var heldCursor: Cursor? = null
    private var previousWindowCursor: Cursor? = null

    override fun getIcon(): PointerIcon = heldIcon ?: delegate.getIcon()

    override fun setIcon(value: PointerIcon?) {
        hoverIcon = value
        delegate.setIcon(heldIcon ?: value)
    }

    override fun getStylusHoverIcon(): PointerIcon? = delegate.getStylusHoverIcon()
    override fun setStylusHoverIcon(value: PointerIcon?) = delegate.setStylusHoverIcon(value)

    fun hold(token: Any, diagonal: PrimeResizeDiagonal) {
        if (owner === token) return
        if (owner == null) previousWindowCursor = window?.cursor?.takeIf { window.isCursorSet }
        owner = token
        heldCursor = primeResizeAwtCursor(diagonal)
        heldIcon = primeResizePointerIcon(diagonal)
        window?.cursor = heldCursor
        delegate.setIcon(heldIcon)
    }

    fun release(token: Any) {
        if (owner !== token) return
        releaseCurrent()
    }

    fun releaseCurrent() {
        if (owner == null) return
        owner = null
        heldIcon = null
        if (window?.cursor === heldCursor) window?.cursor = previousWindowCursor
        heldCursor = null
        previousWindowCursor = null
        delegate.setIcon(hoverIcon)
    }
}
