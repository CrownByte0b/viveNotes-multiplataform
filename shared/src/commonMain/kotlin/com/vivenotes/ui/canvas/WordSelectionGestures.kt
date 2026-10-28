package com.vivenotes.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class WordSelectionActions(
    val state: () -> WorkspaceState,
    val add: (String, TextSelection) -> Unit,
    val clear: () -> Unit,
    val activate: (String, TextSelection) -> Unit,
    val clipboard: TextClipboardActions,
    val edit: (((WorkspaceState) -> WorkspaceState) -> Unit),
)

internal val LocalWordSelectionActions = staticCompositionLocalOf<WordSelectionActions?> { null }

internal fun wordAt(layout: TextLayoutResult?, text: String, offset: Int): TextSelection? {
    if (layout == null || text.isEmpty()) return null
    val range = layout.getWordBoundary(offset.coerceIn(0, text.lastIndex))
    val selected = TextSelection(range.min, range.max).clamped(text.length)
    return selected.takeUnless { it.collapsed || text.substring(it.min, it.max).isBlank() }
}

/** Consume both Ctrl-click presses so Compose does not discard earlier word ranges. */
@Composable
internal fun Modifier.selectAdditionalWords(
    id: String,
    rangeAt: (Offset) -> TextSelection?,
    onCtrlClick: (Offset) -> Unit = {},
): Modifier {
    val actions = LocalWordSelectionActions.current ?: return this
    val currentRange by rememberUpdatedState(rangeAt)
    val currentClick by rememberUpdatedState(onCtrlClick)
    return pointerInput(id, actions) {
        coroutineScope {
            var firstAt = -1L
            var firstPoint = Offset.Zero
            var click: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed || down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                if (!currentEvent.keyboardModifiers.isCtrlPressed) {
                    firstAt = -1L
                    click?.cancel()
                    if (actions.state().wordSelections != null) actions.clear()
                    return@awaitEachGesture
                }
                val state = actions.state()
                if (state.selectedPage?.editable != true || state.inkTool != null || state.objectLassoArmed ||
                    state.shapeToolArmed || state.drawPlacement != null || state.textToolArmed) return@awaitEachGesture
                val point = down.position
                down.consume()
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                up.consume()
                if ((up.position - point).getDistance() > viewConfiguration.touchSlop) {
                    firstAt = -1L
                    click?.cancel()
                    return@awaitEachGesture
                }
                if (firstAt >= 0 && up.uptimeMillis - firstAt in
                    viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                    (point - firstPoint).getDistance() <= viewConfiguration.touchSlop * 2) {
                    click?.cancel()
                    firstAt = -1L
                    currentRange(point)?.let { actions.add(id, it) }
                } else {
                    firstAt = up.uptimeMillis
                    firstPoint = point
                    click?.cancel()
                    click = launch {
                        delay(viewConfiguration.doubleTapTimeoutMillis)
                        currentClick(point)
                    }
                }
            }
        }
    }
}
