package com.vivenotes.data

import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.appendInkValues
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.prepareCanvasScenes
import com.vivenotes.model.ink.InkPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class InkCacheOwner(val accountId: String?, val notebookId: String)

/** Fresh rows from one SQLite read transaction, owned exclusively by the loader. */
internal class StoredInkSnapshot(
    val pageId: String,
    val owner: InkCacheOwner?,
    val strokes: List<InkStrokeEntity>,
    val erases: List<InkEraseWithTargets>,
    val moves: List<InkMoveWithTargets>,
    val operationClock: Long,
) {
    fun sameOperations(other: StoredInkSnapshot): Boolean =
        erases.size == other.erases.size && moves.size == other.moves.size &&
            erases.indices.all { i ->
                val a = erases[i]; val b = other.erases[i]
                val x = a.erase; val y = b.erase
                x.id == y.id && x.pageId == y.pageId && x.mode == y.mode && x.sizeDp == y.sizeDp &&
                    x.enc == y.enc && x.createdAt == y.createdAt && x.deletedAt == y.deletedAt &&
                    x.points.contentEquals(y.points) && a.targets.toSet() == b.targets.toSet()
            } && moves.indices.all { i ->
                val a = moves[i]; val b = other.moves[i]
                val x = a.move; val y = b.move
                x.id == y.id && x.pageId == y.pageId && x.dxDp == y.dxDp && x.dyDp == y.dyDp &&
                    x.scaleX == y.scaleX && x.scaleY == y.scaleY && x.anchorX == y.anchorX && x.anchorY == y.anchorY &&
                    x.enc == y.enc && x.createdAt == y.createdAt && x.deletedAt == y.deletedAt &&
                    x.points.contentEquals(y.points) && a.targets.toSet() == b.targets.toSet()
            }

    fun hasPrefix(previous: StoredInkSnapshot): Boolean = strokes.size >= previous.strokes.size &&
        previous.strokes.indices.all { sameStroke(strokes[it], previous.strokes[it]) }

    fun canAppend(previous: StoredInkSnapshot): Boolean {
        val added = strokes.subList(previous.strokes.size, strokes.size).mapTo(HashSet()) { it.id }
        // An old operation can name a row that has only just arrived. A move's shared clamp must
        // then be replayed against ALL its targets, including the newly available stroke.
        return erases.none { it.targets.any { target -> target.strokeId in added } } &&
            moves.none { it.targets.any { target -> target.strokeId in added } }
    }

    val sourceWeight: Long get() = strokes.sumOf { it.points.size.toLong() + 128 +
        2L * (it.id.length.toLong() + it.pageId.length + it.brushFamily.length + it.enc.length + (it.groupId?.length ?: 0)) } +
        erases.sumOf { it.erase.points.size.toLong() + 96 +
            2L * (it.erase.id.length.toLong() + it.erase.pageId.length + it.erase.enc.length) +
            it.targets.sumOf { target -> 2L * (target.strokeId.length.toLong() + target.eraseId.length) + 32 } } +
        moves.sumOf { it.move.points.size.toLong() + 128 +
            2L * (it.move.id.length.toLong() + it.move.pageId.length + it.move.enc.length) +
            it.targets.sumOf { target -> 2L * (target.strokeId.length.toLong() + target.moveId.length) + 32 } }
}

/** Entity equality intentionally omits metadata; it is never a cache-validity predicate. */
private fun sameStroke(a: InkStrokeEntity, b: InkStrokeEntity): Boolean =
    a.id == b.id && a.pageId == b.pageId && a.seq == b.seq && a.brushFamily == b.brushFamily &&
        a.brushVersion == b.brushVersion && a.sizeDp == b.sizeDp && a.colorArgb == b.colorArgb &&
        a.colorFollowsTheme == b.colorFollowsTheme && a.epsilon == b.epsilon && a.stabilization == b.stabilization &&
        a.minX == b.minX && a.minY == b.minY && a.maxX == b.maxX && a.maxY == b.maxY &&
        a.enc == b.enc && a.createdAt == b.createdAt && a.groupId == b.groupId && a.deletedAt == b.deletedAt &&
        a.points.contentEquals(b.points)

/** Bounded authoritative snapshots, never a replacement for reading storage on a page visit. */
internal class LoadedInkCache(
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val maxPages: Int = 2,
    private val maxRows: Int = 80_000,
    private val maxSourceWeight: Long = 32L * 1024 * 1024,
    private val maxSamples: Long = 1_000_000,
    private val maxProjections: Int = 120_000,
) : AutoCloseable {
    private class Entry(val stored: StoredInkSnapshot, val page: InkPage) {
        val rows = stored.strokes.size.toLong() + stored.erases.size + stored.moves.size
        val bytes = stored.sourceWeight
        val samples = page.desktopGeometry().sourceStrokes.sumOf { it.stroke.inputs.size.toLong() } +
            page.operations.sumOf { when (it) {
                is com.vivenotes.model.ink.InkPageOperation.Erase -> it.samples.size.toLong()
                is com.vivenotes.model.ink.InkPageOperation.Move -> it.path.size.toLong()
            } }
        val projections = page.desktopGeometry().segments().sumOf { it.projections.size.toLong() }
    }
    private val lane = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private var owner: InkCacheOwner? = null
    private var closed = false
    internal var fullLoads = 0
        private set
    internal var hits = 0
        private set
    internal var prefixLoads = 0
        private set
    internal val retainedPages: Int get() = synchronized(entries) { entries.size }

    // Acquire the lane before reading, so a late older query cannot displace a newer snapshot.
    suspend fun load(read: suspend () -> StoredInkSnapshot): InkPage = lane.withLock {
        synchronized(entries) { check(!closed) }
        val stored = read()
        withContext(cpu) {
            currentCoroutineContext().ensureActive()
            val previous = synchronized(entries) {
                check(!closed)
                if (owner != stored.owner || stored.owner == null) entries.clear()
                owner = stored.owner
                entries.remove(stored.pageId)
            }
            val compatible = previous != null && stored.sameOperations(previous.stored) && stored.hasPrefix(previous.stored)
            val loaded = when {
                compatible && stored.strokes.size == previous.stored.strokes.size -> {
                    hits++
                    previous.page
                }
                compatible && stored.canAppend(previous.stored) -> {
                    prefixLoads++
                    val added = InkPageReader.read(stored.pageId,
                        stored.strokes.subList(previous.stored.strokes.size, stored.strokes.size), emptyList(), emptyList())
                    previous.page.copy(strokes = appendInkValues(previous.page.strokes, added.strokes),
                        geometry = previous.page.desktopGeometry().withChanges(added.desktopGeometry().sourceStrokes, emptySet()))
                }
                else -> {
                    fullLoads++
                    InkPageReader.read(stored.pageId, stored.strokes, stored.erases, stored.moves)
                }
            }
            val page = if (loaded.latestOperationAt == stored.operationClock) loaded else loaded.copy(latestOperationAt = stored.operationClock)
            page.desktopGeometry().prepareCanvasScenes()
            currentCoroutineContext().ensureActive()
            val entry = if (previous?.page === page) previous else Entry(stored, page)
            synchronized(entries) {
                check(!closed)
                if (stored.owner != null && maxPages > 0 && entry.rows <= maxRows && entry.bytes <= maxSourceWeight &&
                    entry.samples <= maxSamples && entry.projections <= maxProjections) {
                    entries[stored.pageId] = entry
                    while (entries.size > maxPages || entries.values.sumOf { it.rows } > maxRows ||
                        entries.values.sumOf { it.bytes } > maxSourceWeight || entries.values.sumOf { it.samples } > maxSamples ||
                        entries.values.sumOf { it.projections } > maxProjections) entries.remove(entries.keys.first())
                }
            }
            DebugLog.d("ink") { "load complete rows=${stored.strokes.size} fullLoads=$fullLoads prefixLoads=$prefixLoads hits=$hits retainedPages=$retainedPages" }
            page
        }
    }

    override fun close() = synchronized(entries) {
        closed = true
        entries.clear()
        owner = null
    }
}
