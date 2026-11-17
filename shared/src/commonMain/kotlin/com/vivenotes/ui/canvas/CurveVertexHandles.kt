package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.vertices
import com.vivenotes.model.ink.withVertex
import kotlin.math.roundToInt

private class VertexCoordinates { var value: LayoutCoordinates? = null }

internal fun curveVertexTag(id: String, index: Int) = "workspace-shape-vertex-$id-$index"

/** Fixed-size viewport handles shared by a curve selected with the pointer or the lasso. */
@Composable
internal fun CurveVertexHandles(
    curve: Outline.Shape,
    pageToView: (Offset) -> Offset,
    pageScale: Float,
    onPreview: (Outline.Shape?) -> Unit,
    onMove: (String, Int, Float, Float) -> Unit,
) {
    val currentCurve = rememberUpdatedState(curve)
    val preview = rememberUpdatedState(onPreview)
    val commit = rememberUpdatedState(onMove)
    Box(Modifier.fillMaxSize()) {
        curve.vertices().forEach { vertex ->
            val coordinates = remember(curve.id, vertex.index) { VertexCoordinates() }
            Box(Modifier.offset {
                val at = pageToView(Offset(vertex.x, vertex.y))
                IntOffset((at.x - 24.dp.toPx()).roundToInt(), (at.y - 24.dp.toPx()).roundToInt())
            }.size(48.dp).onGloballyPositioned { coordinates.value = it }
                .testTag(curveVertexTag(curve.id, vertex.index))
                .semantics { contentDescription = Strings.shape_vertex_point(vertex.index + 1) }
                .pointerInput(curve.id, vertex.index, pageScale) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed || down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed)
                            return@awaitEachGesture
                        val downAt = coordinates.value?.takeIf { it.isAttached }?.localToRoot(down.position)
                            ?: return@awaitEachGesture
                        val original = currentCurve.value
                        val start = original.vertices().getOrNull(vertex.index) ?: return@awaitEachGesture
                        down.consume()
                        var moved = original
                        var at = Offset(start.x, start.y)
                        var finished = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                val currentAt = coordinates.value?.takeIf { it.isAttached }?.localToRoot(change.position)
                                    ?: break
                                at = Offset(start.x, start.y) + (currentAt - downAt) / pageScale
                                moved = original.withVertex(vertex.index, at.x, at.y)
                                preview.value(moved)
                                change.consume()
                                if (!change.pressed) { finished = true; break }
                            }
                            if (finished && moved != original) commit.value(original.id, vertex.index, at.x, at.y)
                        } finally { preview.value(null) }
                    }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}
