package com.vivenotes.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/** A deliberate leftward finger swipe — Android's way to put away a pane docked on the left. */
internal fun Modifier.swipeLeft(onSwipeLeft: () -> Unit): Modifier =
    swipeHorizontally(TOWARDS_START, onSwipeLeft)

/** The mirror of [swipeLeft], for a pane docked on the right: it leaves the way it came in. */
internal fun Modifier.swipeRight(onSwipeRight: () -> Unit): Modifier =
    swipeHorizontally(TOWARDS_END, onSwipeRight)

/**
 * Android's `SwipeToHide`, for a finger only: on a desktop a mouse drag across a pane selects or
 * reorders, and putting the pane away under it would lose the user's place.
 *
 * Only drags nothing inside claimed arrive here — the panes' vertical scrolling, a row's reorder
 * grip and a slider all take theirs first — and the swipe must cover [SWIPE_DISTANCE] the right way.
 */
private fun Modifier.swipeHorizontally(direction: Float, onSwipe: () -> Unit): Modifier =
    pointerInput(direction, onSwipe) {
        val threshold = SWIPE_DISTANCE.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            var distance = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                change.consume()
                distance += overSlop
            } ?: return@awaitEachGesture
            val finished = horizontalDrag(drag.id) { change ->
                distance += change.positionChange().x
                change.consume()
            }
            if (finished && distance * direction >= threshold) onSwipe()
        }
    }

private val SWIPE_DISTANCE = 64.dp
private const val TOWARDS_START = -1f
private const val TOWARDS_END = 1f
