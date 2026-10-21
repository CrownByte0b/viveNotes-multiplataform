package com.vivenotes.ui.canvas

import androidx.compose.runtime.mutableStateMapOf
import com.vivenotes.localization.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

internal typealias PictureDecoder = suspend (PictureDemand, PictureAsset.Ready?) -> PictureAsset

/** Owned by the composition's main scope; native work is delegated to the decoder. */
internal class PictureAssetLoader(
    private val scope: CoroutineScope,
    maxBytes: Long = PictureMemoryBudget,
    private val nowMillis: () -> Long = { pictureEpoch.elapsedNow().inWholeMilliseconds },
    private val decode: PictureDecoder,
) {
    private data class Cached(val asset: PictureAsset, val attempted: Long) {
        val bytes: Long get() = (asset as? PictureAsset.Ready)?.bitmap?.let { it.width.toLong() * it.height * 4 } ?: 0
    }
    private data class Active(val target: PictureDemand, val generation: Long, val job: Job, var waiting: Boolean = false)
    val assets = mutableStateMapOf<String, PictureAsset>()
    private val cache = PictureMemoryCache<Cached>(maxBytes) { it.bytes }
    private val changed = MutableStateFlow(0L)
    private var desired = emptyMap<String, PictureDemand>()
    private val pending = ArrayDeque<PictureDemand>()
    private val active = mutableMapOf<String, Active>()
    private var page: Any? = null
    private var generation = 0L
    private var closed = false
    internal val retainedBytes: Long get() = cache.bytes

    fun update(demands: List<PictureDemand>, pageKey: Any?) {
        if (closed) return
        desired = boundedPictureDemands(demands, cache.maxBytes).associateBy { it.attachmentId }
        pending.clear()
        if (page != pageKey) {
            page = pageKey
            generation++
            active.values.toList().forEach { it.job.cancel() }
            assets.clear()
        }
        active.values.filter { it.generation != generation || desired[it.target.attachmentId] != it.target }
            .forEach { it.job.cancel() }
        assets.keys.filterNot { it in desired }.forEach(assets::remove)
        val downsizing = mutableListOf<PictureDemand>()
        val loading = mutableListOf<PictureDemand>()
        for (target in desired.values) {
            val cached = cache.get(target.attachmentId)
            if (cached != null) assets[target.attachmentId] = cached.asset
            if (cached != null && satisfies(cached, target)) continue
            val running = active[target.attachmentId]
            if (running != null && !running.job.isCancelled && running.generation == generation && running.target == target) continue
            if (cached != null && cached.bytes > target.bytes) downsizing += target else loading += target
        }
        pending.addAll(downsizing)
        pending.addAll(loading)
        cache.trimCount(256, desired.keys)
        changed.value++
        pump()
    }

    private fun satisfies(cached: Cached, target: PictureDemand): Boolean = when (val asset = cached.asset) {
        is PictureAsset.Broken -> nowMillis() - cached.attempted < 60_000
        is PictureAsset.Ready -> cached.bytes <= target.bytes && hasResolution(asset, target)
    }

    private fun hasResolution(asset: PictureAsset.Ready, target: PictureDemand): Boolean =
        asset.bitmap.width >= minOf(asset.sourceWidth, target.widthPx) &&
            asset.bitmap.height >= minOf(asset.sourceHeight, target.heightPx)

    private fun pump() {
        if (closed) return
        var remaining = pending.size
        while (active.values.count { !it.waiting } < 2 && pending.isNotEmpty() && remaining-- > 0) {
            val target = pending.removeFirst()
            if (desired[target.attachmentId] != target) continue
            if (target.attachmentId in active) { pending.addLast(target); continue }
            if (active.values.any { it.waiting } && (cache.get(target.attachmentId)?.bytes ?: 0) <= target.bytes) {
                pending.addLast(target)
                continue
            }
            val startedGeneration = generation
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    yield()
                    val cached = cache.get(target.attachmentId)
                    val source = (cached?.asset as? PictureAsset.Ready)?.takeIf { hasResolution(it, target) }
                    val asset = try { decode(target, source) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { PictureAsset.Broken(Strings.error_value_could_not_be_read(target.attachmentId.pictureFileName())) }
                    currentCoroutineContext().ensureActive()
                    val next = Cached(asset, nowMillis())
                    while (generation == startedGeneration && desired[target.attachmentId] == target && !closed) {
                        val version = changed.value
                        val before = cache.bytes
                        if (cache.put(target.attachmentId, next, desired.keys)) {
                            assets[target.attachmentId] = asset
                            cache.trimCount(256, desired.keys)
                            changed.value++
                            break
                        }
                        if (cache.bytes != before) changed.value++
                        active[target.attachmentId]?.waiting = true
                        pump()
                        changed.first { it != version }
                    }
                } finally {
                    active.remove(target.attachmentId)
                    pump()
                }
            }
            active[target.attachmentId] = Active(target, startedGeneration, job)
            job.start()
        }
    }

    fun close() {
        closed = true
        generation++
        pending.clear()
        active.values.toList().forEach { it.job.cancel() }
        cache.clear()
        assets.clear()
        changed.value++
    }
}

private val pictureEpoch = TimeSource.Monotonic.markNow()
internal fun String.pictureFileName(): String = if (length <= 12) this else take(12) + "…"
