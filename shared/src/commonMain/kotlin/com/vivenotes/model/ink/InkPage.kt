package com.vivenotes.model.ink

/** Decoded page-space inputs for displaying a stored Android ink row. The stored bytes stay untouched. */
data class InkSample(val x: Float, val y: Float, val pressure: Float? = null)

data class VisibleInkStroke(
    val id: String,
    val brushFamily: String,
    val brushVersion: Int,
    val sizeDp: Float,
    val colorArgb: Int,
    val colorFollowsTheme: Boolean?,
    val samples: List<InkSample>,
    val stabilization: Int = 0,
    val epsilon: Float = 0.25f,
)

sealed interface InkPageOperation {
    val id: String
    val createdAt: Long
    val targetIds: Set<String>

    data class Erase(
        override val id: String,
        override val createdAt: Long,
        override val targetIds: Set<String>,
        val objectMode: Boolean,
        val sizeDp: Float,
        val samples: List<InkSample>,
    ) : InkPageOperation

    data class Move(
        override val id: String,
        override val createdAt: Long,
        override val targetIds: Set<String>,
        val dxDp: Float,
        val dyDp: Float,
        val scaleX: Float,
        val scaleY: Float,
        val anchorX: Float,
        val anchorY: Float,
        val path: List<InkSample> = emptyList(),
    ) : InkPageOperation
}

/** Platform-owned modeled geometry. The common canvas only needs the painted extent. */
interface InkPageGeometry {
    val rightDp: Float
    val bottomDp: Float
}

/** Optional platform snapshot carried with a new stored stroke. */
interface InkStrokeGeometry

data class InkPage(
    val pageId: String,
    val strokes: List<VisibleInkStroke>,
    val operations: List<InkPageOperation> = emptyList(),
    val geometry: InkPageGeometry? = null,
)
