package com.vivenotes.ui.canvas

import com.vivenotes.localization.Strings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.vivenotes.data.PictureLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** A page picture's pixels, or why there are none to show. */
internal sealed interface PictureAsset {
    data class Ready(val bitmap: ImageBitmap, val sourceWidth: Int = bitmap.width,
        val sourceHeight: Int = bitmap.height) : PictureAsset

    /** Android's wording on its broken-picture plate. */
    data class Broken(val reason: String) : PictureAsset
}

@Composable
internal fun rememberPictureAssets(attachmentIds: List<String>, library: PictureLibrary?): Map<String, PictureAsset> {
    val demands = remember(attachmentIds) { attachmentIds.map { PictureDemand(it, 2048, 2048) } }
    return rememberPictureAssets(demands, library, pageKey = Unit)
}

@Composable
internal fun rememberPictureAssets(demands: List<PictureDemand>, library: PictureLibrary?, pageKey: Any?,
    maxBytes: Long = PictureMemoryBudget, decode: PictureDecoder? = null): Map<String, PictureAsset> {
    val scope = rememberCoroutineScope()
    val loader = remember(library, maxBytes, decode) {
        PictureAssetLoader(scope, maxBytes, decode = decode ?: { target, source ->
            withContext(Dispatchers.Default) {
                if (source != null) resizePicture(source, target) else {
                    val bytes = library?.bytes(target.attachmentId)
                    currentCoroutineContext().ensureActive()
                    if (bytes == null) PictureAsset.Broken(Strings.error_value_not_found(target.attachmentId.pictureFileName()))
                    else {
                        val bitmap = bytes.decodeToImageBitmap()
                        currentCoroutineContext().ensureActive()
                        resizePicture(PictureAsset.Ready(bitmap), target)
                    }
                }
            }
        })
    }
    DisposableEffect(loader) { onDispose { loader.close() } }
    LaunchedEffect(loader, demands, pageKey) { loader.update(if (library == null && decode == null) emptyList() else demands, pageKey) }
    return loader.assets
}

internal fun resizePicture(source: PictureAsset.Ready, target: PictureDemand): PictureAsset.Ready {
    val bitmap = source.bitmap
    val width = minOf(bitmap.width, target.widthPx)
    val height = minOf(bitmap.height, target.heightPx)
    if (width == bitmap.width && height == bitmap.height && bitmap.config == ImageBitmapConfig.Argb8888) return source
    val resized = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
    Canvas(resized).drawImageRect(bitmap, dstSize = IntSize(width, height),
        paint = Paint().apply { filterQuality = FilterQuality.Medium })
    return PictureAsset.Ready(resized, source.sourceWidth, source.sourceHeight)
}

/**
 * A picture drawn at its outline's size; the frame keeps that size even when the file is missing.
 * [background] (`Outline.Image.backgroundArgb`) fills the frame behind a picture that can be drawn,
 * on the box the bitmap fills, so no sliver of colour shows past an opaque picture's edge.
 */
@Composable
internal fun PictureContent(asset: PictureAsset?, background: Int? = null, modifier: Modifier = Modifier) {
    when (asset) {
        is PictureAsset.Ready -> Image(asset.bitmap, contentDescription = Strings.picture,
            contentScale = ContentScale.FillBounds, modifier = modifier.fillMaxSize()
                .then(background?.let { Modifier.background(Color(it)) } ?: Modifier))
        // Android's broken-picture plate: the frame keeps its fill and only the message is red.
        is PictureAsset.Broken -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp), contentAlignment = Alignment.Center) {
            Text(asset.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        null -> Unit
    }
}
