package com.vivenotes.data

import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageRevisionEntity
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Exact logical old state: timestamps do not occur in either revision payload. */
internal data class CheckpointSource(val content: PageContentEntity, val ink: InkSnapshot) {
    fun sameState(other: CheckpointSource): Boolean =
        content.pageId == other.content.pageId && content.format == other.content.format &&
            content.docJson == other.content.docJson && ink == other.ink
}

internal data class PreparedCheckpoint(
    val source: CheckpointSource,
    val row: PageRevisionEntity,
    val candidates: List<PageRevisionEntity>,
    val healthyIds: List<String>,
)

/** Confined to one mutation/replay chain; nested repository calls share its prepared work. */
internal class CheckpointPreparation : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CheckpointPreparation>
    private val pages = HashMap<String, MutableList<PreparedCheckpoint>>()

    fun find(source: CheckpointSource): PreparedCheckpoint? =
        pages[source.content.pageId]?.firstOrNull { it.source.sameState(source) }

    fun remember(prepared: PreparedCheckpoint) {
        val rows = pages.getOrPut(prepared.source.content.pageId) { mutableListOf() }
        rows.removeAll { it.source.sameState(prepared.source) }
        rows.add(0, prepared)
    }
}

internal sealed class CheckpointRetry : RuntimeException() {
    class Capture(val source: CheckpointSource, val createdAt: Long) : CheckpointRetry()
    class Verify(val prepared: PreparedCheckpoint, val candidates: List<PageRevisionEntity>) : CheckpointRetry()
}

