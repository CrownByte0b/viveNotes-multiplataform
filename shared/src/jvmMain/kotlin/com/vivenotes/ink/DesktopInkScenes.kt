package com.vivenotes.ink

import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.kit.InkProjectionKey
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.data.automaticColorOr
import com.vivenotes.model.PageStyle
import com.vivenotes.ui.shell.canvasPalette
import androidx.compose.ui.graphics.toArgb
import java.util.Collections

/** Only immutable scene values live with geometry; renderer paths and rasters remain UI-owned. */
internal class DesktopInkSceneData(projections: List<PageStroke>, canvasInk: Int) {
    internal val preparationThread: String = Thread.currentThread().name
    val scene = InkScene(projections.map {
        InkSceneStroke(it.stroke, it.strokeToPageTransform(),
            automaticColorOr(it.stroke.brush.colorIntArgb, it.colorFollowsTheme, canvasInk))
    })
    val byRow: Map<String, List<InkSceneStroke>> = Collections.unmodifiableMap(
        buildMap<String, MutableList<InkSceneStroke>> {
            projections.forEachIndexed { index, projection ->
                getOrPut(projection.id) { ArrayList() }.add(scene.strokes[index])
            }
        }.mapValues { Collections.unmodifiableList(it.value) })
    val byProjection: Map<InkProjectionKey, InkSceneStroke> = Collections.unmodifiableMap(
        projections.mapIndexed { index, projection -> projection.projectionKey to scene.strokes[index] }.toMap())
}

/** The page palette has two foregrounds, independent of zoom, density and paper colour. */
internal fun DesktopInkPage.prepareCanvasScenes() {
    val colors = listOf(canvasPalette(PageStyle(), false).ink.toArgb(), canvasPalette(PageStyle(), true).ink.toArgb())
    for (segment in segments()) if (segment.projections.isNotEmpty()) {
        for (color in colors) segment.sceneData(color)
    }
}
