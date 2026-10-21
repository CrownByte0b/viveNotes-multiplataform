package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.TableRow
import com.vivenotes.ui.canvas.PictureDemand
import com.vivenotes.ui.canvas.PictureAsset
import com.vivenotes.ui.canvas.rememberPictureAssets
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

internal data class TextMeasurementKey(
    val blocks: List<Block>,
    val width: Float,
    val minHeight: Float,
    val density: Float,
    val fontScale: Float,
    val editing: Boolean = false,
    val thumbnails: VideoThumbnailSource? = null,
)

internal data class TableMeasurementKey(
    val columns: List<Float>, val rows: List<TableRow>, val headerRow: Boolean,
    val headerColumn: Boolean, val inkOnly: Boolean, val density: Float, val fontScale: Float,
)

/** Quantized overscan keeps scroll pixels out of the composition's state dependencies. */
internal fun canvasDemandWindow(viewport: Rect, step: Float = 256f): Rect = Rect(
    (floor(viewport.left / step) - 1f) * step,
    (floor(viewport.top / step) - 1f) * step,
    (ceil(viewport.right / step) + 1f) * step,
    (ceil(viewport.bottom / step) + 1f) * step,
)

/** Unknown text/table heights are measured once before they can be safely omitted. */
internal fun shouldComposeCanvasItem(
    x: Float, y: Float, width: Float, measuredHeight: Float?, window: Rect, pinned: Boolean,
): Boolean = pinned || measuredHeight == null ||
    (x <= window.right && x + width >= window.left &&
        y <= window.bottom && y + measuredHeight >= window.top)

/** Retain lightweight scroll targets while expensive editors and decoders leave composition. */
@Composable
internal fun CanvasItemPlaceholder(tag: String, x: Float, y: Float, width: Float, height: Float) {
    Box(Modifier.offset(x.dp, y.dp).size(width.coerceAtLeast(0f).dp, height.coerceAtLeast(0f).dp).testTag(tag))
}

@Composable
internal fun rememberViewportPictureAssets(
    pageId: String?, outlines: List<Outline>, pictures: PictureLibrary?,
    viewport: () -> Rect, pixelScale: Float,
): Map<String, PictureAsset> {
    val window by remember(viewport) { derivedStateOf { canvasDemandWindow(viewport()) } }
    val demands = remember(outlines, window, pixelScale) {
        outlines.filterIsInstance<Outline.Image>().filter {
            shouldComposeCanvasItem(it.x, it.y, it.width, it.height, window, false)
        }.map {
            fun resolution(dp: Float) = ceil(dp * pixelScale / 128f).toInt().coerceIn(1, 16) * 128
            PictureDemand(it.attachmentId, resolution(it.width), resolution(it.height))
        }
    }
    return rememberPictureAssets(demands, pictures, pageId)
}
