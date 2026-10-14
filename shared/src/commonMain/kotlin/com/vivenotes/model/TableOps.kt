package com.vivenotes.model

import com.vivenotes.model.Outline.Table

private fun emptyCell(id: String, inkOnly: Boolean): TableCell =
    if (inkOnly) TableCell(id = id) else TableCell.empty(id)

val Table.canAddRow: Boolean
    get() = rowCount < Table.MAX_ROWS && (rowCount + 1) * columnCount.coerceAtLeast(1) <= Table.MAX_CELLS

val Table.canAddColumn: Boolean
    get() = columnCount < Table.MAX_COLUMNS && rowCount.coerceAtLeast(1) * (columnCount + 1) <= Table.MAX_CELLS

val Table.canRemoveRow: Boolean get() = rowCount > 1

val Table.canRemoveColumn: Boolean get() = columnCount > 1

fun newTable(
    columns: Int,
    rows: Int,
    x: Float = 0f,
    y: Float = 0f,
    columnWidth: Float = Table.DEFAULT_COLUMN_WIDTH,
    rowHeight: Float = Table.DEFAULT_ROW_HEIGHT,
    headerRow: Boolean = false,
    headerColumn: Boolean = false,
    borderArgb: Int = 0xFF000000.toInt(),
    borderFollowsTheme: Boolean? = null,
    borderWidth: Float = 1f,
    fillArgb: Int? = null,
    
    inkOnly: Boolean = false,
    newId: () -> String = ::newId,
): Table {
    val columnCount = columns.coerceIn(1, Table.MAX_COLUMNS)
    val rowCount = rows.coerceIn(1, minOf(Table.MAX_ROWS, Table.MAX_CELLS / columnCount))
    return Table(
        id = newId(),
        x = x,
        y = y,
        columns = List(columnCount) { columnWidth.coerceIn(Table.MIN_COLUMN_WIDTH, Table.MAX_COLUMN_WIDTH) },
        rows = List(rowCount) {
            TableRow(
                id = newId(),
                minHeight = rowHeight.coerceIn(Table.MIN_ROW_HEIGHT, Table.MAX_ROW_HEIGHT),
                cells = List(columnCount) { emptyCell(newId(), inkOnly) },
            )
        },
        headerRow = headerRow,
        headerColumn = headerColumn,
        borderArgb = borderArgb,
        borderFollowsTheme = borderFollowsTheme,
        borderWidth = borderWidth,
        fillArgb = fillArgb,
        inkOnly = inkOnly,
    ).withRecomputedWidth()
}

fun Table.withRowInserted(at: Int, newId: () -> String = ::newId): Table {
    if (!canAddRow) return this
    val index = at.coerceIn(0, rowCount)
    val neighbour = rows.getOrNull(index) ?: rows.lastOrNull()
    val row = TableRow(
        id = newId(),
        minHeight = neighbour?.minHeight ?: Table.DEFAULT_ROW_HEIGHT,
        cells = List(columnCount) { emptyCell(newId(), inkOnly) },
    )
    return copy(rows = rows.toMutableList().apply { add(index, row) })
}

fun Table.withRowRemoved(at: Int): Table {
    if (!canRemoveRow) return this
    val index = at.coerceIn(0, rowCount - 1)
    return copy(rows = rows.filterIndexed { position, _ -> position != index })
}

fun Table.withColumnInserted(at: Int, newId: () -> String = ::newId): Table {
    if (!canAddColumn) return this
    val index = at.coerceIn(0, columnCount)
    val width = columns.getOrNull(index) ?: columns.lastOrNull() ?: Table.DEFAULT_COLUMN_WIDTH
    return copy(
        columns = columns.toMutableList().apply { add(index, width) },
        rows = rows.map { row ->
            row.copy(cells = row.cells.toMutableList().apply { add(index, emptyCell(newId(), inkOnly)) })
        },
    ).withRecomputedWidth()
}

fun Table.withColumnRemoved(at: Int): Table {
    if (!canRemoveColumn) return this
    val index = at.coerceIn(0, columnCount - 1)
    return copy(
        columns = columns.filterIndexed { position, _ -> position != index },
        rows = rows.map { row ->
            row.copy(cells = row.cells.filterIndexed { position, _ -> position != index })
        },
    ).withRecomputedWidth()
}

fun Table.withColumnWidth(at: Int, width: Float): Table {
    if (at !in columns.indices) return this
    val clamped = width.coerceIn(Table.MIN_COLUMN_WIDTH, Table.MAX_COLUMN_WIDTH)
    if (columns[at] == clamped) return this
    return copy(columns = columns.mapIndexed { index, value -> if (index == at) clamped else value })
        .withRecomputedWidth()
}

fun Table.withRowMinHeight(at: Int, minHeight: Float): Table {
    if (at !in rows.indices) return this
    val clamped = minHeight.coerceIn(Table.MIN_ROW_HEIGHT, Table.MAX_ROW_HEIGHT)
    if (rows[at].minHeight == clamped) return this
    return copy(
        rows = rows.mapIndexed { index, row -> if (index == at) row.copy(minHeight = clamped) else row },
    )
}

fun Table.withNewIds(newId: () -> String = ::newId): Table = copy(
    id = newId(),
    rows = rows.map { row ->
        row.copy(id = newId(), cells = row.cells.map { it.copy(id = newId()) })
    },
)

fun Table.withCellBlocks(blocks: Map<String, List<Block>>): Table = copy(
    rows = rows.map { row ->
        row.copy(
            cells = row.cells.map { cell ->
                blocks[cell.id]?.let { cell.copy(blocks = it) } ?: cell
            },
        )
    },
)