package com.vivenotes.ui.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

internal const val TextPreparationBatchSize = 64

internal data class MeasuredTextHeight(
    val key: TextMeasurementKey,
    val height: Float,
    // Reading Compose's resolved-font state also invalidates composition when an async font arrives.
    val fontsCurrent: () -> Boolean = { true },
) {
    fun matches(candidate: TextMeasurementKey): Boolean = key == candidate && fontsCurrent()
}

/** Window-owned reuse of exact placements. Active-page state has a separate lifetime. */
internal class TextMeasurementCache(
    private val maxEntries: Int = 2_048,
    private val maxWeight: Long = 2_000_000,
    private val maxPages: Int = 4,
) {
    private data class Address(val page: String, val box: String)
    private data class Entry(val measurement: MeasuredTextHeight, val weight: Long)
    private val entries = LinkedHashMap<Address, Entry>()
    var weight: Long = 0
        private set
    val size: Int get() = entries.size

    init { require(maxEntries >= 0 && maxWeight >= 0 && maxPages >= 0) }

    fun get(page: String, box: String, key: TextMeasurementKey): MeasuredTextHeight? {
        val address = Address(page, box)
        val entry = entries[address] ?: return null
        if (!entry.measurement.matches(key)) return null
        // The cache is touched on adoption/publication, not on every composition lookup.
        return entry.measurement
    }

    fun put(page: String, box: String, measurement: MeasuredTextHeight) {
        remove(page, box)
        val cost = measurement.key.blocks.sumOf { block ->
            64L + block.runs.sumOf { 32L + it.plainText.length }
        }.coerceAtLeast(1)
        if (maxEntries == 0 || maxPages == 0 || cost > maxWeight ||
            measurement.key.editing || !measurement.height.isFinite() || measurement.height < 0f ||
            !measurement.fontsCurrent()) return
        entries[Address(page, box)] = Entry(measurement, cost)
        weight += cost
        while (entries.size > maxEntries || weight > maxWeight) removeOldest()
        while (entries.keys.map { it.page }.distinct().size > maxPages) {
            val oldestPage = entries.keys.first().page
            entries.keys.filter { it.page == oldestPage }.forEach(::remove)
        }
    }

    fun remove(page: String, box: String) = remove(Address(page, box))

    fun retainPages(pages: Set<String>) {
        entries.keys.filterNot { it.page in pages }.forEach(::remove)
    }

    fun retainBoxes(page: String, boxes: Set<String>) {
        entries.keys.filter { it.page == page && it.box !in boxes }.forEach(::remove)
    }

    private fun removeOldest() = remove(entries.keys.first())
    private fun remove(address: Address) { entries.remove(address)?.let { weight -= it.weight } }
}

/** Unknown boxes can only wait when their known top/width proves they miss the viewport. */
internal fun textMayIntersectWindow(x: Float, y: Float, width: Float, window: Rect): Boolean =
    x <= window.right && x + width >= window.left && y <= window.bottom

internal data class TextPreparationPlan(val retained: Set<String>, val next: Set<String>)

internal fun planTextPreparation(
    unknownOffscreen: List<String>,
    admitted: Set<String>,
    limit: Int = TextPreparationBatchSize,
): TextPreparationPlan {
    require(limit > 0)
    val waiting = unknownOffscreen.toHashSet()
    val retained = admitted.filterTo(linkedSetOf()) { it in waiting }
    val next = unknownOffscreen.asSequence().filterNot { it in retained }
        .take((limit - retained.size).coerceAtLeast(0)).toCollection(linkedSetOf())
    return TextPreparationPlan(retained, next)
}

internal data class TextPreparationSnapshot(
    val pageId: String,
    val total: Int,
    val ready: Int,
    val composed: Int,
    val background: Int,
    val editorStarts: Int,
    val textLayouts: Int,
    val placements: Int,
    val reused: Int,
)

/** Optional test/profiling observer; no document contents or per-frame logging. */
internal val LocalTextPreparationObserver = staticCompositionLocalOf<((TextPreparationSnapshot) -> Unit)?> { null }
