package com.vivenotes.model

/** Immutable derived lookup; deliberately outside the document's serialized constructor. */
internal class DocumentTextIndex(outlines: List<Outline>) {
    private val containers = buildMap<String, Outline.Text> {
        for (outline in outlines) if (outline is Outline.Text && outline.id !in this) put(outline.id, outline)
        for (table in outlines) if (table is Outline.Table && !table.inkOnly && table.lockGroup == null) {
            var top = 0.0
            for (row in table.rows) {
                var left = 0f
                row.cells.forEachIndexed { column, cell ->
                    if (cell.id !in this) put(cell.id, Outline.Text(id = cell.id,
                        x = table.x + left, y = table.y + top.toFloat(),
                        width = table.columns[column], minHeight = row.minHeight, blocks = cell.blocks))
                    left += table.columns[column]
                }
                top += row.minHeight.toDouble()
            }
        }
    }

    operator fun get(id: String): Outline.Text? = containers[id]
}
