package com.vivenotes.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

internal val NavigationDividerWidth = 7.dp

@Composable
internal fun NavigationDivider(
    width: Float,
    maxWidth: Float,
    background: Color,
    label: String,
    tag: String,
    onResize: (width: Float, restoreWidth: Float) -> Unit,
    onResizeFinished: () -> Unit,
) {
    val currentWidth by rememberUpdatedState(width)
    val currentMax by rememberUpdatedState(maxWidth.coerceAtLeast(0f))
    val resize by rememberUpdatedState(onResize)
    val finished by rememberUpdatedState(onResizeFinished)
    val density = LocalDensity.current.density
    var mouseResizing by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    ApplyNavigationResizeCursor(mouseResizing)
    Box(
        Modifier.width(NavigationDividerWidth).fillMaxHeight()
            .background(background)
            .testTag(tag)
            .onGloballyPositioned { coordinates = it }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(width, 0f..currentMax)
                setProgress { value ->
                    if (!value.isFinite()) false else {
                        resize(value.coerceIn(0f, currentMax), currentWidth)
                        finished()
                        true
                    }
                }
            }
            .pointerHoverIcon(navigationResizePointerIcon())
            .pointerInput(density) {
                var dragWidth = 0f
                var restoreWidth = 0f
                var lastRootX: Float? = null
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragWidth = currentWidth
                        restoreWidth = currentWidth
                        lastRootX = null
                    },
                    onDragEnd = { mouseResizing = false; finished() },
                    onDragCancel = { mouseResizing = false; finished() },
                ) { change, delta ->
                    change.consume()
                    mouseResizing = change.type == PointerType.Mouse
                    val rootX = coordinates?.takeIf { it.isAttached }?.localToRoot(change.position)?.x
                    val rootDelta = if (rootX != null && lastRootX != null) rootX - lastRootX!! else delta
                    lastRootX = rootX
                    dragWidth = (dragWidth + rootDelta / density).coerceIn(0f, currentMax)
                    resize(dragWidth, restoreWidth)
                }
            },
    )
}

internal expect fun navigationResizePointerIcon(): PointerIcon

@Composable
internal expect fun ApplyNavigationResizeCursor(active: Boolean)
