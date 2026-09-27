package com.vivenotes.ui.navigation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for rows of a [androidx.compose.foundation.lazy.LazyColumn] — Android's
 * `Reorderable.kt`, driven by a mouse here rather than a finger.
 *
 * The page list is a flat list of variable-height rows; the notebook pane is a tree whose draggable
 * rows are one notebook's sections between headers that must not move. So this works on keys rather
 * than indices, and the caller says which keys may be dragged at the moment.
 *
 * Row heights are read from the live layout rather than assumed, because a page row is two or three
 * lines tall depending on whether it has a preview.
 *
 * The list reorders under the pointer, not on release: the caller keeps an optimistic copy of the
 * order and changes it on every [onMove], and [onSettle] is when that copy should be stored.
 */
@Stable
internal class ReorderState internal constructor(
    private val listState: LazyListState,
    private val keys: () -> List<Any>,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSettle: () -> Unit,
) {

    /** The row being dragged, or null. */
    var draggedKey: Any? by mutableStateOf(null)
        private set

    /** Where the dragged row's top edge sat in the viewport when the drag began. */
    private var anchor = 0f
    private var height = 0

    /**
     * How far the pointer has travelled since. State, so the row's offset is recomputed in the draw
     * phase, where it costs a redraw rather than a recomposition of the whole list.
     */
    private var travelled by mutableFloatStateOf(0f)

    val dragging: Boolean get() = draggedKey != null

    fun start(key: Any) {
        val info = listState.itemInfo(key) ?: return
        draggedKey = key
        anchor = info.offset.toFloat()
        height = info.size
        travelled = 0f
    }

    fun drag(dy: Float) {
        if (draggedKey == null) return
        travelled += dy
        swapUnderPointer()
    }

    fun settle() {
        if (draggedKey == null) return
        draggedKey = null
        travelled = 0f
        onSettle()
    }

    /** How far [key]'s row should be drawn from wherever the list has just laid it out. */
    fun offsetFor(key: Any): Float {
        if (key != draggedKey) return 0f
        // Its slot keeps moving, as the list reorders around it and as auto-scroll slides everything
        // past, so the translation is measured fresh against the anchor rather than accumulated.
        val laidOutAt = listState.itemInfo(key)?.offset?.toFloat() ?: return 0f
        return anchor + travelled - laidOutAt
    }

    /**
     * Whichever draggable row the dragged one is now centred over trades places with it.
     *
     * Centre-in-bounds rather than edge overlap: overlap is true of two rows at once through the
     * whole of a slow drag, which makes the list flicker between two arrangements.
     */
    private fun swapUnderPointer() {
        val key = draggedKey ?: return
        val order = keys()
        val from = order.indexOf(key)
        if (from < 0) return
        val centre = anchor + travelled + height / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != key &&
                centre >= item.offset &&
                centre <= item.offset + item.size &&
                item.key in order
        } ?: return
        val to = order.indexOf(target.key)
        if (to >= 0 && to != from) onMove(from, to)
    }

    /** Scrolls the list while the dragged row is held against an edge, to reach rows off screen. */
    internal suspend fun autoScrollStep(edge: Float, maxStep: Float) {
        if (draggedKey == null || edge <= 0f) return
        val viewport = listState.layoutInfo
        val top = anchor + travelled
        val pastTop = viewport.viewportStartOffset + edge - top
        val pastBottom = (top + height) - (viewport.viewportEndOffset - edge)
        val push = when {
            pastTop > 0f -> -(pastTop / edge).coerceAtMost(1f) * maxStep
            pastBottom > 0f -> (pastBottom / edge).coerceAtMost(1f) * maxStep
            else -> return
        }
        // Rows slid past the stationary pointer are new drop targets, so test again after moving.
        if (listState.scrollBy(push) != 0f) swapUnderPointer()
    }
}

/** How close to an edge the dragged row must be held before the list scrolls itself. */
private val AutoScrollEdge = 56.dp

/** Scroll per frame at the very edge; it ramps up across [AutoScrollEdge] rather than snapping on. */
private val AutoScrollMaxStep = 14.dp

/**
 * @param keys the rows that may be dragged, in the order shown. Indices handed to [onMove] index
 *   into this list, so it is also what the caller reorders.
 * @param onSettle the drag finished; store what [onMove] has been building.
 */
@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    keys: List<Any>,
    onMove: (from: Int, to: Int) -> Unit,
    onSettle: () -> Unit,
): ReorderState {
    val currentKeys = rememberUpdatedState(keys)
    val currentMove = rememberUpdatedState(onMove)
    val currentSettle = rememberUpdatedState(onSettle)
    val state = remember(listState) {
        ReorderState(
            listState = listState,
            keys = { currentKeys.value },
            onMove = { from, to -> currentMove.value(from, to) },
            onSettle = { currentSettle.value() },
        )
    }

    val edge = with(LocalDensity.current) { AutoScrollEdge.toPx() }
    val maxStep = with(LocalDensity.current) { AutoScrollMaxStep.toPx() }
    // Frame-driven rather than driven by drag events, or holding still at the edge — which is
    // exactly what reaching for an off-screen row looks like — would stop the scroll.
    LaunchedEffect(state, state.dragging) {
        if (!state.dragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            state.autoScrollStep(edge, maxStep)
        }
    }
    return state
}

/** Lifts a row above its neighbours and makes it follow the pointer while it is the dragged one. */
internal fun Modifier.reorderable(state: ReorderState, key: Any): Modifier =
    zIndex(if (state.draggedKey == key) 1f else 0f)
        .graphicsLayer { translationY = state.offsetFor(key) }

/**
 * The grip that starts a drag. A handle rather than a press-and-drag on the row, because the row is
 * a button: a press on it opens the page or section, and holding one should not be ambiguous.
 *
 * [onGrabbed] runs before the drag does, for a caller whose draggable keys depend on which row was
 * picked up — the notebook pane's, which are one notebook's sections and not another's.
 */
internal fun Modifier.reorderHandle(
    state: ReorderState,
    key: Any,
    onGrabbed: () -> Unit = {},
): Modifier =
    pointerInput(state, key) {
        detectDragGestures(
            onDragStart = {
                onGrabbed()
                state.start(key)
            },
            onDragEnd = { state.settle() },
            onDragCancel = { state.settle() },
            onDrag = { change, drag ->
                change.consume()
                state.drag(drag.y)
            },
        )
    }

private fun LazyListState.itemInfo(key: Any): LazyListItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
