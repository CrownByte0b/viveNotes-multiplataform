package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vivenotes.ui.components.BoundTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.Outline
import com.vivenotes.model.findVideoLinks
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.ui.ribbon.draw.measuredTableRows
import com.vivenotes.workspace.TABLE_GUTTER
import com.vivenotes.workspace.primeHeight

/** Measures the complete paste without drawing, exposing semantics, or changing the document. */
@Composable
internal fun CanvasPasteMeasurement(
    request: CanvasPasteLayoutRequest,
    thumbnails: VideoThumbnailSource?,
    onMeasured: (Map<String, Float>) -> Unit,
) {
    val heights = remember(request) { mutableStateMapOf<String, Float>() }
    val ink = MaterialTheme.colorScheme.onSurface
    val colors = RichTextColors(ink, ink, ink)
    Layout(modifier = Modifier.clearAndSetSemantics {}, content = {
        request.outlines.forEach { outline ->
            key(outline.id) {
                when (outline) {
                    is Outline.Text -> MeasurePastedText(outline, thumbnails, colors) { heights[outline.id] = it }
                    is Outline.Table -> {
                        val height = TABLE_GUTTER + measuredTableRows(outline, colors).sum()
                        SideEffect { heights[outline.id] = height }
                    }
                    else -> SideEffect { heights[outline.id] = outline.primeHeight() }
                }
            }
        }
    }) { measurables, constraints ->
        measurables.forEach { it.measure(Constraints()) }
        layout(constraints.minWidth, constraints.minHeight) {}
    }
    val measured = heights.toMap()
    LaunchedEffect(request, measured) {
        if (request.outlines.all { it.id in measured }) onMeasured(measured)
    }
}

@Composable
private fun MeasurePastedText(
    outline: Outline.Text,
    thumbnails: VideoThumbnailSource?,
    colors: RichTextColors,
    onMeasured: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val ink = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = ink)
    val buffer = remember(outline.blocks) { RichTextBuffer(outline.blocks) }
    val styled = buffer.asAnnotatedString(colors)
    var height by remember { mutableStateOf<Float?>(null) }
    var editor by remember { mutableStateOf<TextLayoutResult?>(null) }
    var preview by remember { mutableStateOf<PreviewLayout?>(null) }
    var prepared by remember(outline.blocks, thumbnails) {
        mutableStateOf(thumbnails == null || findVideoLinks(buffer.text).isEmpty())
    }
    Box(Modifier.width(outline.width.coerceIn(120f, 2000f).dp)
        .heightIn(min = maxOf(outline.minHeight, 80f).dp)
        .onSizeChanged { height = it.height / density.density }) {
        if (styled.isNotEmpty()) TextBoxPreviews(
            source = styled, style = style, ink = ink,
            availableWidthDp = outline.width - 16f,
            thumbnails = thumbnails, onEdit = {}, onOpenVideo = {},
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            storedEquations = buffer.storedEquationPreviews(), links = buffer.linkSpans(),
            blocks = outline.blocks, colors = colors,
            onPreviewLayout = { preview = it }, onPreparationChanged = { prepared = it },
        )
        BoundTextField(
            value = TextFieldValue(styled), onValueChange = {}, enabled = false, readOnly = true,
            separateBlocks = true, minLines = 3, textStyle = style,
            onTextLayout = { editor = it },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
    }
    LaunchedEffect(height, editor, preview, prepared, density) {
        val measured = height ?: return@LaunchedEffect
        if (prepared && editor?.multiParagraph?.intrinsics?.hasStaleResolvedFonts == false &&
            preview?.layout?.multiParagraph?.intrinsics?.hasStaleResolvedFonts != true) onMeasured(measured)
    }
}
