package com.vivenotes.ui.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.data.PictureLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** A page picture's pixels, or why there are none to show. */
internal sealed interface PictureAsset {
    data class Ready(val bitmap: ImageBitmap) : PictureAsset

    /** Android's wording on its broken-picture plate. */
    data class Broken(val reason: String) : PictureAsset
}

/**
 * Android `ImageLayer.rememberPageAssets`: one decode per attachment shown on the page, dropped when
 * no picture on it uses the attachment any more. Stored pictures are already at most 2048 px, so a
 * whole-file decode is the budget Android's import set.
 */
@Composable
internal fun rememberPictureAssets(attachmentIds: List<String>, library: PictureLibrary?): Map<String, PictureAsset> {
    val assets = remember(library) { mutableStateMapOf<String, PictureAsset>() }
    LaunchedEffect(attachmentIds, library) {
        if (library == null) return@LaunchedEffect
        attachmentIds.forEach { id ->
            if (assets[id] is PictureAsset.Ready) return@forEach
            val bytes = library.bytes(id)
            assets[id] = when (bytes) {
                null -> PictureAsset.Broken("Error: ${id.asFileName()} not found")
                else -> withContext(Dispatchers.Default) { runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
                    ?.let(PictureAsset::Ready)
                    ?: PictureAsset.Broken("Error: ${id.asFileName()} could not be read")
            }
        }
        assets.keys.filterNot { it in attachmentIds }.forEach(assets::remove)
    }
    return assets
}

/** A picture drawn at its outline's size; the frame keeps that size even when the file is missing. */
@Composable
internal fun PictureContent(asset: PictureAsset?, modifier: Modifier = Modifier) {
    when (asset) {
        is PictureAsset.Ready -> Image(asset.bitmap, contentDescription = "Picture",
            contentScale = ContentScale.FillBounds, modifier = modifier.fillMaxSize())
        is PictureAsset.Broken -> Box(modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(asset.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        null -> Unit
    }
}

/** The first twelve characters of a content hash are enough to find the file by hand. */
private fun String.asFileName(): String = if (length <= 12) this else take(12) + "…"
