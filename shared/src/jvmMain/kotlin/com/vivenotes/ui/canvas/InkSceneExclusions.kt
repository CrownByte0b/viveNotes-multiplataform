package com.vivenotes.ui.canvas

import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.kit.InkProjectionKey
import java.util.Collections
import java.util.IdentityHashMap

internal fun inkSceneExclusions(rows: Set<String>, byRow: Map<String, List<InkSceneStroke>>,
    projectionKeys: Set<InkProjectionKey>, byProjection: Map<InkProjectionKey, InkSceneStroke>,
    movingKeys: Set<InkProjectionKey>): Set<InkSceneStroke> {
    if (rows.isEmpty() && projectionKeys.isEmpty() && movingKeys.isEmpty() || byRow.isEmpty() && byProjection.isEmpty()) return emptySet()
    val selected = Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>())
    matchingValues(rows, byRow) { selected.addAll(it) }
    matchingValues(projectionKeys, byProjection) { selected.add(it) }
    matchingValues(movingKeys, byProjection) { selected.add(it) }
    return if (selected.isEmpty()) emptySet() else Collections.unmodifiableSet(selected)
}

private inline fun <K, V : Any> matchingValues(keys: Set<K>, values: Map<K, V>, match: (V) -> Unit) {
    if (keys.size < values.size) {
        for (key in keys) values[key]?.let(match)
    } else {
        for ((key, value) in values) if (key in keys) match(value)
    }
}
