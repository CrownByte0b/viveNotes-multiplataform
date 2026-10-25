package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.ui.canvas.LocalObjectGrab
import com.vivenotes.ui.canvas.detectObjectDrag
import com.vivenotes.ui.canvas.primeResizePointer
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.TABLE_GUTTER
import com.vivenotes.workspace.WorkspaceState

/**
 * One row or one column of one table, held by a click on its gutter handle (Android `TableAxis`).
 *
 * Not part of the object selection: it is a place inside the one selected table, which is what the
 * toolkit's row and column verbs act on. Transient canvas state, never saved.
 */
internal sealed interface TableAxis {
    val tableId: String
    val index: Int

    data class Row(override val tableId: String, override val index: Int) : TableAxis
    data class Column(override val tableId: String, override val index: Int) : TableAxis
}

/** The hold while it still means something: its table alone is selected and still has that row or column. */
internal fun TableAxis?.heldIn(state: WorkspaceState): TableAxis? {
    val axis = this ?: return null
    if (state.selectedObjectIds != setOf(axis.tableId) || state.selectedInk != null ||
        state.selectedTextOutlineIds.isNotEmpty()) return null
    val table = state.selectedPrimeObjects.singleOrNull() as? Outline.Table ?: return null
    return axis.takeIf {
        when (it) {
            is TableAxis.Row -> it.index in table.rows.indices
            is TableAxis.Column -> it.index in table.columns.indices
        }
    }
}

/**
 * A selected table's handles, laid over the gutter its `(x, y)` reserves above and left of the grid.
 *
 * Each column has a bar in the top gutter and each row one in the left gutter: a click holds that
 * column or row (a second click lets go) and a drag sets its width or floor. The square where the
 * gutters meet is the move grip, and the grid's bottom-right corner scales the whole table. A lock
 * keeps the bars for holding but drops every drag, as on Android.
 */
@Composable
internal fun TableChrome(
    table: Outline.Table,
    rows: List<Float>,
    held: TableAxis?,
    grabbable: Boolean,
    onHold: (TableAxis?) -> Unit,
    onColumnWidth: (column: Int, width: Float, first: Boolean) -> Unit,
    onRowMinHeight: (row: Int, minHeight: Float, first: Boolean) -> Unit,
    onMove: (dx: Float, dy: Float, first: Boolean) -> Unit,
    onScale: (scaleX: Float, scaleY: Float, first: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val current by rememberUpdatedState(table)
    val currentRows by rememberUpdatedState(rows)
    val currentHeld by rememberUpdatedState(held)
    val currentMove by rememberUpdatedState(onMove)
    val currentScale by rememberUpdatedState(onScale)
    val grab = LocalObjectGrab.current
    val gutter = TABLE_GUTTER.dp
    val gridHeight = rows.sum()

    if (grabbable) {
        // Larger than the gutter, so it grows up and left from the grid's corner and stays clear of
        // the first column and row bars.
        Box(Modifier.offset((table.x + TABLE_GUTTER).dp - TableGripSize, (table.y + TABLE_GUTTER).dp - TableGripSize)
            .size(TableGripSize)
            .clip(RoundedCornerShape(topStart = 5.dp))
            .background(accent)
            .testTag(TableTags.Move)
            .semantics { contentDescription = Strings.move_table }
            .pointerInput(table.id) {
                var first = true
                detectObjectDrag(grab, onDragStart = { first = true }) { change, drag ->
                    change.consume()
                    currentMove(with(density) { drag.x.toDp().value }, with(density) { drag.y.toDp().value }, first)
                    first = false
                }
            },
            contentAlignment = Alignment.Center) {
            Icon(NavigationSymbols.DragIndicator, contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(TableGripSize - 4.dp))
        }
    }

    var left = table.x + TABLE_GUTTER
    table.columns.forEachIndexed { index, width ->
        val axis = TableAxis.Column(table.id, index)
        AxisHandle(
            modifier = Modifier.offset(left.dp, table.y.dp).size(width.dp, gutter)
                .testTag(TableTags.columnHandle(table.id, index)),
            label = Strings.table_column_value(index + 1),
            horizontal = true,
            held = held == axis,
            accent = accent,
            onTap = { onHold(if (currentHeld == axis) null else axis) },
            size = { current.columns.getOrElse(index) { width } },
            onResize = if (!grabbable) null else { size, first -> onColumnWidth(index, size, first) },
        )
        left += width
    }

    var top = table.y + TABLE_GUTTER
    rows.forEachIndexed { index, height ->
        val axis = TableAxis.Row(table.id, index)
        AxisHandle(
            modifier = Modifier.offset(table.x.dp, top.dp).size(gutter, height.dp)
                .testTag(TableTags.rowHandle(table.id, index)),
            label = Strings.table_row_value(index + 1),
            horizontal = false,
            held = held == axis,
            accent = accent,
            onTap = { onHold(if (currentHeld == axis) null else axis) },
            // From the height the row is drawn at, which its text may hold above its floor.
            size = { currentRows.getOrElse(index) { height } },
            onResize = if (!grabbable) null else { size, first -> onRowMinHeight(index, size, first) },
        )
        top += height
    }

    // The grid's own outline, dashed like a shape's selection, inside the gutters.
    Box(Modifier.offset((table.x + TABLE_GUTTER).dp, (table.y + TABLE_GUTTER).dp)
        .size(table.width.dp, gridHeight.dp)
        .testTag(TableTags.SelectionOutline)
        .drawBehind {
            drawRect(accent, style = Stroke(width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
        })

    if (grabbable) {
        // One scale handle, at the grid's bottom right: the top and left edges carry the gutters.
        val surface = MaterialTheme.colorScheme.surface
        Box(Modifier.offset((table.x + TABLE_GUTTER + table.width).dp - 7.dp, (table.y + TABLE_GUTTER + gridHeight).dp - 7.dp)
            .size(14.dp)
            .zIndex(1f)
            .clip(CircleShape)
            .background(surface)
            .drawBehind { drawCircle(accent, size.minDimension / 2f - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx())) }
            .testTag(WorkspaceTestTags.objectCorner(table.id, 3))
            .semantics { contentDescription = Strings.resize_value(Strings.table) }
            .primeResizePointer(3)
            .pointerInput(table.id) {
                var total = Offset.Zero
                var previousX = 1f
                var previousY = 1f
                var first = true
                detectDragGestures(onDragStart = { total = Offset.Zero; previousX = 1f; previousY = 1f; first = true }) { change, drag ->
                    change.consume()
                    total += drag
                    val width = current.width.coerceAtLeast(1f)
                    val height = currentRows.sum().coerceAtLeast(1f)
                    val sx = ((width + with(density) { total.x.toDp().value }) / width).coerceAtLeast(MIN_SCALE)
                    val sy = ((height + with(density) { total.y.toDp().value }) / height).coerceAtLeast(MIN_SCALE)
                    currentScale(sx / previousX, sy / previousY, first)
                    previousX = sx
                    previousY = sy
                    first = false
                }
            })
    }
}

/**
 * One gutter bar: a click holds its row or column, a drag past the slop resizes it.
 *
 * One hand-written gesture rather than a tap detector beside a drag detector, which cannot share a
 * target — the tap arm claims the press the drag arm waits for (Android `pointerInputAxis`).
 * [onResize] receives [size] as it was when the drag began plus the travel since, in page dp.
 */
@Composable
private fun AxisHandle(
    modifier: Modifier,
    label: String,
    horizontal: Boolean,
    held: Boolean,
    accent: Color,
    onTap: () -> Unit,
    size: () -> Float,
    onResize: ((size: Float, first: Boolean) -> Unit)?,
) {
    val density = LocalDensity.current
    val currentTap by rememberUpdatedState(onTap)
    val currentSize by rememberUpdatedState(size)
    val currentResize by rememberUpdatedState(onResize)
    Box(modifier
        .semantics {
            contentDescription = label
            selected = held
            onClick { currentTap(); true }
        }
        .pointerInput(horizontal) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var travelled = false
                var first = true
                var from = Offset.Zero
                var startSize = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) {
                        if (!travelled) currentTap()
                        break
                    }
                    if (!travelled && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        travelled = true
                        // Measured from where the slop was spent, so deciding costs no resize.
                        from = change.position
                        startSize = currentSize()
                    }
                    val resize = currentResize
                    if (travelled && resize != null) {
                        val moved = change.position - from
                        val along = with(density) { (if (horizontal) moved.x else moved.y).toDp().value }
                        if (along != 0f || !first) {
                            resize(startSize + along, first)
                            first = false
                        }
                    }
                }
            }
        }
        .padding(horizontal = if (horizontal) 1.dp else 4.dp, vertical = if (horizontal) 4.dp else 1.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(accent.copy(alpha = if (held) 1f else 0.3f)),
        contentAlignment = if (horizontal) Alignment.CenterEnd else Alignment.BottomCenter) {
        Spacer(Modifier
            .then(if (horizontal) Modifier.width(3.dp).height(TABLE_GUTTER.dp - 6.dp)
                else Modifier.height(3.dp).width(TABLE_GUTTER.dp - 6.dp))
            .clip(RoundedCornerShape(2.dp))
            .background(HANDLE_KNOB))
    }
}

/** The move grip's side: a little more than the 16 dp gutter, which made it a small target. */
internal val TableGripSize = 22.dp

/** The grab knob on a gutter bar, light in both themes because it sits on the bar's own blue. */
private val HANDLE_KNOB = Color(0xFFF2F0EF)

/** Never through zero: a table flipped inside out by a fast drag cannot be dragged back. */
private const val MIN_SCALE = 0.15f
