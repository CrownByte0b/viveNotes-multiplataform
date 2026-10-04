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
) : InkPageGeometry {
    constructor(projections: List<PageStroke>, sourceIds: Collection<String> = projections.map { it.id }) :
        this(DesktopInkSegment(projections, sourceIds), EMPTY_SEGMENT, emptySet(), projections.mapNotNull { it.pageBounds }.unionBounds())

    override val rightDp: Float get() = bounds?.right ?: 0f
    override val bottomDp: Float get() = bounds?.bottom ?: 0f
    val projections: List<PageStroke> by lazy {
        Collections.unmodifiableList(base.projections.filterNot { it.id in excludedRows } + additions.projections)
    }
    val isEmpty: Boolean get() = bounds == null
    fun containsSource(id: String): Boolean = (id in base.sourceIds && id !in excludedRows) || id in additions.sourceIds

    val index = DesktopInkQueries(this)

    fun withChanges(added: List<PageStroke>, removed: Set<String>): DesktopInkPage {
        if (added.isEmpty() && removed.isEmpty()) return this
        val excluded = Collections.unmodifiableSet(HashSet(excludedRows).apply {
            addAll(removed.filter { it in base.sourceIds })
        })
        val tail = if (added.isEmpty() && removed.none { it in additions.sourceIds }) additions else
            DesktopInkSegment(additions.projections.filterNot { it.id in removed } + added)
        val baseBounds = if (excluded == excludedRows) {
            // The previous extent includes the tail, which remains present when only appending.
            if (removed.isEmpty()) bounds else base.projections.filterNot { it.id in excluded }.mapNotNull { it.pageBounds }.unionBounds()
        } else base.projections.filterNot { it.id in excluded }.mapNotNull { it.pageBounds }.unionBounds()
        val extent = listOfNotNull(baseBounds, tail.bounds).unionBounds()
        if (tail.projections.size >= ADDITION_LIMIT || excluded.size >= TOMBSTONE_LIMIT) {
            val live = base.projections.filterNot { it.id in excluded } + tail.projections
            val sources = base.sourceIds.filterNot { it in excluded } + tail.sourceIds
            return DesktopInkPage(live, sources)
        }
        return DesktopInkPage(base, tail, excluded, extent)
    }

    companion object {
        const val ADDITION_LIMIT = 128
        const val TOMBSTONE_LIMIT = 256
        private val EMPTY_SEGMENT = DesktopInkSegment(emptyList())
    }
}

internal class DesktopInkQueries(private val page: DesktopInkPage) {
    fun at(point: InkPoint, reach: Float): List<PageStroke> =
        page.base.index.at(point, reach).filterNot { it.id in page.excludedRows } + page.additions.index.at(point, reach)
    fun crossing(from: InkPoint, to: InkPoint, width: Float): List<PageStroke> =
        page.base.index.crossing(from, to, width).filterNot { it.id in page.excludedRows } + page.additions.index.crossing(from, to, width)
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
    DesktopInkPage(ViveInkPage.load(rows, erases, moves).strokes, strokes.map { it.id })
}

internal fun List<InkSample>.inputs(): MutableStrokeInputBatch = MutableStrokeInputBatch().apply {
    this@inputs.forEachIndexed { index, point -> add(InputToolType.MOUSE, point.x, point.y,
        index * 10L, pressure = StrokeInput.NO_PRESSURE) }
}

internal fun InkPage.withDesktopEdit(edit: InkEdit): InkPage = withDesktopEdits(listOf(edit))

internal fun InkPage.withDesktopEdits(edits: List<InkEdit>): InkPage {
    if (edits.isEmpty()) return this
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
    }
    if (added.isEmpty() && removed.isEmpty()) return this
    val geometry = original.withChanges(added.values.map { it.second }, removed)
    val kept = if (removed.isEmpty()) strokes else strokes.filterNot { it.id in removed }
    val visible = appendPortable(kept, added.values.map { it.first })
    return copy(strokes = visible, geometry = geometry)
}

private class AppendedInkList(val base: List<VisibleInkStroke>, val tail: List<VisibleInkStroke>) : AbstractList<VisibleInkStroke>() {
    override val size: Int get() = base.size + tail.size
    override fun get(index: Int): VisibleInkStroke {
        checkElementIndex(index, size)
        return if (index < base.size) base[index] else tail[index - base.size]
    }
    private fun checkElementIndex(index: Int, size: Int) { if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size") }
}

private fun appendPortable(base: List<VisibleInkStroke>, tail: List<VisibleInkStroke>): List<VisibleInkStroke> {
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
