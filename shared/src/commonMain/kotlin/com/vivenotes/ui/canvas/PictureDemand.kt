package com.vivenotes.ui.canvas

import kotlin.math.floor

internal const val PictureMemoryBudget: Long = 64L * 1024 * 1024

internal data class PictureDemand(val attachmentId: String, val widthPx: Int, val heightPx: Int) {
    init { require(widthPx > 0 && heightPx > 0) }
    val bytes: Long get() = widthPx.toLong() * heightPx * 4
}

/** Every visible attachment gets pixels, including when the combined requested resolutions are large. */
internal fun boundedPictureDemands(demands: List<PictureDemand>, maxBytes: Long = PictureMemoryBudget): List<PictureDemand> {
    require(maxBytes >= 4)
    val distinct = LinkedHashMap<String, PictureDemand>()
    for (demand in demands) {
        val previous = distinct[demand.attachmentId]
        distinct[demand.attachmentId] = PictureDemand(demand.attachmentId,
            maxOf(previous?.widthPx ?: 1, demand.widthPx.coerceAtMost(2048)),
            maxOf(previous?.heightPx ?: 1, demand.heightPx.coerceAtMost(2048)))
    }
    val targets = distinct.values.toList()
    require(targets.size.toLong() * 4 <= maxBytes)
    if (targets.sumOf { it.bytes } <= maxBytes) return targets
    fun dimension(pixels: Int, scale: Double) = floor(pixels * scale).toInt().coerceAtLeast(1)
    var low = 0.0
    var high = 1.0
    repeat(40) {
        val middle = (low + high) / 2
        val bytes = targets.sumOf { dimension(it.widthPx, middle).toLong() * dimension(it.heightPx, middle) * 4 }
        if (bytes <= maxBytes) low = middle else high = middle
    }
    return targets.map { PictureDemand(it.attachmentId, dimension(it.widthPx, low), dimension(it.heightPx, low)) }
}

/** LRU admission never evicts a visible picture merely because another visible picture finishes. */
internal class PictureMemoryCache<T>(val maxBytes: Long, private val weigh: (T) -> Long) {
    private val values = LinkedHashMap<String, T>()
    var bytes: Long = 0
        private set
    val size: Int get() = values.size

    fun get(id: String): T? = values.remove(id)?.also { values[id] = it }

    fun remove(id: String): T? = values.remove(id)?.also { bytes -= weigh(it) }

    fun put(id: String, value: T, pinned: Set<String>): Boolean {
        val weight = weigh(value)
        require(weight >= 0)
        if (weight > maxBytes) return false
        val oldWeight = values[id]?.let(weigh) ?: 0
        while (bytes - oldWeight + weight > maxBytes) {
            val evict = values.keys.firstOrNull { it != id && it !in pinned } ?: return false
            remove(evict)
        }
        remove(id)
        values[id] = value
        bytes += weight
        return true
    }

    fun trimCount(maxEntries: Int, pinned: Set<String>) {
        while (values.size > maxEntries) {
            val evict = values.keys.firstOrNull { it !in pinned } ?: break
            remove(evict)
        }
    }

    fun clear() { values.clear(); bytes = 0 }
}
