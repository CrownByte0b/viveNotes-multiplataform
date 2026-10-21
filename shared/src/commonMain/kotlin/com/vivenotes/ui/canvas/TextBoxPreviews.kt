package com.vivenotes.ui.canvas

import com.vivenotes.localization.Strings
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.Block
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Read-only projection of a text box. Its backing editor keeps the exact source and marks.
 *
 * The preview stands in for the editor, which it hides, so it draws [blocks]' list markers and
 * quote stripes itself and ticks a to-do whose box is clicked. [onEdit] gets the source offset
 * clicked, where editing starts. [onPreviewLayout] gets each layout it draws, for what reads a
 * point on the box — a right-click — while the editor underneath is laid out differently.
 */
@Composable
internal fun TextBoxPreviews(
    source: AnnotatedString,
    style: TextStyle,
    ink: Color,
    availableWidthDp: Float,
    thumbnails: VideoThumbnailSource?,
    onEdit: (Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    modifier: Modifier = Modifier,
    storedEquations: List<EquationPreview> = emptyList(),
    links: List<LinkSpan> = emptyList(),
    linkColor: Color = Color(0xFF1A5FB4),
    onOpenLink: (String) -> Unit = {},
    blocks: List<Block> = emptyList(),
    colors: RichTextColors = RichTextColors(Color(0xFF4CAF50), linkColor, ink.copy(alpha = 0.1f)),
    onToggleTodo: (String) -> Unit = {},
    onPreviewLayout: (PreviewLayout) -> Unit = {},
    onPreparationChanged: (Boolean) -> Unit = {},
): Boolean {
    val equations = remember(source.text, storedEquations) {
        findAutoEquationCandidates(source.text).map {
            EquationPreview(it.start, it.end, it.latex)
        } + storedEquations
    }
    val videos = remember(source.text) { findVideoLinks(source.text) }
    val images = remember(thumbnails) { mutableStateMapOf<String, ImageBitmap>() }
    var prepared by remember(videos, thumbnails) { mutableStateOf(thumbnails == null || videos.isEmpty()) }
    LaunchedEffect(videos, thumbnails) {
        if (thumbnails == null) return@LaunchedEffect
        val ids = videos.map { it.videoId }.distinct()
        images.keys.filterNot { it in ids }.forEach(images::remove)
        ids.forEach { id ->
            if (id !in images) {
                val bitmap = loadVideoThumbnail(thumbnails, id)
                coroutineContext.ensureActive()
                if (bitmap != null) images[id] = bitmap
            }
        }
        prepared = true
    }
    val preparationFinished = prepared
    SideEffect { onPreparationChanged(preparationFinished) }
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
                    Image(image, contentDescription = Strings.youtube_thumbnail, contentScale = ContentScale.Crop,
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
    val replacementCoverage = PreviewReplacementCoverage(entries.map { it.start until it.end })
    links.forEach { link ->
        if (link.end <= link.start || link.end > source.length) return@forEach
        // A thumbnail or equation replaces its source text, so its link needs no separate glyph.
        if (replacementCoverage.coversLinkEnd(link.end)) return@forEach
        entries += PreviewEntry(link.end, link.end,
            InlineTextContent(Placeholder(18.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize().clickable { onOpenLink(link.href) }
                    .testTag("link-icon-${link.start}"), contentAlignment = Alignment.Center) {
                    Icon(DocumentSymbols.Link, contentDescription = Strings.open_link,
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
    val offsets = remember(ranges) { PreviewOffsetIndex(ranges) }
    val projected = remember(source, offsets) { previewProjection(source, ranges, offsets) }
    var layout by remember { mutableStateOf<PreviewLayout?>(null) }
    var pressedAt by remember { mutableStateOf<Offset?>(null) }
    val measurer = rememberTextMeasurer()
    val currentLinks by rememberUpdatedState(links)
    val currentBlocks by rememberUpdatedState(blocks)
    val currentToggleTodo by rememberUpdatedState(onToggleTodo)
    BasicText(projected,
        inlineContent = ordered.mapIndexed { index, entry -> "preview-$index" to entry.content }.toMap(),
        onTextLayout = { PreviewLayout(it, ranges, offsets).let { shown -> layout = shown; onPreviewLayout(shown) } },
        style = style, modifier = modifier
            .drawBehind {
                layout?.let { drawBlockDecorations(it.layout, blocks, colors, ink, measurer, offsets::toDisplay) }
            }
            .pointerInput(ranges) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressedAt = down.position
                    val shown = layout ?: return@awaitEachGesture
                    val todo = todoAt(shown.layout, currentBlocks, down.position, this, offsets::toDisplay)
                    val link = if (todo != null || !currentEvent.keyboardModifiers.isCtrlPressed) null
                        else shown.linkAt(down.position, currentLinks)
                    if (todo == null && link == null) return@awaitEachGesture
                    down.consume()
                    val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                    up.consume()
                    todo?.let { currentToggleTodo(it) }
                    link?.let { onOpenLink(it.href) }
                }
            }
            .clickable {
                val shown = layout
                val at = pressedAt
                // Editing starts where the box was clicked.
                onEdit(if (shown == null || at == null) 0 else shown.caretAt(at))
            }.testTag("text-box-preview"))
    return true
}

internal suspend fun loadVideoThumbnail(
    source: VideoThumbnailSource,
    id: String,
    decode: (ByteArray) -> ImageBitmap = { it.decodeToImageBitmap() },
): ImageBitmap? = try {
    withContext(Dispatchers.Default) { source.load(id)?.let(decode) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) { null }

/**
 * A preview as drawn — its [layout], and the source [ranges] its objects replace — for finding
 * what a point on it stands for in the source text.
 */
internal class PreviewLayout(val layout: TextLayoutResult, private val ranges: List<IntRange>,
    private val offsets: PreviewOffsetIndex = PreviewOffsetIndex(ranges)) {
    // Separators are zero-width spaces here, as in the editor; [caretAt] reads them as newlines.
    private val text = layout.layoutInput.text.text.replace('\u200B', '\n')

    /** The source offset a click at [point] edits at. */
    fun caretAt(point: Offset): Int = offsets.toSource(caretAt(layout, text, point))

    /** The link of [links] drawn under [point]: on its text, or on the link icon drawn after it. */
    fun linkAt(point: Offset, links: List<LinkSpan>): LinkSpan? {
        val character = characterAt(layout, point) ?: return null
        return links.firstOrNull { link ->
            val end = offsets.toDisplay(link.end)
            character in offsets.toDisplay(link.start) until end ||
                (character == end && offsets.insertionsAt(link.end) > 0)
        }
    }
}

/** Keeps each placeholder inside one paragraph while retaining the source's paragraph styling. */
internal fun previewProjection(source: AnnotatedString, ranges: List<IntRange>,
    offsets: PreviewOffsetIndex = PreviewOffsetIndex(ranges)): AnnotatedString {
    // Replacing a source range can collapse a paragraph style to an empty range at a placeholder's
    // start. Compose treats that as an intersecting paragraph and throws during layout. Rebase each
    // style to the projected offsets and omit empty ranges.
    val displayText = source.text.replace('\n', '\u200B')
    val starts = IntArray(ranges.size + 1)
    val ends = IntArray(ranges.size + 1)
    ranges.forEachIndexed { index, range ->
        ends[index] = range.first
        starts[index + 1] = range.last + 1
    }
    ends[ends.lastIndex] = source.length
    val styles = arrayOfNulls<MutableList<AnnotatedString.Range<SpanStyle>>>(starts.size)
    source.spanStyles.forEach { span ->
        var low = 0
        var high = ends.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ends[mid] < span.start) low = mid + 1 else high = mid
        }
        var index = low
        while (index < starts.size && starts[index] <= span.end) {
            val start = starts[index]
            val end = ends[index]
            if ((start == 0 && end == source.length) ||
                ((start == end || span.start == span.end) && start == span.start) ||
                (start < span.end && span.start < end)) {
                val bucket = styles[index] ?: ArrayList<AnnotatedString.Range<SpanStyle>>().also { styles[index] = it }
                bucket += AnnotatedString.Range(span.item, maxOf(start, span.start) - start,
                    minOf(end, span.end) - start, span.tag)
            }
            index++
        }
    }
    return buildAnnotatedString {
        starts.indices.forEach { index ->
            append(AnnotatedString(displayText.substring(starts[index], ends[index]), styles[index].orEmpty()))
            // One editor character for one visual object. In particular, a display equation may
            // contain source newlines, which cannot be an inline-content replacement range.
            if (index < ranges.size) appendInlineContent("preview-$index", "\uFFFC")
        }
        source.paragraphStyles.forEach { range ->
            val start = offsets.toDisplay(range.start)
            val end = offsets.toDisplay(range.end) + if (range.end > range.start) offsets.insertionsAt(range.end) else 0
            if (end > start) addStyle(range.item, start, end)
        }
    }
}

internal fun projectedOffset(sourceOffset: Int, ranges: List<IntRange>): Int = PreviewOffsetIndex(ranges).toDisplay(sourceOffset)

/**
 * [projectedOffset] the other way: the source offset at [displayOffset]. Just after an object's
 * character is just after the source it stands for.
 */
internal fun sourceOffset(displayOffset: Int, ranges: List<IntRange>): Int = PreviewOffsetIndex(ranges).toSource(displayOffset)

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
