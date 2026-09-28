package com.vivenotes.ui.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.findVideoLinks
import com.vivenotes.model.Mark
import com.vivenotes.richtext.findAutoEquationCandidates
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.RichTextBuffer.LinkSpan
import com.vivenotes.ui.icons.DocumentSymbols
import io.ratex.compose.RaTeX
import io.ratex.compose.rememberBlockingRaTeXDisplayList
import io.ratex.measure
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Read-only projection of a text box. Its backing editor keeps the exact source and marks. */
@Composable
internal fun TextBoxPreviews(
    source: AnnotatedString,
    style: TextStyle,
    ink: Color,
    availableWidthDp: Float,
    thumbnails: VideoThumbnailSource?,
    onEdit: () -> Unit,
    onOpenVideo: (String) -> Unit,
    modifier: Modifier = Modifier,
    storedEquations: List<EquationPreview> = emptyList(),
    links: List<LinkSpan> = emptyList(),
    linkColor: Color = Color(0xFF1A5FB4),
    onOpenLink: (String) -> Unit = {},
): Boolean {
    val equations = remember(source.text, storedEquations) {
        findAutoEquationCandidates(source.text).map {
            EquationPreview(it.start, it.end, it.latex)
        } + storedEquations
    }
    val videos = remember(source.text) { findVideoLinks(source.text) }
    val images = remember(thumbnails) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(videos, thumbnails) {
        if (thumbnails == null) return@LaunchedEffect
        val ids = videos.map { it.videoId }.distinct()
        images.keys.filterNot { it in ids }.forEach(images::remove)
        ids.forEach { id ->
            if (id !in images) {
                val bitmap = runCatching { thumbnails.load(id)?.decodeToImageBitmap() }.getOrNull()
                if (bitmap != null) images[id] = bitmap
            }
        }
    }
    val density = LocalDensity.current
    val availableWidthSp = with(density) { availableWidthDp.dp.toPx().toSp().value }
    val entries = mutableListOf<PreviewEntry>()
    equations.forEach { candidate ->
        val parsed = rememberBlockingRaTeXDisplayList(
            latex = candidate.latex,
            displayMode = candidate.latex.startsWith("{\\displaystyle"),
            color = ink,
        ).getOrNull()
        if (parsed != null) {
            val fontSize = 18.sp
            val size = remember(parsed, density) { parsed.measure(with(density) { fontSize.toPx() }) }
            entries += PreviewEntry(candidate.start, candidate.end,
                InlineTextContent(Placeholder(
                    width = minOf(with(density) { size.widthPx.toSp().value }, availableWidthSp).sp,
                    height = with(density) { size.totalHeightPx.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                )) { RaTeX(displayList = parsed, fontSize = fontSize) })
        }
    }
    videos.forEach { link ->
        val image = images[link.videoId] ?: return@forEach
        val cardWidth = with(density) { minOf(320f, availableWidthDp).dp.toPx().toSp().value }
        entries += PreviewEntry(link.start, link.end,
            InlineTextContent(Placeholder(cardWidth.sp, (cardWidth * 9f / 16f).sp,
                PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize().testTag("video-preview-${link.videoId}")) {
                    Image(image, contentDescription = "YouTube thumbnail", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .clickable { onOpenVideo(link.url) }
                        .testTag("video-play-${link.videoId}"), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text("▶", color = Color.White)
                    }
                }
            })
    }
    links.forEach { link ->
        if (link.end <= link.start || link.end > source.length) return@forEach
        // A thumbnail or equation replaces its source text, so its link needs no separate glyph.
        if (entries.any { link.end > it.start && link.end <= it.end }) return@forEach
        entries += PreviewEntry(link.end, link.end,
            InlineTextContent(Placeholder(18.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize().clickable { onOpenLink(link.href) }
                    .testTag("link-icon-${link.start}"), contentAlignment = Alignment.Center) {
                    Icon(DocumentSymbols.Link, contentDescription = "Open link",
                        tint = linkColor, modifier = Modifier.size(14.dp))
                }
            })
    }
    val ordered = mutableListOf<PreviewEntry>()
    entries.sortedBy { it.start }.forEach { entry ->
        if (ordered.lastOrNull()?.end?.let { it > entry.start } != true) ordered += entry
    }
    if (ordered.isEmpty()) return false
    val ranges = ordered.map { it.start until it.end }
    val projected = previewProjection(source, ranges)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val displayLinks = links.map { link ->
        Triple(projectedOffset(link.start, ranges), projectedOffset(link.end, ranges), link.href)
    }
    BasicText(projected,
        inlineContent = ordered.mapIndexed { index, entry -> "preview-$index" to entry.content }.toMap(),
        onTextLayout = { layout = it },
        style = style, modifier = modifier.pointerInput(displayLinks) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!currentEvent.keyboardModifiers.isCtrlPressed) return@awaitEachGesture
                val textLayout = layout ?: return@awaitEachGesture
                val offset = textLayout.getOffsetForPosition(down.position)
                val link = displayLinks.firstOrNull { (start, end, _) ->
                    offset in start until end && textLayout.getBoundingBox(offset).contains(down.position)
                } ?: return@awaitEachGesture
                down.consume()
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                up.consume()
                onOpenLink(link.third)
            }
        }.clickable { onEdit() }.testTag("text-box-preview"))
    return true
}

/** Keeps each placeholder inside one paragraph while retaining the source's paragraph styling. */
internal fun previewProjection(source: AnnotatedString, ranges: List<IntRange>): AnnotatedString {
    // Replacing a source range can collapse a paragraph style to an empty range at a placeholder's
    // start. Compose treats that as an intersecting paragraph and throws during layout. Rebase each
    // style to the projected offsets and omit empty ranges.
    val displaySource = AnnotatedString(source.text.replace('\n', '\u200B'), source.spanStyles)
    return buildAnnotatedString {
        var offset = 0
        ranges.forEachIndexed { index, range ->
            append(displaySource.subSequence(offset, range.first))
            // One editor character for one visual object. In particular, a display equation may
            // contain source newlines, which cannot be an inline-content replacement range.
            appendInlineContent("preview-$index", "\uFFFC")
            offset = range.last + 1
        }
        append(displaySource.subSequence(offset, source.length))
        source.paragraphStyles.forEach { range ->
            val start = projectedOffset(range.start, ranges)
            val end = projectedOffset(range.end, ranges) + ranges.count {
                it.first == range.end && it.isEmpty() && range.end > range.start
            }
            if (end > start) addStyle(range.item, start, end)
        }
    }
}

internal fun projectedOffset(sourceOffset: Int, ranges: List<IntRange>): Int {
    var removed = 0
    ranges.forEach { range ->
        val end = range.last + 1
        if (sourceOffset <= range.first) return sourceOffset - removed
        if (sourceOffset < end) return range.first - removed + 1
        removed += end - range.first - 1
    }
    return sourceOffset - removed
}

private data class PreviewEntry(val start: Int, val end: Int, val content: InlineTextContent)

/** Android's atomic inline equation is one editor character with its source in a mark. */
internal data class EquationPreview(val start: Int, val end: Int, val latex: String)

internal fun RichTextBuffer.storedEquationPreviews(): List<EquationPreview> {
    val result = mutableListOf<EquationPreview>()
    var offset = 0
    blocks.forEachIndexed { blockIndex, block ->
        block.runs.forEach { run ->
            run.marks.filterIsInstance<Mark.Equation>().firstOrNull()?.let { equation ->
                result += EquationPreview(offset, offset + run.editorText.length, equation.latex)
            }
            offset += run.editorText.length
        }
        if (blockIndex < blocks.lastIndex) offset++
    }
    return result
}
