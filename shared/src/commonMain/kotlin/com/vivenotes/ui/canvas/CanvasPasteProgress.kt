package com.vivenotes.ui.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.vivenotes.localization.Strings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal object CanvasPasteTags {
    const val Progress = "canvas-paste-progress"
}

/**
 * The wait for a paste from another app, where its top-left will be: a picture downloading, a long
 * page being read. [point] is in page dp, placed through [scale] (zoom × density) and the scroll as
 * the canvas's right-click menu is, and drawn at its own size whatever the zoom, since it is not
 * part of the page. The scroll is read while placing, so scrolling moves it without recomposing.
 *
 * It appears only once the wait has lasted a moment, so a paste of a few words does not flash one.
 * Contained, as the online picture search's download is: it sits on the page's own colours, which
 * an uncontained indicator could vanish into.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CanvasPasteProgress(point: Offset, scale: Float, horizontal: ScrollState, vertical: ScrollState) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PASTE_PROGRESS_DELAY_MS)
        shown = true
    }
    val description = Strings.pasting
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = Modifier.offset {
            IntOffset((point.x * scale - horizontal.value).roundToInt(), (point.y * scale - vertical.value).roundToInt())
        },
    ) {
        ContainedLoadingIndicator(
            modifier = Modifier
                .testTag(CanvasPasteTags.Progress)
                .semantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}

/** How long a paste from another app runs before [CanvasPasteProgress] says so. */
private const val PASTE_PROGRESS_DELAY_MS = 150L
