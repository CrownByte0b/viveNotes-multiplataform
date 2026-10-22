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
internal class DesktopInkSegment(projections: List<PageStroke>, sourceIds: Collection<String>? = null) {
    val projections: List<PageStroke> = Collections.unmodifiableList(ArrayList(projections))
    val sourceIds: Set<String> = Collections.unmodifiableSet(HashSet(sourceIds ?: projections.map { it.id }))
    private val unprojected = if (sourceIds == null) null else lazy {
        if (this.projections.isEmpty()) this.sourceIds else
            HashSet(this.sourceIds).apply { this@DesktopInkSegment.projections.forEach { remove(it.id) } }
                .let { if (it.isEmpty()) emptySet() else Collections.unmodifiableSet(it) }
    }
    val unprojectedSourceIds: Set<String> get() = unprojected?.value ?: emptySet()
    val bounds: InkBounds? = this.projections.mapNotNull { it.pageBounds }.unionBounds()
    private val preparedIndex = lazy { InkPageIndex(this.projections) }
    val index: InkPageIndex get() = preparedIndex.value
    internal val indexReady: Boolean get() = preparedIndex.isInitialized()
    init {
        if (this.projections.size > DesktopInkPage.ADDITION_LIMIT) preparedIndex.value
    }
    val byProjection by lazy { this.projections.associateBy { it.projectionKey } }
    val ordinal by lazy { this.projections.mapIndexed { index, projection -> projection.projectionKey to index }.toMap() }
    val byGroup by lazy { this.projections.filter { it.groupId != null }.groupBy { it.groupId!! } }
    val groupIds: Set<String> = this.projections.mapNotNullTo(HashSet()) { it.groupId }
}

internal class DesktopInkPage private constructor(
    val base: DesktopInkSegment,
    val additions: DesktopInkSegment,
    val excludedRows: Set<String>,
    private val bounds: InkBounds?,
    val sourceStrokes: List<PageStroke>,
    val operations: List<DecodedInkOperation>,
    val excludedProjections: Set<InkProjectionKey> = emptySet(),
    val retainedSegments: InkSegmentStore = InkSegmentStore.Empty,
) : InkPageGeometry {
    constructor(projections: List<PageStroke>, sourceIds: Collection<String> = projections.map { it.id },
        sourceStrokes: List<PageStroke> = projections, operations: List<DecodedInkOperation> = emptyList()) :
        this(DesktopInkSegment(projections, sourceIds), EMPTY_SEGMENT, emptySet(), projections.mapNotNull { it.pageBounds }.unionBounds(),
            Collections.unmodifiableList(ArrayList(sourceStrokes)), Collections.unmodifiableList(ArrayList(operations)))

    override val rightDp: Float get() = bounds?.right ?: 0f
    override val bottomDp: Float get() = bounds?.bottom ?: 0f
    val projections: List<PageStroke> by lazy {
        Collections.unmodifiableList(segments().flatMap { segment -> segment.projections.filter(::isLive) })
    }
    val isEmpty: Boolean get() = bounds == null
    fun containsSource(id: String): Boolean = id !in excludedRows &&
        (id in base.sourceIds || id in additions.sourceIds || retainedSegments.containsSource(id))
    private val segmentView = object : AbstractList<DesktopInkSegment>() {
        override val size: Int get() = retainedSegments.size + 2
        override fun get(index: Int): DesktopInkSegment = when {
            index == 0 -> base
            index in 1..retainedSegments.size -> retainedSegments[index - 1]
            index == size - 1 -> additions
            else -> throw IndexOutOfBoundsException("$index outside $size ink segments")
        }
        override fun iterator(): Iterator<DesktopInkSegment> = sequence {
            yield(base)
            yieldAll(retainedSegments)
            yield(additions)
        }.iterator()
    }
    fun segments(): List<DesktopInkSegment> = segmentView
    fun isLive(stroke: PageStroke): Boolean = stroke.id !in excludedRows && stroke.projectionKey !in excludedProjections
    fun selectedProjections(keys: Set<InkProjectionKey>): List<PageStroke> = segmentsForRows(keys.mapTo(HashSet()) { it.strokeId }).flatMap { segment ->
        if (keys.size < segment.projections.size)
            keys.mapNotNull(segment.byProjection::get).filter(::isLive).sortedBy { segment.ordinal[it.projectionKey] }
        else segment.projections.filter { it.projectionKey in keys && isLive(it) }
    }
    private fun grouped(group: String): List<PageStroke> = segmentsForRowsAndGroups(emptySet(), setOf(group))
        .flatMap { it.byGroup[group].orEmpty().filter(::isLive) }

    fun segmentsForRows(rows: Set<String>): List<DesktopInkSegment> = segmentsForRowsAndGroups(rows, emptySet())
    fun segmentsForRowsAndGroups(rows: Set<String>, groups: Set<String>): List<DesktopInkSegment> = buildList {
        if (base.projections.isNotEmpty() && (rows.any { it in base.sourceIds } || groups.any { it in base.groupIds })) add(base)
        val retained = (retainedSegments.forRows(rows) + retainedSegments.forGroups(groups)).distinctBy { it.first }.sortedBy { it.first }
        retained.forEach { add(it.second) }
        if (additions.projections.isNotEmpty() && (rows.any { it in additions.sourceIds } || groups.any { it in additions.groupIds })) add(additions)
    }

    fun nearSegments(box: InkBounds, work: InkQueryWork? = null): List<DesktopInkSegment> = buildList {
        work?.let { it.segmentBoundsVisited++ }
        if (base.bounds.meets(box)) add(base)
        addAll(retainedSegments.near(box, work))
        work?.let { it.segmentBoundsVisited++ }
        if (additions.bounds.meets(box)) add(additions)
    }

    val index = DesktopInkQueries(this)

    internal var basePartitionBuildCount = 0
        private set
    internal var basePartitionThread: String? = null
        private set
    internal var projectionReclamationBuildCount = 0
        private set
    internal var projectionReclamationThread: String? = null
        private set
    val needsProjectionReclamation: Boolean get() = base.sourceIds.isNotEmpty() && excludedRows.size >= TOMBSTONE_LIMIT ||
        excludedProjections.size >= TOMBSTONE_LIMIT || retainedSegments.emptySlots >= TOMBSTONE_LIMIT &&
        retainedSegments.emptySlots >= retainedSegments.size / 2
    private val editablePartition: DesktopInkPage by lazy {
        basePartitionBuildCount++
        basePartitionThread = Thread.currentThread().name
        val chunks = base.projections.filter(::isLive).chunked(ADDITION_LIMIT).map { DesktopInkSegment(it) }
        val kept = retainedSegments.mapNotNull { segment ->
            val live = if (segment.projections.all(::isLive) && segment.sourceIds.none { it in excludedRows }) segment
                else DesktopInkSegment(segment.projections.filter(::isLive), segment.sourceIds.filterNot { it in excludedRows })
            live.takeUnless { it.projections.isEmpty() && it.sourceIds.isEmpty() }
        }
        val tail = if (additions.projections.all(::isLive) && additions.sourceIds.none { it in excludedRows }) additions
            else DesktopInkSegment(additions.projections.filter(::isLive), additions.sourceIds.filterNot { it in excludedRows })
        val represented = HashSet<String>().apply {
            chunks.forEach { addAll(it.sourceIds) }; kept.forEach { addAll(it.sourceIds) }; addAll(tail.sourceIds)
        }
        val sourceOnly = base.sourceIds.filterNot { it in excludedRows || it in represented }.sorted().chunked(ADDITION_LIMIT)
            .map { DesktopInkSegment(emptyList(), it) }
        DesktopInkPage(EMPTY_SEGMENT, tail, emptySet(), bounds, sourceStrokes,
            operations, emptySet(), InkSegmentStore.Empty.append(chunks + sourceOnly + kept))
    }
    private val reclaimedProjectionSnapshot: DesktopInkPage by lazy {
        projectionReclamationBuildCount++
        projectionReclamationThread = Thread.currentThread().name
        fun reclaim(segment: DesktopInkSegment): DesktopInkSegment {
            if (segment.projections.all(::isLive) && segment.sourceIds.none { it in excludedRows }) return segment
            val live = segment.projections.filter(::isLive)
            val sources = if (excludedRows.isEmpty()) segment.sourceIds else segment.sourceIds.filterNot { it in excludedRows }
            return if (live.isEmpty() && sources.isEmpty()) EMPTY_SEGMENT else DesktopInkSegment(live, sources)
        }
        val rows = HashSet(excludedRows).apply { excludedProjections.forEach { add(it.strokeId) } }
        val replacements = retainedSegments.forRows(rows).mapNotNull { (index, segment) ->
            reclaim(segment).takeUnless { it === segment }?.let { index to it }
        }.toMap()
        val retained = retainedSegments.replace(replacements)
        val compact = retained.emptySlots >= TOMBSTONE_LIMIT && retained.emptySlots >= retained.size / 2
        val kept = if (compact) InkSegmentStore.Empty.append(retained.filterNot {
            it.projections.isEmpty() && it.sourceIds.isEmpty()
        }) else retained
        DesktopInkPage(reclaim(base), reclaim(additions), emptySet(), bounds, sourceStrokes,
            operations, emptySet(), kept)
    }

    /** Only the new, last erase is replayed; historical move clamps have already been applied. */
    fun withNormalErase(operation: DecodedInkOperation.Erase, work: InkProjectionWork? = null): DesktopInkPage {
        require(operation.mode == InkEraseMode.Normal)
        val current = if (base.projections.size > ADDITION_LIMIT) editablePartition else this
        val affected = current.segmentsForRows(operation.targetIds)
        val replacements = java.util.IdentityHashMap<DesktopInkSegment, DesktopInkSegment>()
        val priorTargets = ArrayList<InkBounds>()
        val nextTargets = ArrayList<InkBounds>()
        val reclaimed = HashSet<InkProjectionKey>()
        for (segment in affected) {
            val live = segment.projections.filter(current::isLive)
            val targeted = live.filter { it.id in operation.targetIds }
            work?.let { it.targetProjections += targeted.size; it.rebuiltProjections += live.size; it.rebuiltSegments++ }
            priorTargets.addAll(targeted.mapNotNull { it.pageBounds })
            val projections = live.subtract(operation.mask, operation.targetIds)
            nextTargets.addAll(projections.filter { it.id in operation.targetIds }.mapNotNull { it.pageBounds })
            reclaimed.addAll(segment.projections.map { it.projectionKey })
            replacements[segment] = DesktopInkSegment(projections, segment.sourceIds)
        }
        val base = replacements[current.base] ?: current.base
        val tail = replacements[current.additions] ?: current.additions
        val retained = current.retainedSegments.replace(current.retainedSegments.forRows(operation.targetIds)
            .mapNotNull { (index, segment) -> replacements[segment]?.let { index to it } }.toMap())
        val exclusions = if (current.excludedProjections.isEmpty()) emptySet() else
            Collections.unmodifiableSet(HashSet(current.excludedProjections).apply { removeAll(reclaimed) })
        val oldBox = priorTargets.unionBounds()
        val newBox = nextTargets.unionBounds()
        val changesExtent = current.bounds != null && oldBox != null && (
            oldBox.left <= current.bounds.left && (newBox == null || newBox.left > current.bounds.left) ||
            oldBox.top <= current.bounds.top && (newBox == null || newBox.top > current.bounds.top) ||
            oldBox.right >= current.bounds.right && (newBox == null || newBox.right < current.bounds.right) ||
            oldBox.bottom >= current.bounds.bottom && (newBox == null || newBox.bottom < current.bounds.bottom))
        val extent = if (!changesExtent) current.bounds else liveBounds(base, retained, tail, current.excludedRows, exclusions)
        return DesktopInkPage(base, tail, current.excludedRows, extent, sourceStrokes,
            appendInkValues(operations, listOf(operation)), exclusions, retained)
    }

    /** Runs in the erase preparation lane; a new snapshot drops only redundant projection/index data. */
    fun reclaimExcludedProjections(): DesktopInkPage {
        if (excludedProjections.isEmpty() && excludedRows.isEmpty() && retainedSegments.emptySlots == 0) return this
        return reclaimedProjectionSnapshot
    }

    override fun selectAt(page: InkPage, x: Float, y: Float, reach: Float): InkSelection? {
        if (!x.isFinite() || !y.isFinite() || !reach.isFinite() || reach < 0f) return null
        val hit = index.at(InkPoint(x, y), reach).lastOrNull() ?: return null
        val candidates = if (hit.groupId == null) listOf(hit) else grouped(hit.groupId!!)
        val box = candidates.mapNotNull { it.pageBounds }.unionBounds() ?: return null
        val selection = InkLassoSelection(box.selectionPath(), candidates.map { it.id }.toSet(),
            candidates.map { it.projectionKey }.toSet(), box)
        return DesktopInkSelection(page, selection)
    }

    override fun selectInRect(page: InkPage, left: Float, top: Float, right: Float, bottom: Float): InkSelection? {
        if (listOf(left, top, right, bottom).any { !it.isFinite() } || right <= left || bottom <= top) return null
        val selected = index.selectWithLasso(InkBounds(left, top, right, bottom).selectionPath()) ?: return null
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
        val selected = index.selectWithLasso(path.map { InkPoint(it.x, it.y) }) ?: return null
        return DesktopInkSelection(page, selected)
    }

    fun withChanges(added: List<PageStroke>, removed: Set<String>): DesktopInkPage {
        if (added.isEmpty() && removed.isEmpty()) return this
        val addedIds = added.mapTo(HashSet()) { it.id }
        val projectedExclusions = if (addedIds.any { it in excludedRows || it in removed })
            Collections.unmodifiableSet(HashSet(excludedProjections).apply {
                // Sealed/tail removals are physically reclaimed below. Only occurrences still in
                // the original immutable base need a key to distinguish a row that was re-added.
                base.projections.forEach {
                    if (it.id in addedIds && (it.id in excludedRows || it.id in removed)) add(it.projectionKey)
                }
            }) else excludedProjections
        val excluded = if (removed.none { it in base.sourceIds } && addedIds.none { it in excludedRows }) excludedRows else
            Collections.unmodifiableSet(HashSet(excludedRows).apply {
                addAll(removed.filter { it in base.sourceIds })
                removeAll(addedIds)
            })
        val pending = when {
            added.isEmpty() && removed.none { it in additions.sourceIds } -> null
            removed.isEmpty() -> additions.projections + added
            else -> additions.projections.filterNot { it.id in removed } + added
        }
        val sealed = if (pending == null) emptyList() else (0 until pending.size / ADDITION_LIMIT).map { index ->
            DesktopInkSegment(pending.subList(index * ADDITION_LIMIT, (index + 1) * ADDITION_LIMIT))
        }
        val tail = if (pending == null) additions else {
            val remaining = pending.size % ADDITION_LIMIT
            if (remaining == 0) EMPTY_SEGMENT else DesktopInkSegment(pending.subList(pending.size - remaining, pending.size))
        }
        val reclaimedKeys = if (removed.isEmpty()) null else HashSet<InkProjectionKey>()
        if (reclaimedKeys != null) additions.projections.forEach { if (it.id in removed) reclaimedKeys.add(it.projectionKey) }
        val reclaimed = if (removed.isEmpty()) retainedSegments else retainedSegments.replace(retainedSegments.forRows(removed)
            .associate { (index, segment) ->
                val remaining = segment.projections.filterNot { it.id in removed }
                segment.projections.forEach { if (it.id in removed) checkNotNull(reclaimedKeys).add(it.projectionKey) }
                val sourceIds = segment.sourceIds.filterNot { it in removed }
                index to if (remaining.isEmpty() && sourceIds.isEmpty()) EMPTY_SEGMENT else DesktopInkSegment(remaining, sourceIds)
            })
        val exclusions = if (reclaimedKeys == null || reclaimedKeys.none { it in projectedExclusions }) projectedExclusions else
            Collections.unmodifiableSet(HashSet(projectedExclusions).apply { removeAll(reclaimedKeys) })
        val sourceOnly = if (pending == null || additions.unprojectedSourceIds.isEmpty()) emptyList() else
            additions.unprojectedSourceIds.filterNot { it in removed || it in addedIds }.chunked(ADDITION_LIMIT)
                .map { DesktopInkSegment(emptyList(), it) }
        val toSeal = if (sourceOnly.isEmpty()) sealed else if (sealed.isEmpty()) sourceOnly else sourceOnly + sealed
        val retained = reclaimed.append(toSeal)
        val extent = if (removed.isEmpty())
            (listOfNotNull(bounds) + added.mapNotNull { it.pageBounds }).unionBounds()
        else liveBounds(base, retained, tail, excluded, exclusions)
        val sources = appendInkValues(if (removed.isEmpty()) sourceStrokes else sourceStrokes.filterNot { it.id in removed }, added)
        return DesktopInkPage(base, tail, excluded, extent, sources, operations, exclusions, retained)
    }

    fun withObjectErase(operation: DecodedInkOperation.Erase): DesktopInkPage? {
        val touched = index.touching(operation.mask).filter { it.id in operation.targetIds }
        if (touched.eraseObjects(operation.mask, operation.targetIds).isNotEmpty()) return null
        val excluded = Collections.unmodifiableSet(HashSet(excludedProjections).apply { addAll(touched.map { it.projectionKey }) })
        val changesExtent = bounds != null && touched.any { it.pageBounds?.let { box ->
            box.left <= bounds.left || box.top <= bounds.top || box.right >= bounds.right || box.bottom >= bounds.bottom
        } == true }
        val nextBounds = if (!changesExtent) bounds else segments().flatMap { it.projections }
            .filterNot { it.id in excludedRows || it.projectionKey in excluded }.mapNotNull { it.pageBounds }.unionBounds()
        val result = DesktopInkPage(base, additions, excludedRows, nextBounds, sourceStrokes,
            appendInkValues(operations, listOf(operation)), excluded, retainedSegments)
        return if (excluded.size >= TOMBSTONE_LIMIT) result.reclaimExcludedProjections() else result
    }

    companion object {
        const val ADDITION_LIMIT = 128
        const val TOMBSTONE_LIMIT = 256
        private val EMPTY_SEGMENT = DesktopInkSegment(emptyList())
    }
}

internal class InkProjectionWork {
    var targetProjections = 0
    var rebuiltProjections = 0
    var rebuiltSegments = 0
}

private fun liveBounds(base: DesktopInkSegment, retained: InkSegmentStore, tail: DesktopInkSegment,
    rows: Set<String>, projections: Set<InkProjectionKey>): InkBounds? {
    var result: InkBounds? = null
    fun add(segment: DesktopInkSegment) {
        for (stroke in segment.projections) if (stroke.id !in rows && stroke.projectionKey !in projections) {
            val box = stroke.pageBounds ?: continue
            val previous = result
            result = if (previous == null) box else InkBounds(minOf(previous.left, box.left), minOf(previous.top, box.top),
                maxOf(previous.right, box.right), maxOf(previous.bottom, box.bottom))
        }
    }
    add(base); retained.forEach(::add); add(tail)
    return result
}

internal class DesktopInkQueries(private val page: DesktopInkPage) {
    fun touching(mask: Stroke, work: InkQueryWork? = null): List<PageStroke> {
        val box = mask.shape.computeBoundingBox() ?: return emptyList()
        return near(box.xMin, box.yMin, box.xMax, box.yMax, work).flatMap { segment ->
            work?.let { it.nativeIndexQueries++ }
            segment.index.touching(mask).filter(page::isLive)
        }
    }
    fun at(point: InkPoint, reach: Float, work: InkQueryWork? = null): List<PageStroke> =
        near(minOf(point.x - reach, point.x + reach), minOf(point.y - reach, point.y + reach),
            maxOf(point.x - reach, point.x + reach), maxOf(point.y - reach, point.y + reach), work).flatMap { segment ->
            work?.let { it.nativeIndexQueries++ }
            segment.index.at(point, reach).filter(page::isLive)
        }
    fun crossing(from: InkPoint, to: InkPoint, width: Float, work: InkQueryWork? = null): List<PageStroke> {
        val reach = width / 2f
        return near(minOf(from.x, to.x) - reach, minOf(from.y, to.y) - reach,
            maxOf(from.x, to.x) + reach, maxOf(from.y, to.y) + reach, work).flatMap { segment ->
            work?.let { it.nativeIndexQueries++ }
            segment.index.crossing(from, to, width).filter(page::isLive)
        }
    }
    private fun near(left: Float, top: Float, right: Float, bottom: Float, work: InkQueryWork?) =
        page.nearSegments(InkBounds(left - .01f, top - .01f, right + .01f, bottom + .01f), work)

    fun selectWithLasso(path: List<InkPoint>, work: InkQueryWork? = null): InkLassoSelection? {
        if (path.size < 3) return null
        val shape = LassoShape(path)
        val segments = near(path.minOf { it.x } - 4f, path.minOf { it.y } - 4f,
            path.maxOf { it.x } + 4f, path.maxOf { it.y } + 4f, work)
        val hits = segments.flatMap { segment ->
            work?.let { it.nativeIndexQueries++ }
            val selection = segment.index.selectWithLasso(path) ?: return@flatMap emptyList()
            selection.projections.mapNotNull(segment.byProjection::get)
                .filter { page.isLive(it) && shape.contains(it) }.sortedBy { segment.ordinal[it.projectionKey] }
        }
        if (hits.isEmpty()) return null
        val groups = hits.mapNotNullTo(HashSet()) { it.groupId }
        val keys = hits.mapTo(HashSet()) { it.projectionKey }
        val selected = if (groups.isEmpty()) hits else page.segmentsForRowsAndGroups(hits.mapTo(HashSet()) { it.id }, groups)
            .flatMap { segment -> segment.projections.filter { page.isLive(it) && (it.projectionKey in keys || it.groupId in groups) } }
        val bounds = selected.mapNotNull { it.pageBounds }.unionBounds() ?: return null
        return InkLassoSelection(path, selected.mapTo(LinkedHashSet()) { it.id },
            selected.mapTo(LinkedHashSet()) { it.projectionKey }, bounds)
    }
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
    if (edits.any { it is InkEdit.Batch }) return edits.fold(this) { page, edit ->
        page.withDesktopEdits(if (edit is InkEdit.Batch) edit.edits else listOf(edit))
    }
    if (edits.any { it is InkEdit.PartialErase || it is InkEdit.RestoreStrokes || it is InkEdit.Move ||
        it is InkEdit.SetStrokeColors || it is InkEdit.SetStrokeGroups }) {
        var page = this
        val rows = ArrayList<InkEdit>()
        fun flushRows() {
            if (rows.isNotEmpty()) { page = page.withDesktopEdits(rows.toList()); rows.clear() }
        }
        for (edit in edits) when (edit) {
            is InkEdit.PartialErase -> { flushRows(); page = page.withPartialErase(edit) }
            is InkEdit.Move -> { flushRows(); page = page.withMove(edit) }
            is InkEdit.RestoreStrokes -> { flushRows(); page = page.withRestoredStrokes(edit) }
            is InkEdit.SetStrokeColors -> { flushRows(); page = page.withStrokeColors(edit) }
            is InkEdit.SetStrokeGroups -> { flushRows(); page = page.withStrokeGroups(edit) }
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
        is InkEdit.PartialErase, is InkEdit.RestoreStrokes, is InkEdit.Move, is InkEdit.SetStrokeColors, is InkEdit.SetStrokeGroups,
        is InkEdit.Batch -> error("Operation edits must be replayed in order")
    }
    if (added.isEmpty() && removed.isEmpty()) return this
    val geometry = original.withChanges(added.values.map { it.second }, removed)
    val kept = if (removed.isEmpty()) strokes else strokes.filterNot { it.id in removed }
    val visible = appendPortable(kept, added.values.map { it.first })
    return copy(strokes = visible, geometry = geometry)
}

private fun InkPage.withStrokeColors(edit: InkEdit.SetStrokeColors): InkPage {
    val native = desktopGeometry()
    val sources = native.sourceStrokes.map { stroke ->
        edit.colors[stroke.id]?.let { color -> stroke.copy(
            stroke = stroke.stroke.copy(stroke.stroke.brush.copyWithColorIntArgb(color.argb)),
            colorFollowsTheme = color.followsTheme,
        ) } ?: stroke
    }
    return copy(strokes = strokes.map { stroke -> edit.colors[stroke.id]?.let { color ->
        stroke.copy(colorArgb = color.argb, colorFollowsTheme = color.followsTheme)
    } ?: stroke }).replaySources(sources, native.operations)
}

private fun InkPage.withStrokeGroups(edit: InkEdit.SetStrokeGroups): InkPage {
    val native = desktopGeometry()
    val sources = native.sourceStrokes.map { stroke ->
        if (stroke.id in edit.groups) stroke.copy(groupId = edit.groups[stroke.id]) else stroke
    }
    val projections = native.projections.map { stroke ->
        if (stroke.id in edit.groups) stroke.copy(groupId = edit.groups[stroke.id]) else stroke
    }
    return copy(geometry = DesktopInkPage(projections, sources.map { it.id }, sources, native.operations))
}

private fun InkPage.withPartialErase(edit: InkEdit.PartialErase): InkPage {
    require(edit.row.pageId == pageId) { "Erase belongs to another page" }
    val native = desktopGeometry()
    if (edit.active == native.operations.any { it.id == edit.row.id }) return this
    var appended: DecodedInkOperation? = null
    if (edit.active) {
        val row = edit.row
        val stored = StoredInkErase(row.id, pageId, row.mode.name, row.sizeDp, row.points, row.enc,
            row.createdAt, targetIds = edit.targetIds.toList())
        val decoded = ViveInkPage.load(emptyList(), listOf(stored), emptyList()).operations.single()
        if (decoded is DecodedInkOperation.Erase && native.operations.all { it.createdAt < decoded.createdAt }) {
            val projected = if (decoded.mode == InkEraseMode.Object) native.withObjectErase(decoded) else native.withNormalErase(decoded)
            projected?.let {
                return copy(geometry = projected, operations = appendInkValues(this.operations, listOfNotNull(decoded.portableOperation())),
                    latestOperationAt = maxOf(latestOperationAt, edit.row.createdAt))
            }
        }
        appended = decoded
    }
    val operations = native.operations.filterNot { it.id == edit.row.id } + listOfNotNull(appended)
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

private fun appendPortable(base: List<VisibleInkStroke>, tail: List<VisibleInkStroke>): List<VisibleInkStroke> = appendInkValues(base, tail)

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
