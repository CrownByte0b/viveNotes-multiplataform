package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.model.ink.*
import java.util.Collections

/** Exact modeled projections; original Room rows and all operation bytes stay in storage. */
internal class DesktopInkSegment(projections: List<PageStroke>, sourceIds: Collection<String> = projections.map { it.id }) {
    val projections: List<PageStroke> = Collections.unmodifiableList(ArrayList(projections))
    val sourceIds: Set<String> = Collections.unmodifiableSet(HashSet(sourceIds))
    val bounds: InkBounds? = this.projections.mapNotNull { it.pageBounds }.unionBounds()
    val index: InkPageIndex = InkPageIndex(this.projections)
}

internal class DesktopInkPage private constructor(
    val base: DesktopInkSegment,
    val additions: DesktopInkSegment,
    val excludedRows: Set<String>,
    private val bounds: InkBounds?,
    val sourceStrokes: List<PageStroke>,
    val operations: List<DecodedInkOperation>,
    val excludedProjections: Set<InkProjectionKey> = emptySet(),
) : InkPageGeometry {
    constructor(projections: List<PageStroke>, sourceIds: Collection<String> = projections.map { it.id },
        sourceStrokes: List<PageStroke> = projections, operations: List<DecodedInkOperation> = emptyList()) :
        this(DesktopInkSegment(projections, sourceIds), EMPTY_SEGMENT, emptySet(), projections.mapNotNull { it.pageBounds }.unionBounds(),
            Collections.unmodifiableList(ArrayList(sourceStrokes)), Collections.unmodifiableList(ArrayList(operations)))

    override val rightDp: Float get() = bounds?.right ?: 0f
    override val bottomDp: Float get() = bounds?.bottom ?: 0f
    val projections: List<PageStroke> by lazy {
        Collections.unmodifiableList(base.projections.filterNot { it.id in excludedRows || it.projectionKey in excludedProjections } +
            additions.projections.filterNot { it.projectionKey in excludedProjections })
    }
    val isEmpty: Boolean get() = bounds == null
    fun containsSource(id: String): Boolean = (id in base.sourceIds && id !in excludedRows) || id in additions.sourceIds

    val index = DesktopInkQueries(this)

    override fun selectAt(page: InkPage, x: Float, y: Float, reach: Float): InkSelection? {
        if (!x.isFinite() || !y.isFinite() || !reach.isFinite() || reach < 0f) return null
        val hit = index.at(InkPoint(x, y), reach).lastOrNull() ?: return null
        val candidates = projections.filter { it.id == hit.id || (hit.groupId != null && it.groupId == hit.groupId) }
        val bounds = if (hit.groupId == null) hit.pageBounds else candidates.mapNotNull { it.pageBounds }.unionBounds()
        val box = bounds ?: return null
        val selection = candidates.selectWithLasso(box.selectionPath()) ?: return null
        return DesktopInkSelection(page, selection)
    }

    override fun selectInRect(page: InkPage, left: Float, top: Float, right: Float, bottom: Float): InkSelection? {
        if (listOf(left, top, right, bottom).any { !it.isFinite() } || right <= left || bottom <= top) return null
        val selected = projections.selectWithLasso(InkBounds(left, top, right, bottom).selectionPath()) ?: return null
        return DesktopInkSelection(page, selected)
    }

    override fun selectPastLine(page: InkPage, cut: com.vivenotes.model.SpaceCut): InkSelection? {
        val moving = projections.groupBy { it.groupId ?: it.id }.values.filter { unit ->
            val boxes = unit.mapNotNull { it.pageBounds }
            boxes.isNotEmpty() && boxes.all { cut.moves(it.left, it.top) }
        }.flatten()
        val box = moving.mapNotNull { it.pageBounds }.unionBounds() ?: return null
        val selected = moving.selectWithLasso(InkBounds(box.left - 6f, box.top - 6f, box.right + 6f, box.bottom + 6f).selectionPath()) ?: return null
        return DesktopInkSelection(page, selected)
    }

    override fun selectInLasso(page: InkPage, path: List<com.vivenotes.model.ink.InkSample>): InkSelection? {
        if (!com.vivenotes.model.ink.inkLassoCloses(path)) return null
        val selected = projections.selectWithLasso(path.map { InkPoint(it.x, it.y) }) ?: return null
        return DesktopInkSelection(page, selected)
    }

    fun withChanges(added: List<PageStroke>, removed: Set<String>): DesktopInkPage {
        if (added.isEmpty() && removed.isEmpty()) return this
        val excluded = Collections.unmodifiableSet(HashSet(excludedRows).apply {
            addAll(removed.filter { it in base.sourceIds })
        })
        val tail = if (added.isEmpty() && removed.none { it in additions.sourceIds }) additions else
            DesktopInkSegment(additions.projections.filterNot { it.id in removed } + added)
        val baseBounds = if (excluded == excludedRows) {
            // The previous extent includes the tail, which remains present when only appending.
            if (removed.isEmpty()) bounds else base.projections.filterNot { it.id in excluded || it.projectionKey in excludedProjections }.mapNotNull { it.pageBounds }.unionBounds()
        } else base.projections.filterNot { it.id in excluded || it.projectionKey in excludedProjections }.mapNotNull { it.pageBounds }.unionBounds()
        val tailBounds = if (excludedProjections.isEmpty()) tail.bounds else
            tail.projections.filterNot { it.projectionKey in excludedProjections }.mapNotNull { it.pageBounds }.unionBounds()
        val extent = listOfNotNull(baseBounds, tailBounds).unionBounds()
        val sources = appendInk(if (removed.isEmpty()) sourceStrokes else sourceStrokes.filterNot { it.id in removed }, added)
        if (tail.projections.size >= ADDITION_LIMIT || excluded.size >= TOMBSTONE_LIMIT || excludedProjections.size >= TOMBSTONE_LIMIT) {
            val live = base.projections.filterNot { it.id in excluded || it.projectionKey in excludedProjections } +
                tail.projections.filterNot { it.projectionKey in excludedProjections }
            val sourceIds = base.sourceIds.filterNot { it in excluded } + tail.sourceIds
            return DesktopInkPage(live, sourceIds, sources, operations)
        }
        return DesktopInkPage(base, tail, excluded, extent, sources, operations, excludedProjections)
    }

    fun withObjectErase(operation: DecodedInkOperation.Erase): DesktopInkPage? {
        val touched = index.touching(operation.mask).filter { it.id in operation.targetIds }
        if (touched.eraseObjects(operation.mask, operation.targetIds).isNotEmpty()) return null
        val excluded = Collections.unmodifiableSet(HashSet(excludedProjections).apply { addAll(touched.map { it.projectionKey }) })
        val live = (base.projections + additions.projections).filterNot { it.id in excludedRows || it.projectionKey in excluded }
        val nextBounds = live.mapNotNull { it.pageBounds }.unionBounds()
        return DesktopInkPage(base, additions, excludedRows, nextBounds, sourceStrokes,
            Collections.unmodifiableList(operations + operation), excluded)
    }

    companion object {
        const val ADDITION_LIMIT = 128
        const val TOMBSTONE_LIMIT = 256
        private val EMPTY_SEGMENT = DesktopInkSegment(emptyList())
    }
}

internal class DesktopInkQueries(private val page: DesktopInkPage) {
    fun touching(mask: Stroke): List<PageStroke> =
        page.base.index.touching(mask).filterNot { it.id in page.excludedRows || it.projectionKey in page.excludedProjections } +
            page.additions.index.touching(mask).filterNot { it.projectionKey in page.excludedProjections }
    fun at(point: InkPoint, reach: Float): List<PageStroke> =
        page.base.index.at(point, reach).filterNot { it.id in page.excludedRows || it.projectionKey in page.excludedProjections } +
            page.additions.index.at(point, reach).filterNot { it.projectionKey in page.excludedProjections }
    fun crossing(from: InkPoint, to: InkPoint, width: Float): List<PageStroke> =
        page.base.index.crossing(from, to, width).filterNot { it.id in page.excludedRows || it.projectionKey in page.excludedProjections } +
            page.additions.index.crossing(from, to, width).filterNot { it.projectionKey in page.excludedProjections }
}

internal fun InkStrokeEntity.toByteInk() = StoredInkStroke(id, pageId, seq, brushFamily,
    brushVersion, sizeDp, colorArgb, colorFollowsTheme, epsilon, stabilization, minX, minY,
    maxX, maxY, points, enc, createdAt, groupId, deletedAt)

internal fun StoredInkStroke.toEntity() = InkStrokeEntity(id, pageId, seq, brushFamily,
    brushVersion, sizeDp, colorArgb, colorFollowsTheme, epsilon, stabilization, minX, minY,
    maxX, maxY, points, enc, createdAt, groupId, deletedAt)

internal fun InkEraseWithTargets.toByteInk(): StoredInkErase = erase.let { row ->
    StoredInkErase(row.id, row.pageId, row.mode.name, row.sizeDp, row.points, row.enc,
        row.createdAt, row.deletedAt, targets.map { it.strokeId })
}

internal fun InkMoveWithTargets.toByteInk(): StoredInkMove = move.let { row ->
    StoredInkMove(row.id, row.pageId, row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX,
        row.anchorY, row.points, row.enc, row.createdAt, row.deletedAt, targets.map { it.strokeId })
}

/** Also supports portable synthetic pages used by previews and shared UI tests. */
internal fun InkPage.desktopGeometry(): DesktopInkPage = geometry as? DesktopInkPage ?: run {
    val rows = strokes.mapIndexed { seq, source ->
        val brush = ViveBrushes.brush(source.brushFamily, source.stabilization, source.colorArgb, source.sizeDp)
        val stroke = Stroke(brush, source.samples.inputs())
        ViveInkCodec.encodeStroke(stroke, source.id, pageId, seq, source.brushFamily,
            source.stabilization, source.colorFollowsTheme, 0L)
    }
    val erases = operations.filterIsInstance<InkPageOperation.Erase>().mapNotNull { row -> runCatching {
        ViveInkCodec.encodeErase(ViveBrushes.eraseMask(row.samples.inputs(), row.sizeDp),
            row.id, pageId, if (row.objectMode) InkEraseMode.Object else InkEraseMode.Normal,
            row.createdAt, row.targetIds.toList())
    }.getOrNull() }
    val moves = operations.filterIsInstance<InkPageOperation.Move>().map { row ->
        val path = row.path.ifEmpty { listOf(InkSample(-10000f, -10000f), InkSample(10000f, -10000f),
            InkSample(10000f, 10000f), InkSample(-10000f, 10000f)) }
        StoredInkMove(row.id, pageId, row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX,
            row.anchorY, ViveInkCodec.encodeMove(InkLassoMove(path.map { com.vivenotes.byteink.kit.InkPoint(it.x, it.y) },
                row.targetIds, emptySet(), row.dxDp, row.dyDp), row.id, pageId, row.createdAt).points, ViveInkCodec.MOVE_ENCODING,
            row.createdAt, targetIds = row.targetIds.toList())
    }
    val loaded = ViveInkPage.load(rows, erases, moves)
    DesktopInkPage(loaded.strokes, strokes.map { it.id }, loaded.sourceStrokes, loaded.operations)
}

internal fun List<InkSample>.inputs(): MutableStrokeInputBatch = MutableStrokeInputBatch().apply {
    this@inputs.forEachIndexed { index, point -> add(InputToolType.MOUSE, point.x, point.y,
        index * 10L, pressure = StrokeInput.NO_PRESSURE) }
}

internal fun InkPage.withDesktopEdit(edit: InkEdit): InkPage = withDesktopEdits(listOf(edit))

internal fun InkPage.withDesktopEdits(edits: List<InkEdit>): InkPage {
    if (edits.isEmpty()) return this
    if (edits.any { it is InkEdit.PartialErase || it is InkEdit.RestoreStrokes || it is InkEdit.Move }) {
        var page = this
        val rows = ArrayList<InkEdit>()
        fun flushRows() {
            if (rows.isNotEmpty()) { page = page.withDesktopEdits(rows.toList()); rows.clear() }
        }
        for (edit in edits) when (edit) {
            is InkEdit.PartialErase -> { flushRows(); page = page.withPartialErase(edit) }
            is InkEdit.Move -> { flushRows(); page = page.withMove(edit) }
            is InkEdit.RestoreStrokes -> { flushRows(); page = page.withRestoredStrokes(edit) }
            else -> rows.add(edit)
        }
        flushRows()
        return page
    }
    val original = desktopGeometry()
    val added = LinkedHashMap<String, Pair<VisibleInkStroke, PageStroke>>()
    val removed = HashSet<String>()
    for (edit in edits) when (edit) {
        is InkEdit.AddStroke -> {
            val row = edit.row
            require(row.pageId == pageId) { "Stroke belongs to another page" }
            if ((original.containsSource(row.id) && row.id !in removed) || row.id in added) continue
            val prepared = edit.geometry as? DesktopInkStroke
            val native = if (prepared?.row === row) prepared.projection else
                requireNotNull(ViveInkPage.decode(row.toByteInk()))
            added[row.id] = native.portableStroke() to native
        }
        is InkEdit.EraseStrokes -> {
            removed.addAll(edit.ids)
            edit.ids.forEach(added::remove)
        }
        is InkEdit.PartialErase, is InkEdit.RestoreStrokes, is InkEdit.Move -> error("Operation edits must be replayed in order")
    }
    if (added.isEmpty() && removed.isEmpty()) return this
    val geometry = original.withChanges(added.values.map { it.second }, removed)
    val kept = if (removed.isEmpty()) strokes else strokes.filterNot { it.id in removed }
    val visible = appendPortable(kept, added.values.map { it.first })
    return copy(strokes = visible, geometry = geometry)
}

private fun InkPage.withPartialErase(edit: InkEdit.PartialErase): InkPage {
    require(edit.row.pageId == pageId) { "Erase belongs to another page" }
    val native = desktopGeometry()
    if (edit.active == native.operations.any { it.id == edit.row.id }) return this
    val operations = native.operations.filterNot { it.id == edit.row.id }.toMutableList()
    if (edit.active) {
        val row = edit.row
        val stored = StoredInkErase(row.id, pageId, row.mode.name, row.sizeDp, row.points, row.enc,
            row.createdAt, targetIds = edit.targetIds.toList())
        val decoded = ViveInkPage.load(emptyList(), listOf(stored), emptyList()).operations.single()
        if (decoded is DecodedInkOperation.Erase && decoded.mode == InkEraseMode.Object &&
            native.operations.all { it.createdAt < decoded.createdAt }) {
            native.withObjectErase(decoded)?.let { projected ->
                return copy(geometry = projected, operations = this.operations + listOfNotNull(decoded.portableOperation()),
                    latestOperationAt = maxOf(latestOperationAt, edit.row.createdAt))
            }
        }
        operations.add(decoded)
    }
    return replaySources(native.sourceStrokes, operations).copy(
        latestOperationAt = maxOf(latestOperationAt, edit.row.createdAt))
}

private fun InkPage.withRestoredStrokes(edit: InkEdit.RestoreStrokes): InkPage {
    require(edit.source.pageId == pageId) { "Stroke restore belongs to another page" }
    val native = desktopGeometry()
    val restored = edit.source.desktopGeometry().sourceStrokes
    val current = native.sourceStrokes.associateBy { it.id }
    val originalIds = restored.mapTo(HashSet()) { it.id }
    val sources = restored.mapNotNull { current[it.id] ?: it.takeIf { it.id in edit.ids } } +
        native.sourceStrokes.filterNot { it.id in originalIds }
    val visible = strokes.associateBy { it.id }
    val portable = (edit.source.strokes.mapNotNull { source -> visible[source.id]
        ?: source.takeIf { it.id in edit.ids } } + strokes.filterNot { it.id in originalIds })
    return copy(strokes = portable).replaySources(sources, native.operations)
}

private fun InkPage.withMove(edit: InkEdit.Move): InkPage {
    require(edit.row.pageId == pageId) { "Move belongs to another page" }
    val native = desktopGeometry()
    if (edit.active == native.operations.any { it.id == edit.row.id }) return this
    val operations = native.operations.filterNot { it.id == edit.row.id }.toMutableList()
    if (edit.active) {
        val row = edit.row
        val stored = StoredInkMove(row.id, pageId, row.dxDp, row.dyDp, row.scaleX, row.scaleY,
            row.anchorX, row.anchorY, row.points, row.enc, row.createdAt, targetIds = edit.targetIds.toList())
        operations += ViveInkPage.load(emptyList(), emptyList(), listOf(stored)).operations.single()
    }
    return replaySources(native.sourceStrokes, operations).copy(latestOperationAt = maxOf(latestOperationAt, edit.row.createdAt))
}

private fun InkPage.replaySources(sources: List<PageStroke>, operations: List<DecodedInkOperation>): InkPage =
    copy(geometry = DesktopInkPage(ViveInkPage.replay(sources, operations), sources.map { it.id }, sources, operations),
        operations = operations.sortedWith(compareBy(DecodedInkOperation::createdAt, DecodedInkOperation::id)).mapNotNull { it.portableOperation() })

private class AppendedInkList<T>(val base: List<T>, val tail: List<T>) : AbstractList<T>() {
    override val size: Int get() = base.size + tail.size
    override fun get(index: Int): T {
        checkElementIndex(index, size)
        return if (index < base.size) base[index] else tail[index - base.size]
    }
    private fun checkElementIndex(index: Int, size: Int) { if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size") }
}

private fun appendPortable(base: List<VisibleInkStroke>, tail: List<VisibleInkStroke>): List<VisibleInkStroke> = appendInk(base, tail)

private fun <T> appendInk(base: List<T>, tail: List<T>): List<T> {
    if (tail.isEmpty()) return base
    val result = if (base is AppendedInkList) AppendedInkList(base.base, base.tail + tail) else AppendedInkList(base, tail)
    return if (result.tail.size >= DesktopInkPage.ADDITION_LIMIT) result.toList() else result
}

internal class DesktopInkStroke(val row: InkStrokeEntity, val projection: PageStroke) : InkStrokeGeometry

internal fun AuthoredViveStroke.desktopEdit(): InkEdit.AddStroke {
    val entity = row.toEntity()
    val projection = PageStroke(entity.id, canonicalStroke, brushFamily = entity.brushFamily,
        brushVersion = entity.brushVersion, stabilization = entity.stabilization,
        colorFollowsTheme = entity.colorFollowsTheme, groupId = entity.groupId)
    return InkEdit.AddStroke(entity, DesktopInkStroke(entity, projection))
}

internal fun PageStroke.portableStroke(): VisibleInkStroke = VisibleInkStroke(id, brushFamily, brushVersion,
    stroke.brush.size, stroke.brush.colorIntArgb, colorFollowsTheme, NativeInkSamples(stroke.inputs), stabilization, stroke.brush.epsilon)

internal class NativeInkSamples(private val inputs: androidx.ink.strokes.StrokeInputBatch) : AbstractList<InkSample>() {
    override val size: Int = inputs.size
    internal var materializations: Int = 0
        private set
    private val samples by lazy {
        materializations++
        val sample = StrokeInput()
        val pressure = inputs.hasPressure()
        List(size) { index ->
            inputs.populate(index, sample)
            InkSample(sample.x, sample.y, sample.pressure.takeIf { pressure })
        }
    }
    override fun get(index: Int): InkSample = samples[index]
}
